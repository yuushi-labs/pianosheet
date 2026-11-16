package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.score.Accidental
import com.yuushilabs.pianosheet.domain.score.KeySignature
import com.yuushilabs.pianosheet.domain.score.Mode
import com.yuushilabs.pianosheet.domain.score.NoteValue
import com.yuushilabs.pianosheet.domain.score.QuantizationSettings
import com.yuushilabs.pianosheet.domain.score.QuantizeMode
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.ScoreMath
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import com.yuushilabs.pianosheet.domain.score.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreBuilderTest {

    private fun note(pitch: Int, onset: Double, duration: Double = 0.45, amplitude: Float = 0.8f) =
        FinalNote(pitch = pitch, onsetSec = onset, durationSec = duration, amplitude = amplitude)

    /** Four quarter notes at 120 BPM. */
    private fun melodyAt120(count: Int = 8, pitches: IntArray = intArrayOf(60, 62, 64, 65)) =
        (0 until count).map { index -> note(pitches[index % pitches.size], index * 0.5) }

    private fun build(notes: List<FinalNote>, quantization: QuantizationSettings = QuantizationSettings()) =
        ScoreBuilder.build(
            projectId = "p1",
            title = "テスト",
            notes = notes,
            durationSec = (notes.maxOfOrNull { it.endSec } ?: 0.0) + 0.5,
            options = ScoreBuildOptions(quantization = quantization),
        )

    @Test
    fun `a steady melody gets its tempo and quarter-note values`() {
        val score = build(melodyAt120())
        assertEquals(120.0, score.tempoBpm, 3.0)
        assertEquals(8, score.noteCount)
        assertTrue(score.measures.isNotEmpty())
        val values = score.notes.map { it.noteValue }.distinct()
        assertEquals(listOf(NoteValue.QUARTER), values)
        // Consecutive beats, starting on beat 0.
        assertEquals(0.0, score.notes[0].startBeat, 1e-6)
        assertEquals(1.0, score.notes[1].startBeat, 1e-6)
        assertEquals(2.0, score.notes[2].startBeat, 1e-6)
    }

    @Test
    fun `notes are sorted by onset then pitch`() {
        val score = build(
            listOf(
                note(67, 1.0),
                note(60, 0.0),
                note(64, 0.0),
                note(62, 0.5),
            ),
        )
        val ordered = score.notes.map { it.startBeat to it.midiPitch }
        assertEquals(ordered.sortedWith(compareBy({ it.first }, { it.second })), ordered)
    }

    @Test
    fun `readability mode snaps a sloppy rhythm, performance mode keeps it`() {
        // Uneven human timing: 0.53 s, 0.48 s then 0.51 s between quarter notes.
        val sloppy = listOf(0.0, 0.53, 1.01, 1.52).map { onset -> note(60, onset) }
        val readable = build(sloppy, QuantizationSettings(QuantizeMode.READABILITY, 0.25, 1.0))
        val performed = build(sloppy, QuantizationSettings(QuantizeMode.PERFORMANCE_TIME, 0.25, 1.0))

        val readableGaps = readable.notes.map { it.startBeat }.zipWithNext { a, b -> b - a }
        assertTrue("snapped to the beat: $readableGaps", readableGaps.all { kotlin.math.abs(it - 1.0) < 1e-6 })

        val performedGaps = performed.notes.map { it.startBeat }.zipWithNext { a, b -> b - a }
        assertTrue("the played timing survives: $performedGaps", performedGaps.any { it > 1.03 || it < 0.97 })
    }

    @Test
    fun `gaps become rests on the staff that is silent`() {
        // Two notes then a half-measure of silence at 120 BPM (4/4 = 4 beats = 2 s).
        val notes = listOf(note(72, 0.0), note(72, 0.5))
        val score = build(notes)
        assertTrue("rests are generated: ${score.rests.size}", score.rests.isNotEmpty())
        assertTrue(score.rests.all { it.staff == Staff.TREBLE || it.staff == Staff.BASS })
        val totalRest = score.rests.filter { it.staff == Staff.TREBLE }.sumOf { it.durationBeats }
        assertTrue("a bar of silence is filled with rests: $totalRest", totalRest >= 2.0)
    }

    @Test
    fun `hands are split by register and stay continuous inside the melody`() {
        val notes = build(
            listOf(
                // Right hand around C4/D4 (the split lands at middle C) and a low bass.
                note(62, 0.0), note(60, 0.5), note(60, 1.0), note(60, 1.5),
                note(48, 0.0), note(48, 1.0), note(43, 2.0),
            ),
        )
        val treble = notes.notes.filter { it.staff == Staff.TREBLE }.map { it.midiPitch }
        val bass = notes.notes.filter { it.staff == Staff.BASS }.map { it.midiPitch }
        assertTrue("the melody stays in the right hand: $treble", treble.count { it == 60 } >= 3)
        assertTrue("the bass notes stay in the left hand: $bass", bass.isNotEmpty() && bass.all { it < 48 || it == 48 })
    }

    @Test
    fun `a wide chord is split between the hands`() {
        val notes = build(listOf(note(48, 0.0), note(76, 0.0), note(52, 0.5), note(79, 0.5)))
        val treble = notes.notes.filter { it.staff == Staff.TREBLE }.map { it.midiPitch }.sorted()
        val bass = notes.notes.filter { it.staff == Staff.BASS }.map { it.midiPitch }.sorted()
        assertEquals(listOf(76, 79), treble)
        assertEquals(listOf(48, 52), bass)
    }

    @Test
    fun `a chromatic note is spelled the way the key writes it`() {
        // Pure spelling rules (the key estimate itself is covered by KeyEstimatorTest).
        val cMajor = KeySignature.C_MAJOR
        assertEquals(Accidental.SHARP, PitchSpeller.spell(66, cMajor).accidental)
        assertEquals(Accidental.SHARP, PitchSpeller.spell(61, cMajor).accidental)
        assertEquals(Accidental.FLAT, PitchSpeller.spell(70, cMajor).accidental)
        assertEquals(null, PitchSpeller.spell(65, cMajor).accidental)
        // In F major B flat is in the signature, so it is written as a plain B.
        val fMajor = KeySignature(-1, Mode.MAJOR)
        assertEquals(null, PitchSpeller.spell(70, fMajor).accidental)
        assertEquals(6, PitchSpeller.spell(70, fMajor).letter)
    }

    @Test
    fun `the same accidental is not repeated inside one measure`() {
        val melody = ArrayList<FinalNote>()
        // Establish C major with a scale, then two chromatic F sharps in one bar.
        val scale = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72, 71, 69, 67, 65)
        scale.forEachIndexed { index, pitch -> melody.add(note(pitch, index * 0.5)) }
        melody.add(note(66, 6.0))
        melody.add(note(66, 6.5))
        val score = build(melody)
        val fSharps = score.notes.filter { it.midiPitch == 66 }
        assertEquals(2, fSharps.size)
        assertEquals(1, fSharps.count { it.accidental != null })
    }

    @Test
    fun `empty input still produces a playable empty score`() {
        val score = build(emptyList())
        assertEquals(0, score.noteCount)
        assertTrue(score.measures.isNotEmpty())
        assertEquals(TimeSignature.COMMON, score.timeSignature)
    }

    @Test
    fun `a very short blip is ignored`() {
        val score = build(listOf(note(60, 0.0, duration = 0.01), note(62, 0.5, duration = 0.4)))
        assertEquals(1, score.noteCount)
        assertEquals(62, score.notes.first().midiPitch)
    }

    @Test
    fun `durations map to written values including dots`() {
        assertEquals(NoteValue.QUARTER to false, ScoreMath.noteValueFor(1.0))
        assertEquals(NoteValue.HALF to false, ScoreMath.noteValueFor(2.0))
        assertEquals(NoteValue.QUARTER to true, ScoreMath.noteValueFor(1.5))
        assertEquals(NoteValue.EIGHTH to false, ScoreMath.noteValueFor(0.5))
        assertEquals(NoteValue.SIXTEENTH to false, ScoreMath.noteValueFor(0.25))
        assertEquals(NoteValue.WHOLE to false, ScoreMath.noteValueFor(4.0))
    }

    @Test
    fun `every note lands inside a measure`() {
        val score = build(melodyAt120(count = 20))
        for (note in score.notes) {
            val measure = score.measures.firstOrNull {
                note.startBeat >= it.startBeat - 1e-6 && note.startBeat < it.endBeat - 1e-9
            }
            assertNotNull("note at ${note.startBeat} has no measure", measure)
            assertEquals(measure!!.index, note.measureIndex)
        }
    }

    @Test
    fun `the key signature is available for spelling`() {
        val score: Score = build(melodyAt120())
        assertTrue(score.keySignature.fifths in -7..7)
        assertEquals(Mode.MAJOR, score.keySignature.mode)
    }

    @Test
    fun `duplicate onset for the same pitch is merged`() {
        val score = build(listOf(note(60, 1.0, duration = 0.2), note(60, 1.01, duration = 0.6)))
        assertEquals(1, score.noteCount)
        assertTrue(score.notes.first().durationBeats >= 0.5)
    }

    @Test
    fun `the staff of a note follows its spelled letter, not the raw semitone`() {
        val score = build(listOf(note(61, 0.0), note(63, 0.5), note(66, 1.0), note(68, 1.5)))
        // C#4 must sit on the C line/space (letter C, octave 4), D#4 on D.
        val cSharp = score.notes.first { it.midiPitch == 61 }
        assertEquals(0, cSharp.letter)
        assertEquals(4, cSharp.octave)
        val dSharp = score.notes.first { it.midiPitch == 63 }
        assertEquals(1, dSharp.letter)
    }

    @Test
    fun `spelling is octave correct around middle C`() {
        val key = KeySignature.C_MAJOR
        val b3 = PitchSpeller.spell(59, key)
        val c4 = PitchSpeller.spell(60, key)
        assertEquals(6, b3.letter)
        assertEquals(3, b3.octave)
        assertEquals(0, c4.letter)
        assertEquals(4, c4.octave)
    }
}
