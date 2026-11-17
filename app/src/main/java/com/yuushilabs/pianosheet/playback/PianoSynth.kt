package com.yuushilabs.pianosheet.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small polyphonic piano voice built from additive synthesis (spec §7.1:
 * "端末内の軽量ポリフォニック・ピアノシンセ").
 *
 * No SoundFont is used: six harmonics with individual decay times and a short
 * hammer attack already read as a piano at this size, and the whole thing is
 * pure Kotlin so the waveform can be unit tested (length, peak, fundamental).
 */
object PianoSynth {

    const val PARTIALS = 6

    /** Attack of the hammer, then the string decays per harmonic. */
    const val ATTACK_SEC = 0.006

    /**
     * How long the damper takes to stop the string after the key is released.
     *
     * It used to be 55 ms with a 19 ms exponential, which left the waveform at
     * ~6 % of its level when the buffer ended: that step is a broadband click at
     * every note end — heard as a tick riding on the (correct) pitch, and it made
     * every note sound cut short.  The release now fades to exactly zero over
     * 180 ms, which is about how long a real damper takes.
     */
    const val RELEASE_SEC = 0.18
    const val BASE_DECAY_SEC = 1.25

    /** Highest output sample value, leaving headroom before 16-bit conversion. */
    const val PEAK = 0.95f

    /** Above this level the limiter bends the waveform instead of cutting it off. */
    const val KNEE = 0.70f

    /**
     * Harmonics above this frequency are rolled off.  Without it a C7 sounds like
     * a whistle: its 2nd–6th partials sit at 4–12 kHz at full level, which is what
     * the user heard as "耳障りな高音キンキン音".
     */
    const val HIGH_ROLLOFF_HZ = 2400.0

    fun frequency(midiPitch: Double): Double = 440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)

    /**
     * Level of [partial] of a note at [midiPitch].
     *
     * Two things make the high register behave like a piano instead of an organ:
     * the spectrum of a high note is *relatively poor* in upper partials (a short,
     * stiff string cannot support them), and everything above [HIGH_ROLLOFF_HZ] is
     * damped.  The low register keeps the bright, slightly detuned spectrum that
     * makes a piano read as a piano.
     */
    fun partialAmplitude(partial: Int, midiPitch: Int = 60): Double {
        if (partial < 1) return 0.0
        val base = 1.0 / partial.toDouble().pow(1.55)
        val octavesAboveMiddleC = ((midiPitch - 60).coerceAtLeast(0)) / 12.0
        val spectralTilt = 1.0 / (1.0 + 0.55 * octavesAboveMiddleC * (partial - 1))
        val frequency = frequency(midiPitch.toDouble()) * partial
        val rolloff = 1.0 / (1.0 + (frequency / HIGH_ROLLOFF_HZ).pow(2))
        return base * spectralTilt * rolloff
    }

    fun partialDecay(partial: Int, midiPitch: Int = 60): Double {
        // Higher strings die faster, and a high note is mostly its fundamental.
        val octavesAboveMiddleC = ((midiPitch - 60).coerceAtLeast(0)) / 12.0
        return BASE_DECAY_SEC / (sqrt(partial.toDouble()) * (1.0 + 0.35 * octavesAboveMiddleC))
    }

    /**
     * Adds one note to [out], starting at [startSample].
     *
     * The note rings [durationSec] and then releases over [RELEASE_SEC]; notes
     * that run past the buffer are simply cut, so callers should size the buffer
     * with a tail.
     */
    fun addNote(
        out: FloatArray,
        startSample: Int,
        sampleRate: Int,
        midiPitch: Int,
        durationSec: Double,
        velocity: Float = 0.8f,
    ) {
        if (durationSec <= 0.0 || startSample >= out.size) return
        val base = frequency(midiPitch.toDouble())
        val nyquist = sampleRate / 2.0
        val gate = durationSec
        val total = gate + RELEASE_SEC
        val sampleCount = (total * sampleRate).toInt().coerceAtMost(out.size - startSample)
        if (sampleCount <= 0) return
        val gain = velocity.coerceIn(0.02f, 1f) * 0.22f * registerGain(midiPitch)
        for (partial in 1..PARTIALS) {
            val frequencyPartial = base * partial * (1.0 + 0.00035 * partial * partial)
            if (frequencyPartial >= nyquist * 0.95) break
            val amplitude = partialAmplitude(partial, midiPitch) * gain
            if (amplitude <= 0.0) continue
            val decay = partialDecay(partial, midiPitch)
            val omega = 2.0 * PI * frequencyPartial / sampleRate
            for (i in 0 until sampleCount) {
                val t = i.toDouble() / sampleRate
                val attack = if (t < ATTACK_SEC) t / ATTACK_SEC else 1.0
                val release = if (t <= gate) {
                    1.0
                } else {
                    // Exponential damp along a raised cosine that reaches zero with
                    // zero slope at gate + RELEASE_SEC: no step at the end.
                    val u = ((t - gate) / RELEASE_SEC).coerceIn(0.0, 1.0)
                    exp(-u * 2.2) * (0.5 + 0.5 * cos(PI * u))
                }
                val envelope = attack * release * exp(-t / decay)
                if (envelope < 1e-4) continue
                out[startSample + i] += (amplitude * envelope * sin(omega * i)).toFloat()
            }
        }
    }

    /** Soft-limits the mix so a dense chord cannot clip the 16-bit output. */
    fun finish(buffer: FloatArray): FloatArray {
        var peak = 0f
        for (value in buffer) peak = maxOf(peak, abs(value))
        if (peak <= KNEE) return buffer
        for (i in buffer.indices) buffer[i] = softLimit(buffer[i])
        return buffer
    }

    /**
     * Linear up to [KNEE], then a tanh bend towards [PEAK].  Ten notes struck at
     * once stay inside full scale without being turned down as a whole, so the
     * quiet part of the same chord keeps its level.
     */
    fun softLimit(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= KNEE) return value
        val headroom = (PEAK - KNEE).toDouble()
        val bent = KNEE + headroom * kotlin.math.tanh((magnitude - KNEE) / headroom)
        return if (value < 0f) -bent.toFloat() else bent.toFloat()
    }

    /** High notes are quieter on a real piano; keep them audible but not thin. */
    fun registerGain(midiPitch: Int): Float {
        val octaves = ((midiPitch - 60).coerceAtLeast(0)) / 12.0
        return (1.0 + 0.16 * octaves).toFloat()
    }

    /**
     * For tests: the strongest frequency in [samples] via a coarse DFT peak.
     * The play path never needs this; the tests use it to prove the rendered
     * note really is the pitch that was asked for.
     */
    fun dominantFrequency(samples: FloatArray, sampleRate: Int, minHz: Double = 50.0, maxHz: Double = 2000.0): Double {
        if (samples.isEmpty()) return 0.0
        val count = minOf(samples.size, sampleRate / 2)
        var bestHz = 0.0
        var bestPower = -1.0
        var hz = minHz
        while (hz <= maxHz) {
            var real = 0.0
            var imaginary = 0.0
            val omega = 2.0 * PI * hz / sampleRate
            for (i in 0 until count) {
                val value = samples[i].toDouble()
                real += value * kotlin.math.cos(omega * i)
                imaginary += value * sin(omega * i)
            }
            val power = real * real + imaginary * imaginary
            if (power > bestPower) {
                bestPower = power
                bestHz = hz
            }
            hz += 4.0
        }
        return bestHz
    }
}
