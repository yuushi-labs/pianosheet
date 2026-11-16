package com.yuushilabs.pianosheet.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicPitchDecoderTest {

    private val frames = BasicPitchConstants.N_FRAMES
    private val bins = BasicPitchConstants.N_SEMITONES

    private fun blank(): Array<FloatArray> = Array(frames) { FloatArray(bins) }

    @Test
    fun `one onset with sustained activation becomes one note`() {
        val note = blank()
        val onset = blank()
        for (t in 10 until 40) note[t][40] = 0.8f
        onset[10][40] = 0.9f

        val result = BasicPitchDecoder.decode(
            note = note,
            onset = onset,
            inferOnsets = false,
            melodiaTrick = false,
        )
        assertEquals(1, result.size)
        val found = result.first()
        assertEquals(61, found.pitchMidi) // bin 40 + MIDI 21 = C#4
        assertEquals(10, found.startFrame)
        assertEquals(40, found.endFrame)
        assertEquals(0.8f, found.amplitude, 1e-4f)
        assertEquals(0.9f, found.onsetStrength, 1e-4f)
    }

    @Test
    fun `a chord yields one note per pitch`() {
        val note = blank()
        val onset = blank()
        for (bin in intArrayOf(36, 40, 43)) {
            for (t in 5 until 50) note[t][bin] = 0.7f
            onset[5][bin] = 0.85f
        }

        val result = BasicPitchDecoder.decode(
            note = note,
            onset = onset,
            inferOnsets = false,
            melodiaTrick = false,
        )
        assertEquals(3, result.size)
        assertEquals(listOf(57, 61, 64), result.map { it.pitchMidi }.sorted())
    }

    @Test
    fun `notes shorter than the minimum length are dropped`() {
        val note = blank()
        val onset = blank()
        for (t in 10 until 15) note[t][40] = 0.9f
        onset[10][40] = 0.9f

        val result = BasicPitchDecoder.decode(
            note = note,
            onset = onset,
            inferOnsets = false,
            melodiaTrick = false,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `inferred onsets find a note whose onset head is weak`() {
        val note = blank()
        val onset = blank()
        // A rising note activation with no onset peak of its own...
        for (t in 20 until 60) note[t][30] = (0.2f + (t - 20) * 0.05f).coerceAtMost(0.9f)
        // ...while another, unrelated peak gives the inferred onsets their scale.
        onset[100][70] = 0.6f

        val result = BasicPitchDecoder.decode(
            note = note,
            onset = onset,
            inferOnsets = true,
            melodiaTrick = false,
        )
        assertTrue("inferred onset should produce a note: $result", result.any { it.pitchMidi == 51 })
    }

    @Test
    fun `zero input yields no notes`() {
        val result = BasicPitchDecoder.decode(blank(), blank())
        assertTrue(result.isEmpty())
    }
}
