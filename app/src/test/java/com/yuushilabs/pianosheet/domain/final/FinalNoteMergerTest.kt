package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.live.Detection
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two "not a key" rules of the merger: blips under 80 ms, and short notes
 * (80–120 ms) that arrive without a real attack.
 *
 * The second rule exists because lowering the decoder's minimum note length to
 * 6 frames let noise blips into noisy takes: T-014 0.923 → 0.857 without it and
 * back to 0.923 with it.
 */
class FinalNoteMergerTest {

    private fun note(pitch: Int, onsetMs: Double, durationMs: Double, onsetStrength: Float?) =
        NoteEvent(
            pitchMidi = pitch,
            onsetMs = onsetMs,
            durationMs = durationMs,
            confidence = 0.6f,
            onsetStrength = onsetStrength,
        )

    @Test
    fun `a blip under 80 ms is dropped`() {
        val result = FinalNoteMerger.suppressGhosts(listOf(note(60, 100.0, 60.0, 0.9f)))
        assertTrue(result.notes.isEmpty())
        assertEquals(1, result.shortDropped)
    }

    @Test
    fun `a short note with a weak onset is dropped`() {
        val result = FinalNoteMerger.suppressGhosts(listOf(note(60, 100.0, 100.0, 0.2f)))
        assertTrue(result.notes.isEmpty())
        assertEquals(1, result.shortDropped)
    }

    @Test
    fun `a short note with a real attack is kept`() {
        val result = FinalNoteMerger.suppressGhosts(listOf(note(60, 100.0, 100.0, 0.62f)))
        assertEquals(1, result.notes.size)
        assertEquals(0, result.shortDropped)
    }

    @Test
    fun `a long weak note is kept`() {
        // A soft held note is a note: the rule only looks at short ones.
        val result = FinalNoteMerger.suppressGhosts(listOf(note(48, 100.0, 900.0, 0.15f)))
        assertEquals(1, result.notes.size)
    }

    @Test
    fun `a detector without onset strength is not punished`() {
        val result = FinalNoteMerger.suppressGhosts(listOf(note(60, 100.0, 100.0, null)))
        assertEquals(1, result.notes.size)
    }

    @Test
    fun `windows that report the same short note twice keep one`() {
        val windows = listOf(
            FinalNoteMerger.WindowResult(0.0, 2.0, listOf(Detection(60, 0.50, 0.63, 0.7f, 0.8f))),
            FinalNoteMerger.WindowResult(1.0, 3.0, listOf(Detection(60, 0.51, 0.63, 0.7f, 0.8f))),
        )
        val merged = FinalNoteMerger.mergeDetailed(windows)
        assertEquals(1, merged.notes.size)
        assertEquals(0, merged.shortDropped)
    }
}
