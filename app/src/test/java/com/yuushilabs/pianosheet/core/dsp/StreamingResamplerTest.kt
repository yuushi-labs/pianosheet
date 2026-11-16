package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StreamingResamplerTest {

    private fun tone(frequency: Double, sampleRate: Int, seconds: Double, amplitude: Double = 0.5): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2.0 * PI * frequency * i / sampleRate)).toFloat() }
    }

    @Test
    fun `48 kHz to 22 point 05 kHz keeps the length and the level`() {
        val input = tone(1000.0, 48000, 0.5)
        val output = StreamingResampler.resample(input, 48000)
        val expected = input.size * 22050 / 48000
        assertTrue("length ${output.size} should be close to $expected", kotlin.math.abs(output.size - expected) < 2000)
        val windowFrom = 2000
        val windowTo = output.size - 200
        val outputRms = DenoiseMath.rmsDb(output.copyOfRange(windowFrom, windowTo))
        // 0.5 amplitude sine = -9 dBFS, allow the filter's passband ripple.
        assertEquals(-9.0, outputRms, 1.0)
    }

    @Test
    fun `content above the new Nyquist is filtered out`() {
        val input = tone(15000.0, 48000, 0.5)
        val output = StreamingResampler.resample(input, 48000)
        val outputRms = DenoiseMath.rmsDb(output.copyOfRange(2000, output.size - 200))
        assertTrue("15 kHz would alias and must be removed, got $outputRms dB", outputRms < -35.0)
    }

    @Test
    fun `same rate is passed through unchanged`() {
        val input = tone(440.0, 22050, 0.1)
        val output = StreamingResampler.resample(input, 22050)
        assertTrue(input.contentEquals(output))
    }
}
