package com.yuushilabs.pianosheet.playback

import kotlin.math.PI
import kotlin.math.abs
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
    const val RELEASE_SEC = 0.055
    const val BASE_DECAY_SEC = 1.25

    /** Highest output sample value, leaving headroom before 16-bit conversion. */
    const val PEAK = 0.95f

    fun frequency(midiPitch: Double): Double = 440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)

    fun partialAmplitude(partial: Int): Double = 1.0 / partial.toDouble().pow(1.55)

    fun partialDecay(partial: Int): Double = BASE_DECAY_SEC / sqrt(partial.toDouble())

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
        val gain = velocity.coerceIn(0.02f, 1f) * 0.22f
        for (partial in 1..PARTIALS) {
            val frequencyPartial = base * partial * (1.0 + 0.00035 * partial * partial)
            if (frequencyPartial >= nyquist * 0.95) break
            val amplitude = partialAmplitude(partial) * gain
            val decay = partialDecay(partial)
            val omega = 2.0 * PI * frequencyPartial / sampleRate
            for (i in 0 until sampleCount) {
                val t = i.toDouble() / sampleRate
                val attack = if (t < ATTACK_SEC) t / ATTACK_SEC else 1.0
                val release = if (t <= gate) 1.0 else exp(-(t - gate) / (RELEASE_SEC * 0.35))
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
        val scale = if (peak > PEAK) PEAK / peak else 1f
        if (scale == 1f) return buffer
        for (i in buffer.indices) buffer[i] *= scale
        return buffer
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
