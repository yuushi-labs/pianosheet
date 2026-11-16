package com.yuushilabs.pianosheet.domain.score

import com.yuushilabs.pianosheet.domain.final.FinalNote
import com.yuushilabs.pianosheet.domain.final.ScoreBuilder
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.NoteOrdering
import com.yuushilabs.pianosheet.domain.model.NoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provisional note list used to print 5.17 s before 5.08 s (Phase 1 bug). */
class NoteOrderingTest {

    private fun note(pitch: Int, onsetMs: Double, sequence: Int): NoteEvent =
        NoteEvent(pitchMidi = pitch, onsetMs = onsetMs, sequence = sequence, state = NoteState.PROVISIONAL)

    @Test
    fun `notes come out in onset order regardless of the emission sequence`() {
        val notes = listOf(
            note(60, 5170.0, sequence = 3),
            note(64, 5080.0, sequence = 4),
            note(62, 5000.0, sequence = 5),
        )
        val ordered = NoteOrdering.chronological(notes).map { it.onsetMs }
        assertEquals(listOf(5000.0, 5080.0, 5170.0), ordered)
    }

    @Test
    fun `a chord is ordered by pitch`() {
        val notes = listOf(
            note(67, 1000.0, 0),
            note(60, 1000.0, 1),
            note(64, 1000.0, 2),
        )
        assertEquals(listOf(60, 64, 67), NoteOrdering.chronological(notes).map { it.pitchMidi })
    }

    @Test
    fun `display indices start at one and follow the sorted order`() {
        val notes = listOf(note(60, 2000.0, 0), note(62, 1000.0, 1))
        val indexed = NoteOrdering.chronologicalIndexed(notes)
        assertEquals(1, indexed.first().first)
        assertEquals(1000.0, indexed.first().second.onsetMs, 1e-6)
    }
}

class ScoreCodecTest {

    private fun score(): Score {
        val notes = (0 until 12).map { index ->
            FinalNote(
                pitch = 60 + (index % 7),
                onsetSec = index * 0.4,
                durationSec = 0.35,
                amplitude = 0.6f,
            )
        }
        return ScoreBuilder.build(
            projectId = "project-1",
            title = "きらきら星 / Twinkle",
            notes = notes,
            durationSec = 6.0,
            liveMatched = 3,
            analysisOnly = 2,
            liveOnly = 1,
        )
    }

    @Test
    fun `a score survives the database round trip`() {
        val original = score()
        val decoded = ScoreCodec.decode(ScoreCodec.encode(original))
        assertTrue(decoded != null)
        decoded!!
        assertEquals(original.projectId, decoded.projectId)
        assertEquals(original.title, decoded.title)
        assertEquals(original.tempoBpm, decoded.tempoBpm, 1e-6)
        assertEquals(original.timeSignature, decoded.timeSignature)
        assertEquals(original.keySignature, decoded.keySignature)
        assertEquals(original.noteCount, decoded.noteCount)
        assertEquals(original.measureCount, decoded.measureCount)
        assertEquals(original.liveMatched, decoded.liveMatched)
        assertEquals(original.analysisOnly, decoded.analysisOnly)
        assertEquals(original.liveOnly, decoded.liveOnly)
        assertEquals(original.notes.map { it.startBeat }, decoded.notes.map { it.startBeat })
        assertEquals(original.notes.map { it.accidental }, decoded.notes.map { it.accidental })
        assertEquals(original.notes.map { it.noteValue }, decoded.notes.map { it.noteValue })
        assertEquals(original.rests.size, decoded.rests.size)
        assertEquals(original.durationSec, decoded.durationSec, 1e-6)
    }

    @Test
    fun `an unknown payload decodes to null`() {
        assertEquals(null, ScoreCodec.decode("not a score"))
        assertEquals(null, ScoreCodec.decode(""))
    }

    @Test
    fun `a truncated payload still gives a usable score`() {
        val encoded = ScoreCodec.encode(score())
        val truncated = encoded.lines().take(10).joinToString("\n")
        val decoded = ScoreCodec.decode(truncated)
        assertTrue(decoded != null)
        assertTrue(decoded!!.measures.isNotEmpty())
    }
}
