package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {

    private val sampleRate = 22050.0

    @Test
    fun `25 Hz high-pass keeps A0 and removes rumble`() {
        val filter = BiquadDesign.highPass(25.0, sampleRate)
        // Values from docs/phase0-report.md and spec v1.3 §20.3.1.
        assertEquals(-2.3, filter.magnitudeDbAt(27.5, sampleRate), 0.3)
        assertEquals(-3.0, filter.magnitudeDbAt(25.0, sampleRate), 0.2)
        assertEquals(-16.0, filter.magnitudeDbAt(10.0, sampleRate), 1.0)
        assertEquals(0.0, filter.magnitudeDbAt(440.0, sampleRate), 0.1)
    }

    @Test
    fun `notch suppresses the mains hum and leaves the passband alone`() {
        val filter = BiquadDesign.notch(50.0, sampleRate, 30.0)
        assertTrue("50 Hz should be cut deeply", filter.magnitudeDbAt(50.0, sampleRate) < -20.0)
        assertTrue("1 kHz should pass", filter.magnitudeDbAt(1000.0, sampleRate) > -0.5)
    }

    @Test
    fun `state carries across chunk boundaries`() {
        val whole = DoubleArray(2048) { i -> kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / sampleRate) }
        val single = BiquadDesign.highPass(25.0, sampleRate)
        val expected = single.process(whole)

        val split = BiquadDesign.highPass(25.0, sampleRate)
        val first = split.process(whole.copyOfRange(0, 1000))
        val second = split.process(whole.copyOfRange(1000, whole.size))
        val joined = first + second
        for (i in whole.indices) {
            assertEquals(expected[i], joined[i], 1e-12)
        }
    }
}
