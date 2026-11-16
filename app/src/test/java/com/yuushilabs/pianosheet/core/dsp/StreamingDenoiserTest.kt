package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class StreamingDenoiserTest {

    @Test
    fun `returns at most what it was given and keeps a bounded delay`() {
        val denoiser = StreamingDenoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD))
        assertEquals(1280, denoiser.delaySamples)

        val chunkSize = 2205 // 100 ms
        val total = 22050
        var produced = 0
        var sent = 0
        val random = Random(11)
        repeat(total / chunkSize) {
            val chunk = FloatArray(chunkSize) { (random.nextDouble() * 0.06 - 0.03).toFloat() }
            val output = denoiser.processChunk(chunk)
            // A chunk can release what earlier chunks held back, but never more
            // than one frame's worth of carried audio.
            assertTrue(output.size <= chunk.size + 1024)
            produced += output.size
            sent += chunk.size
        }
        assertTrue("should emit most of the audio", produced > sent - denoiser.delaySamples - 512)
        assertTrue("must not run ahead of its input", produced <= sent)
    }

    @Test
    fun `a steady tone passes and noise is attenuated`() {
        val denoiser = StreamingDenoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD))
        val sr = 22050
        val random = Random(3)
        val input = FloatArray(sr * 2) { i ->
            (0.3 * sin(2.0 * PI * 440.0 * i / sr) + (random.nextDouble() - 0.5) * 0.05).toFloat()
        }
        val collected = ArrayList<Float>()
        var offset = 0
        while (offset < input.size) {
            val end = minOf(offset + 2205, input.size)
            collected.addAll(denoiser.processChunk(input.copyOfRange(offset, end)).toList())
            offset = end
        }
        val output = collected.toFloatArray()
        assertTrue("should produce most of the signal", output.size > input.size - 2000)
        val windowFrom = 8000
        val windowTo = minOf(20000, output.size)
        val inputRms = DenoiseMath.rmsDb(input.copyOfRange(windowFrom, windowTo))
        val outputRms = DenoiseMath.rmsDb(output.copyOfRange(windowFrom, windowTo))
        // Noise below the tone is removed, the tone itself stays.
        assertTrue("tone should be preserved: $inputRms -> $outputRms", outputRms > inputRms - 3.0)
    }
}
