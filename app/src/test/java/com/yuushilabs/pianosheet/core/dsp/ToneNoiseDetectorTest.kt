package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The take from the device carried a very narrow, very steady high tone.  These
 * tests build such a tone next to a piano-like signal and check three things:
 * the tone is found, the analysis copy loses it, and the piano does not.
 */
class ToneNoiseDetectorTest {

    private val sampleRate = 22050

    /** Amplitude of one frequency in a signal, by Goertzel (normalised per sample). */
    private fun magnitudeAt(samples: FloatArray, frequency: Double): Double {
        val omega = 2.0 * PI * frequency / sampleRate
        val coefficient = 2.0 * kotlin.math.cos(omega)
        var s1 = 0.0
        var s2 = 0.0
        for (sample in samples) {
            val s0 = sample.toDouble() + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
        return kotlin.math.sqrt(power.coerceAtLeast(0.0)) / samples.size * 2.0
    }

    private fun db(value: Double): Double = 20.0 * kotlin.math.log10(value + 1e-12)

    /** A few seconds of repeated piano-ish notes (six partials, decaying). */
    private fun piano(seconds: Double = 4.0, fundamental: Double = 261.63, interval: Double = 0.5): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val out = FloatArray(n)
        var start = 0
        while (start < n) {
            val length = (0.45 * sampleRate).toInt().coerceAtMost(n - start)
            for (i in 0 until length) {
                val t = i.toDouble() / sampleRate
                val envelope = exp(-t / 0.6)
                var value = 0.0
                for (partial in 1..6) {
                    val frequency = fundamental * partial
                    if (frequency >= sampleRate / 2.0) break
                    value += sin(2.0 * PI * frequency * t) * env(partial) * envelope
                }
                out[start + i] = (0.2 * value + out[start + i]).toFloat()
            }
            start += (interval * sampleRate).toInt()
        }
        return out
    }

    private fun env(partial: Int): Double = 1.0 / Math.pow(partial.toDouble(), 1.5)

    private fun withTone(clean: FloatArray, frequency: Double, amplitude: Double = 0.05): FloatArray =
        FloatArray(clean.size) { i ->
            (clean[i] + amplitude * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
        }

    @Test
    fun `a steady 3210 Hz tone is found`() {
        val tones = ToneNoiseDetector.detect(withTone(piano(), 3210.0), sampleRate)
        assertTrue("expected one tone, got $tones", tones.isNotEmpty())
        assertEquals(3210.0, tones.first().frequencyHz, 30.0)
        assertTrue("presence ${tones.first().presence}", tones.first().presence >= ToneNoiseDetector.PRESENCE_MIN)
    }

    @Test
    fun `the analysis copy loses the tone and keeps the piano`() {
        val input = withTone(piano(), 3210.0)
        val withoutNotch = Denoiser(
            DenoiseConfig(strength = DenoiseStrength.STANDARD, toneNotchEnabled = false),
        ).process(input)
        val denoiser = Denoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD))
        val withNotch = denoiser.process(input)

        assertTrue("tones ${denoiser.lastToneNotchesHz}", denoiser.lastToneNotchesHz.isNotEmpty())
        val toneBefore = db(magnitudeAt(withoutNotch, 3210.0))
        val toneAfter = db(magnitudeAt(withNotch, 3210.0))
        assertTrue("tone should drop by >= 12 dB: $toneBefore -> $toneAfter", toneAfter < toneBefore - 12.0)

        for (partial in intArrayOf(261, 523, 785)) {
            val before = db(magnitudeAt(withoutNotch, partial.toDouble()))
            val after = db(magnitudeAt(withNotch, partial.toDouble()))
            assertTrue(
                "piano partial $partial Hz should stay within 2 dB: $before -> $after",
                kotlin.math.abs(after - before) < 2.0,
            )
        }
    }

    @Test
    fun `a repeated high note is not mistaken for a tone`() {
        // 0.15 s of sound every 0.4 s: a musical pattern, not a whistle.
        val n = (4.0 * sampleRate).toInt()
        val signal = FloatArray(n) { i ->
            val position = i % (0.4 * sampleRate).toInt()
            if (position < 0.15 * sampleRate) {
                val t = position.toDouble() / sampleRate
                (0.3 * exp(-t / 0.12) * sin(2.0 * PI * 1568.0 * t)).toFloat()
            } else {
                0f
            }
        }
        val tones = ToneNoiseDetector.detect(signal, sampleRate)
        assertTrue("a musical pattern must not be notched: $tones", tones.isEmpty())
    }

    @Test
    fun `the live tracker locks on after about half a second`() {
        val signal = withTone(piano(), 3210.0)
        val tracker = ToneNoiseTracker(sampleRate)
        var reported: List<ToneNotch> = emptyList()
        var offset = 0
        val hop = 256
        while (offset + 1024 <= signal.size) {
            val frame = DoubleArray(1024) { i ->
                signal[offset + i].toDouble() * Fft.periodicHann(1024)[i]
            }
            val (re, im) = Fft.rfft(frame, 1024)
            val magnitude = DoubleArray(513) { b -> kotlin.math.sqrt(re[b] * re[b] + im[b] * im[b]) }
            reported = tracker.push(magnitude)
            if (reported.isNotEmpty()) break
            offset += hop
        }
        assertTrue("tracker never reported a tone", reported.isNotEmpty())
        assertEquals(3210.0, reported.first().frequencyHz, 40.0)
        assertTrue("decided too slowly: ${offset.toDouble() / sampleRate}s", offset.toDouble() / sampleRate < 1.5)
    }

    @Test
    fun `the live denoiser removes the tone from the streamed audio`() {
        val input = withTone(piano(), 3210.0)
        val denoiser = StreamingDenoiser(DenoiseConfig(strength = DenoiseStrength.WEAK))
        val output = ArrayList<Float>(input.size)
        var offset = 0
        val chunk = 1024
        while (offset < input.size) {
            val end = minOf(offset + chunk, input.size)
            output.addAll(denoiser.processChunk(input.copyOfRange(offset, end)).toList())
            offset = end
        }
        val processed = output.toFloatArray()
        assertTrue(processed.size > input.size / 2)
        val tailInput = input.copyOfRange(input.size / 2, input.size)
        val tailOutput = processed.copyOfRange(processed.size / 2, processed.size)
        val before = db(magnitudeAt(tailInput, 3210.0))
        val after = db(magnitudeAt(tailOutput, 3210.0))
        assertTrue("streamed tone should drop: $before -> $after", after < before - 6.0)
    }
}
