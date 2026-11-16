package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class DenoiserTest {

    private fun sine(frequency: Double, seconds: Double, sampleRate: Int = 22050, amplitude: Double = 0.3): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2.0 * PI * frequency * i / sampleRate)).toFloat() }
    }

    @Test
    fun `off returns the input unchanged`() {
        val input = sine(440.0, 0.2)
        val output = Denoiser(DenoiseConfig(strength = DenoiseStrength.OFF)).process(input)
        assertTrue(input.contentEquals(output))
    }

    @Test
    fun `processing keeps the sample count and stays finite`() {
        val input = sine(440.0, 0.5)
        val output = Denoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD)).process(input)
        assertEquals(input.size, output.size)
        assertTrue(output.all { it.isFinite() })
    }

    @Test
    fun `steady noise is reduced`() {
        val random = Random(7)
        val input = FloatArray(22050) { (random.nextDouble() * 0.04 - 0.02).toFloat() }
        val output = Denoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD)).process(input)
        // Skip the fade-in at the very start.
        val inputRms = DenoiseMath.rmsDb(input.copyOfRange(500, input.size))
        val outputRms = DenoiseMath.rmsDb(output.copyOfRange(500, output.size))
        assertTrue("noise should drop: $inputRms -> $outputRms", outputRms < inputRms - 1.5)
    }

    @Test
    fun `a steady tone survives the standard setting`() {
        val input = sine(440.0, 0.5, amplitude = 0.3)
        val output = Denoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD)).process(input)
        val inputRms = DenoiseMath.rmsDb(input.copyOfRange(500, input.size))
        val outputRms = DenoiseMath.rmsDb(output.copyOfRange(500, output.size))
        assertTrue("tone should stay within 3 dB: $inputRms -> $outputRms", outputRms > inputRms - 3.0)
    }

    @Test
    fun `mains hum detection picks 50 Hz only when it is there`() {
        val sr = 22050
        val withHum = FloatArray(sr) { i ->
            (0.3 * sin(2.0 * PI * 440.0 * i / sr) + 0.05 * sin(2.0 * PI * 50.0 * i / sr)).toFloat()
        }
        assertEquals(50.0, MainsHumDetector.detect(withHum, sr) ?: -1.0, 1e-6)

        val clean = sine(440.0, 1.0)
        assertEquals(null, MainsHumDetector.detect(clean, sr))
    }
}
