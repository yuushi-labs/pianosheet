package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.domain.live.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules that keep a normal performance from turning into hundreds of notes.
 * Everything here is a physical check (a click, a hum, an overtone), so a
 * musician's soft or short-but-real note has to survive.
 */
class NoteCandidateFilterTest {

    private fun note(
        pitch: Int,
        onset: Double,
        duration: Double,
        confidence: Float = 0.6f,
        onsetStrength: Float = 0.8f,
    ) = Detection(
        pitchMidi = pitch,
        onsetS = onset,
        endS = onset + duration,
        confidence = confidence,
        onsetStrength = onsetStrength,
    )

    @Test
    fun `a decay tail the model re-reported is dropped`() {
        val result = NoteCandidateFilter.filter(
            listOf(
                note(60, 0.0, 0.5, onsetStrength = 0.9f),
                note(60, 0.9, 0.3, onsetStrength = 0.05f),
            ),
        )
        assertEquals(1, result.kept.size)
        assertEquals(1, result.droppedWeakOnset)
    }

    @Test
    fun `clicks shorter than 80 ms are dropped`() {
        val result = NoteCandidateFilter.filter(
            listOf(note(72, 1.0, 0.04), note(74, 1.2, 0.2)),
        )
        assertEquals(listOf(74), result.kept.map { it.pitchMidi })
        assertEquals(1, result.droppedTooShort)
    }

    @Test
    fun `a weak high-register blip is dropped but a struck high note stays`() {
        val weakGhost = note(100, 2.0, 0.12, confidence = 0.2f, onsetStrength = 0.3f)
        val playedHigh = note(100, 3.0, 0.25, confidence = 0.6f, onsetStrength = 0.85f)
        val result = NoteCandidateFilter.filter(listOf(weakGhost, playedHigh))
        assertEquals(listOf(3.0), result.kept.map { it.onsetS })
        assertEquals(1, result.droppedHighRegister)
    }

    @Test
    fun `an octave ghost of a strong bass note is dropped`() {
        val bass = note(48, 0.0, 0.9, confidence = 0.9f, onsetStrength = 0.95f)
        val octaveGhost = note(60, 0.01, 0.9, confidence = 0.25f, onsetStrength = 0.7f)
        val result = NoteCandidateFilter.filter(listOf(bass, octaveGhost))
        assertEquals(listOf(48), result.kept.map { it.pitchMidi })
        assertEquals(1, result.droppedOvertone)
    }

    @Test
    fun `a real octave played together is kept`() {
        val bass = note(48, 0.0, 0.9, confidence = 0.7f)
        val octave = note(60, 0.0, 0.9, confidence = 0.6f)
        val result = NoteCandidateFilter.filter(listOf(bass, octave))
        assertEquals(listOf(48, 60), result.kept.map { it.pitchMidi })
        assertEquals(0, result.droppedOvertone)
    }

    @Test
    fun `a twelfth above a quiet note is kept`() {
        // The lower note is barely there, so it cannot be the source of the upper one.
        val quiet = note(55, 0.0, 0.5, confidence = 0.2f)
        val upper = note(74, 0.0, 0.5, confidence = 0.6f)
        val result = NoteCandidateFilter.filter(listOf(quiet, upper))
        assertTrue(result.kept.any { it.pitchMidi == 74 })
    }

    @Test
    fun `an ordinary chord is untouched`() {
        val chord = listOf(
            note(60, 1.0, 0.5),
            note(64, 1.0, 0.5),
            note(67, 1.0, 0.5),
        )
        val result = NoteCandidateFilter.filter(chord)
        assertEquals(3, result.kept.size)
        assertEquals(0, result.droppedTotal)
    }
}
