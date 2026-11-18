package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.export.MusicXmlExporter
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * How a take begins.
 *
 * The user asked for two things in one go: the wait between pressing record and
 * the first note must not be printed as empty bars at the head of the score, and
 * a start off the beat (an upbeat, an eighth of a pickup) has to be notated as a
 * pickup measure instead of being forced onto a downbeat.
 *
 * The rule the implementation keeps, and these tests pin: **beat 0 is the first
 * note the model heard after the take was audible, and the first measure starts
 * there.**  So measure 1 can never open with a rest, and the pickup measure is
 * exactly the beats between that note and the first downbeat.
 */
class ScoreStartTest {

    private fun note(pitch: Int, onset: Double, duration: Double = 0.45, amplitude: Float = 0.65f) =
        FinalNote(pitch, onset, duration, amplitude)

    /**
     * A 4/4 take at 120 BPM: a melody on every beat and a loud bass note on every
     * downbeat, beginning with the first full bar at [firstBarSec].  [pickupSec]
     * lists how long before that first bar the pickup notes are played.
     */
    private fun take(
        firstBarSec: Double,
        bars: Int = 6,
        pickupSec: List<Double> = emptyList(),
    ): MutableList<FinalNote> {
        val beat = 0.5
        val melody = intArrayOf(72, 74, 76, 77)
        val notes = ArrayList<FinalNote>()
        for (offset in pickupSec) notes.add(note(70, firstBarSec - offset, 0.4, 0.7f))
        for (bar in 0 until bars) {
            val barStart = firstBarSec + bar * 4 * beat
            notes.add(note(48, barStart, 0.9, 1.0f))
            for (index in 0 until 4) {
                notes.add(note(melody[(bar * 4 + index) % melody.size], barStart + index * beat))
            }
        }
        return notes
    }

    private fun build(
        notes: List<FinalNote>,
        durationSec: Double = notes.maxOf { it.endSec } + 1.0,
        soundStartSec: Double? = null,
    ): Score = ScoreBuilder.build(
        projectId = "take",
        title = "録音",
        notes = notes,
        durationSec = durationSec,
        options = ScoreBuildOptions(soundStartSec = soundStartSec),
    )

    /** The first attack of the score and the first measure it lands in. */
    private fun assertStartsOnTheFirstNote(score: Score, expectedFirstNote: Double) {
        val firstNote = score.notes.minByOrNull { it.startBeat }!!
        assertEquals("the score starts at the first played note", expectedFirstNote, score.scoreStartSec, 1e-6)
        assertEquals("the first note is beat 0", 0.0, firstNote.startBeat, 1e-6)
        assertEquals("the first note is in measure 1", 0, firstNote.measureIndex)
        assertEquals("no bar is counted out before measure 1", 0.0, score.measures.first().startBeat, 1e-9)
        // Nothing is written before the first sound: only the staff that holds the
        // first attack could lead with a rest, and it must not.
        val leadRests = score.rests.filter {
            it.staff == firstNote.staff && it.startBeat < firstNote.startBeat - 1e-6
        }
        assertTrue("a rest leads the staff of the first note: $leadRests", leadRests.isEmpty())
        // Measures before the one holding the first note would be empty bars.
        val firstMeasure = score.notes.minOf { it.measureIndex }
        assertTrue("empty measures before the first note", firstMeasure == 0)
    }

    // ------------------------------------------------------- the silent lead

    @Test
    fun `a recording that waits before the playing starts the score at the first note`() {
        val score = build(take(firstBarSec = 3.0))
        assertStartsOnTheFirstNote(score, expectedFirstNote = 3.0)
        assertEquals("4/4", "4/4", score.timeSignature.label)
        assertEquals("the first bar is a full bar, not a pickup", 4.0, score.measures.first().beats, 1e-6)
    }

    @Test
    fun `a tap in the silence is not the first measure`() {
        val notes = take(firstBarSec = 6.0)
        notes.add(note(40, 0.3, duration = 0.2, amplitude = 0.8f))
        val score = build(notes, durationSec = 14.0)
        // The tap is gone, and the score is the same as one recorded without it.
        assertStartsOnTheFirstNote(score, expectedFirstNote = 6.0)
        assertEquals(120.0, score.tempoBpm, 3.0)
        assertEquals("tempo and meter survive the stray note", "4/4", score.timeSignature.label)
        assertTrue("the tap must not be notated", score.notes.none { it.midiPitch == 40 })
    }

    @Test
    fun `notes the model heard in the silence are dropped when the take becomes audible`() {
        // What the Gymnopédie fixture does: two invented low notes 23 ms in,
        // 0.8 s before the piano plays; the audio says the take only sounds from
        // 0.78 s.
        val notes = take(firstBarSec = 1.0, bars = 4)
        notes.add(note(43, 0.0233, duration = 1.9, amplitude = 0.6f))
        notes.add(note(71, 0.0233, duration = 0.78, amplitude = 0.34f))
        val trimmed = build(notes, soundStartSec = 0.78)
        assertStartsOnTheFirstNote(trimmed, expectedFirstNote = 1.0)
        assertTrue(trimmed.notes.none { it.midiPitch == 43 || it.midiPitch == 71 })
    }

    @Test
    fun `a take that starts with sound keeps every note`() {
        val notes = take(firstBarSec = 0.0, bars = 4)
        val score = build(notes, soundStartSec = 0.0)
        assertEquals(0.0, score.scoreStartSec, 1e-6)
        assertEquals("no notes are lost", notes.size, score.noteCount)
    }

    @Test
    fun `a wrong sound level never empties the take`() {
        val notes = take(firstBarSec = 2.0, bars = 2)
        val score = build(notes, soundStartSec = 99.0)
        assertEquals("the take is kept", 2.0, score.scoreStartSec, 1e-6)
        assertEquals(notes.size, score.noteCount)
    }

    @Test
    fun `an opening chord that leads into the music is kept`() {
        val notes = take(firstBarSec = 2.0, bars = 4)
        notes.add(note(48, 0.0, duration = 1.2, amplitude = 1.0f))
        notes.add(note(60, 0.0, duration = 1.2, amplitude = 0.9f))
        val score = build(notes)
        assertEquals("the opening chord is beat 0", 0.0, score.scoreStartSec, 1e-6)
        assertEquals(0.0, score.notes.first().startBeat, 1e-6)
    }

    // --------------------------------------------------- upbeats and pickups

    @Test
    fun `a downbeat start has no pickup measure`() {
        val score = build(take(firstBarSec = 2.0))
        assertEquals(4.0, score.measures.first().beats, 1e-6)
        assertEquals(4.0, score.measures[1].startBeat, 1e-6)
        val xml = MusicXmlExporter.toMusicXml(score)
        assertTrue("a downbeat start is not implicit", !firstMeasureTag(xml).contains("implicit"))
    }

    @Test
    fun `a one beat upbeat becomes a one beat pickup measure`() {
        val score = build(take(firstBarSec = 3.0, pickupSec = listOf(0.5)))
        assertStartsOnTheFirstNote(score, expectedFirstNote = 2.5)
        assertEquals("the pickup is one beat", 1.0, score.measures.first().beats, 1e-6)
        assertEquals("the first bar follows it", 1.0, score.measures[1].startBeat, 1e-6)
        val xml = MusicXmlExporter.toMusicXml(score)
        assertTrue("the pickup measure is marked implicit", firstMeasureTag(xml).contains("implicit=\"yes\""))
    }

    @Test
    fun `an offbeat start becomes a half beat pickup measure`() {
        // The first note is the eighth between beat 4 and the next bar line.
        val score = build(take(firstBarSec = 3.0, pickupSec = listOf(0.25)))
        assertStartsOnTheFirstNote(score, expectedFirstNote = 2.75)
        assertEquals("the pickup is the half beat before the bar", 0.5, score.measures.first().beats, 1e-6)
        assertEquals(0.5, score.measures[1].startBeat, 1e-6)
        val xml = MusicXmlExporter.toMusicXml(score, MusicXmlExporter.Options(includeDoctype = false))
        assertTrue(firstMeasureTag(xml).contains("implicit=\"yes\""))
        assertEquals("the first written note of the pickup is the pickup itself", "pitch", firstElementAfterAttributes(xml))
    }

    @Test
    fun `a three beat upbeat is written as a three beat pickup`() {
        val score = build(take(firstBarSec = 3.0, pickupSec = listOf(1.5, 1.0, 0.5)))
        assertStartsOnTheFirstNote(score, expectedFirstNote = 1.5)
        assertEquals(3.0, score.measures.first().beats, 1e-6)
        assertEquals(3.0, score.measures[1].startBeat, 1e-6)
    }

    @Test
    fun `the pickup never leaves an empty measure before the first downbeat`() {
        for (pickup in listOf(emptyList(), listOf(0.5), listOf(0.25), listOf(1.5, 1.0, 0.5))) {
            val score = build(take(firstBarSec = 3.0, pickupSec = pickup))
            val first = score.measures.first()
            assertTrue(
                "pickup of $pickup must be shorter than a bar",
                first.beats <= TimeSignature.COMMON.beatsPerMeasure + 1e-9,
            )
            // The first bar line is at the pickup's end and a note sounds there.
            assertEquals(first.beats, score.measures[1].startBeat, 1e-9)
            val firstDownbeat = score.notes.filter { kotlin.math.abs(it.startBeat - first.endBeat) < 1e-6 }
            assertTrue("nothing sounds on the first downbeat for $pickup", firstDownbeat.isNotEmpty())
        }
    }

    private fun firstMeasureTag(xml: String): String =
        xml.substringAfter("<part id=\"P1\">").substringAfter("<measure").substringBefore(">")

    /** `pitch` for a note, `rest` for a rest: the first thing measure 1 writes. */
    private fun firstElementAfterAttributes(xml: String): String {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val measure = root.getElementsByTagName("measure").item(0) as Element
        val children = measure.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child !is Element) continue
            if (child.tagName == "attributes" || child.tagName == "direction") continue
            if (child.tagName != "note") return child.tagName
            return if (child.getElementsByTagName("rest").length > 0) "rest" else "pitch"
        }
        return "none"
    }
}
