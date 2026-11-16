package com.yuushilabs.pianosheet.core.audio

import com.yuushilabs.pianosheet.playback.PianoSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioResamplerTest {

    private fun sine(seconds: Double, sampleRate: Int, frequency: Double = 440.0): FloatArray =
        FloatArray((seconds * sampleRate).toInt()) { index ->
            (0.5 * sin(2.0 * PI * frequency * index / sampleRate)).toFloat()
        }

    @Test
    fun `downsampling keeps the duration and the pitch`() {
        val input = sine(seconds = 1.0, sampleRate = 48000)
        val output = AudioResampler.resample(input, 48000, 22050)
        assertEquals(22050, output.size)
        val frequency = PianoSynth.dominantFrequency(output, 22050, 300.0, 700.0)
        assertEquals(440.0, frequency, 12.0)
        val peak = output.maxOf { kotlin.math.abs(it) }
        assertTrue("level kept: $peak", peak in 0.3f..0.7f)
    }

    @Test
    fun `upsampling from 16 kHz still finds the note`() {
        val input = sine(seconds = 0.8, sampleRate = 16000, frequency = 523.25)
        val output = AudioResampler.resample(input, 16000, 22050)
        assertTrue(output.size > input.size)
        val frequency = PianoSynth.dominantFrequency(output, 22050, 300.0, 800.0)
        assertEquals(523.25, frequency, 14.0)
    }

    @Test
    fun `the same rate is returned unchanged`() {
        val input = sine(0.2, 22050)
        val output = AudioResampler.resample(input, 22050, 22050)
        assertEquals(input.size, output.size)
        assertEquals(input[100], output[100], 1e-9f)
    }

    @Test
    fun `the filter tail is flushed instead of lost`() {
        val input = sine(seconds = 1.0, sampleRate = 44100)
        val output = AudioResampler.resample(input, 44100, 22050)
        // The last 10 ms must carry signal, not the filter's zero padding.
        val tail = (output.size - 220 until output.size).maxOf { kotlin.math.abs(output[it]) }
        assertTrue("tail level $tail", tail > 0.05f)
    }
}
