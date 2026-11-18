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
    fun `the presets get louder in strength and start from the old weak setting`() {
        // 0.4.4: 0.4.3's "weak" became the default, and the whole table moved one
        // step milder (the user: "今の弱を標準にするぐらい").
        assertEquals(0.70, DenoiseStrength.STANDARD.oversubtraction, 1e-9)
        assertEquals(-8.0, DenoiseStrength.STANDARD.floorDb, 1e-9)
        assertEquals(0.60, DenoiseStrength.STANDARD.gainSmoothing, 1e-9)
        assertTrue(DenoiseStrength.WEAK.oversubtraction < DenoiseStrength.STANDARD.oversubtraction)
        assertTrue(DenoiseStrength.STANDARD.oversubtraction < DenoiseStrength.STRONG.oversubtraction)
        assertTrue(DenoiseStrength.STRONG.floorDb < DenoiseStrength.STANDARD.floorDb)
    }

    @Test
    fun `the high band is cleaned harder than the body of the piano`() {
        val random = Random(11)
        val input = FloatArray(22050) { (random.nextDouble() * 0.06 - 0.03).toFloat() }
        val output = Denoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD)).process(input)

        fun bandDb(samples: FloatArray, from: Double, to: Double): Double {
            val n = 4096
            val window = DoubleArray(n) { 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * it / n) }
            var sum = 0.0
            var frames = 0
            var start = 500
            while (start + n <= samples.size) {
                val frame = DoubleArray(n) { i -> samples[start + i] * window[i] }
                val (re, im) = Fft.rfft(frame, n)
                val binHz = 22050.0 / n
                for (bin in 0 until n / 2) {
                    val hz = bin * binHz
                    if (hz >= from && hz < to) sum += re[bin] * re[bin] + im[bin] * im[bin]
                }
                frames += 1
                start += n
            }
            return 10.0 * kotlin.math.log10(sum / frames / n + 1e-20)
        }

        val bodyDrop = bandDb(input, 300.0, 600.0) - bandDb(output, 300.0, 600.0)
        val highDrop = bandDb(input, 2000.0, 4000.0) - bandDb(output, 2000.0, 4000.0)
        assertTrue(
            "the high band should lose at least 1.5 dB more: body $bodyDrop dB, high $highDrop dB",
            highDrop > bodyDrop + 1.5,
        )
    }

    @Test
    fun `a tone above the keyboard is notched deeper than a note would be`() {
        // 0.4.4: -26 dB over ±3 bins, for the ~1.25 kHz line a room or a speaker
        // leaves in a take.
        assertEquals(-26.0, DenoiseConfig().toneNotchGainDb, 1e-9)
        assertEquals(3, DenoiseConfig().toneNotchHalfWidthBins)
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
