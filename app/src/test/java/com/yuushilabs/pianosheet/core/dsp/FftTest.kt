package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test
    fun `impulse has a flat spectrum`() {
        val input = DoubleArray(1024)
        input[0] = 1.0
        val (re, im) = Fft.rfft(input, 1024)
        for (bin in re.indices) {
            val magnitude = kotlin.math.sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            assertEquals(1.0, magnitude, 1e-9)
        }
    }

    @Test
    fun `sine lands in the expected bin`() {
        val n = 1024
        val sampleRate = 22050.0
        val frequency = 1000.0
        val input = DoubleArray(n) { i -> sin(2.0 * PI * frequency * i / sampleRate) }
        val (re, im) = Fft.rfft(input, n)
        var bestBin = 0
        var best = 0.0
        for (bin in 1 until re.size) {
            val magnitude = kotlin.math.sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            if (magnitude > best) {
                best = magnitude
                bestBin = bin
            }
        }
        val expected = Math.round(frequency / (sampleRate / n)).toInt()
        assertTrue("peak bin $bestBin should be near $expected", kotlin.math.abs(bestBin - expected) <= 1)
    }

    @Test
    fun `round trip reconstructs the input`() {
        val n = 512
        val input = DoubleArray(n) { i -> sin(2.0 * PI * 17.0 * i / n) * 0.7 }
        val (re, im) = Fft.rfft(input, n)
        val restored = Fft.irfft(re, im, n)
        for (i in 0 until n) {
            assertEquals(input[i], restored[i], 1e-9)
        }
    }
}
