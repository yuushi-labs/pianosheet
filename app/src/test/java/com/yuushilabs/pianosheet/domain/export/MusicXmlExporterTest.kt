package com.yuushilabs.pianosheet.domain.export

import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.model.StaffNotation
import com.yuushilabs.pianosheet.domain.score.Accidental
import com.yuushilabs.pianosheet.domain.score.KeySignature
import com.yuushilabs.pianosheet.domain.score.Mode
import com.yuushilabs.pianosheet.domain.score.NoteValue
import com.yuushilabs.pianosheet.domain.score.QuantizationSettings
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.ScoreMath
import com.yuushilabs.pianosheet.domain.score.ScoreMeasure
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import com.yuushilabs.pianosheet.domain.score.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * The MusicXML export has to be readable by a notation program the app has never
 * met, so every test here parses the produced text back with a real XML parser
 * and checks the musical arithmetic instead of matching strings.
 */
class MusicXmlExporterTest {

    // ------------------------------------------------------------- fixtures

    private fun scoreOf(
        notes: List<ScoreNote>,
        timeSignature: TimeSignature = TimeSignature.COMMON,
        key: KeySignature = KeySignature(0, Mode.MAJOR),
        tempo: Double = 90.0,
        measureCount: Int? = null,
        title: String = "録音 11/17 21:03",
    ): Score {
        val lastBeat = notes.maxOfOrNull { it.startBeat + it.durationBeats } ?: 0.0
        val count = measureCount ?: ScoreMath.measureCountFor(lastBeat + 0.001, timeSignature)
        return Score(
            projectId = "p1",
            title = title,
            tempoBpm = tempo,
            tempoConfidence = 0.9,
            timeSignature = timeSignature,
            timeSignatureConfidence = 0.8,
            keySignature = key,
            keyConfidence = 0.7,
            quantization = QuantizationSettings(),
            measures = ScoreMath.measureStartBeats(timeSignature, count),
            notes = notes,
            rests = emptyList(),
            scoreStartSec = 0.0,
            durationSec = 28.5,
            detector = "basic_pitch_onnx",
            createdAt = 1_700_000_000_000L,
        )
    }

    private fun note(
        id: String,
        midi: Int,
        startBeat: Double,
        durationBeats: Double,
        staff: Staff,
        measureIndex: Int = 0,
        velocity: Float = 0.8f,
        dotted: Boolean = false,
    ): ScoreNote {
        val diatonic = StaffNotation.diatonicIndex(midi)
        val letter = ((diatonic % 7) + 7) % 7
        val accidental = when (((midi % 12) + 12) % 12) {
            1, 3, 6, 8, 10 -> Accidental.SHARP
            else -> null
        }
        return ScoreNote(
            id = id,
            midiPitch = midi,
            startBeat = startBeat,
            durationBeats = durationBeats,
            staff = staff,
            noteValue = NoteValue.QUARTER,
            dotted = dotted,
            accidental = accidental,
            startSec = startBeat * 0.666,
            velocity = velocity,
            measureIndex = measureIndex,
            letter = letter,
            octave = midi / 12 - 1,
        )
    }

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(xml.byteInputStream(Charsets.UTF_8))
        return document.documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val result = ArrayList<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element && node.tagName == tag) result.add(node)
            node = node.nextSibling
        }
        return result
    }

    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

    private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()

    private fun xml(score: Score): String = MusicXmlExporter.toMusicXml(
        score,
        MusicXmlExporter.Options(encodingDate = "2026-11-17"),
    )

    // ---------------------------------------------------------------- tests

    @Test
    fun `header describes a piano grand staff`() {
        val score = scoreOf(
            listOf(note("n1", 60, 0.0, 1.0, Staff.TREBLE), note("n2", 48, 0.0, 1.0, Staff.BASS)),
            key = KeySignature(2, Mode.MAJOR),
            tempo = 76.0,
        )
        val root = parse(xml(score))
        assertEquals("score-partwise", root.tagName)
        assertEquals("4.0", root.getAttribute("version"))
        assertEquals("録音 11/17 21:03", root.child("movement-title")?.textContent?.trim())

        val partList = root.child("part-list")
        assertNotNull(partList)
        assertEquals("Piano", partList!!.child("score-part")?.child("part-name")?.textContent?.trim())

        val part = root.child("part")!!
        assertEquals("P1", part.getAttribute("id"))
        val attributes = part.child("measure")!!.child("attributes")!!
        assertEquals("2", attributes.text("staves"))
        assertEquals("4", attributes.text("divisions"))
        assertEquals("2", attributes.child("key")!!.text("fifths"))
        assertEquals("major", attributes.child("key")!!.text("mode"))
        assertEquals("4", attributes.child("time")!!.text("beats"))
        assertEquals("4", attributes.child("time")!!.text("beat-type"))

        val clefs = attributes.children("clef")
        assertEquals(2, clefs.size)
        assertEquals("1", clefs[0].getAttribute("number"))
        assertEquals("G", clefs[0].text("sign"))
        assertEquals("2", clefs[0].text("line"))
        assertEquals("2", clefs[1].getAttribute("number"))
        assertEquals("F", clefs[1].text("sign"))
        assertEquals("4", clefs[1].text("line"))
    }

    @Test
    fun `tempo is written as a metronome direction`() {
        val score = scoreOf(listOf(note("n1", 60, 0.0, 1.0, Staff.TREBLE)), tempo = 92.4)
        val root = parse(xml(score))
        val direction = root.child("part")!!.child("measure")!!.children("direction").first()
        assertEquals("above", direction.getAttribute("placement"))
        val metronome = direction.child("direction-type")!!.child("metronome")!!
        assertEquals("quarter", metronome.text("beat-unit"))
        assertEquals("92", metronome.text("per-minute"))
        assertEquals("92", direction.child("sound")!!.getAttribute("tempo"))
    }

    @Test
    fun `every measure adds up to the meter in both staves`() {
        // A five-measure 4/4 take: chords, a dotted quarter, a note held over the
        // bar line and a silent bass measure are all in here.
        val notes = ArrayList<ScoreNote>()
        notes.add(note("a", 60, 0.0, 1.0, Staff.TREBLE, 0))
        notes.add(note("b", 64, 0.0, 1.0, Staff.TREBLE, 0))
        notes.add(note("c", 67, 0.0, 1.0, Staff.TREBLE, 0))
        notes.add(note("d", 62, 1.0, 1.5, Staff.TREBLE, 0, dotted = true))
        notes.add(note("e", 64, 2.5, 0.5, Staff.TREBLE, 0))
        notes.add(note("f", 65, 3.0, 2.0, Staff.TREBLE, 0)) // ties into measure 2
        notes.add(note("g", 69, 5.0, 0.5, Staff.TREBLE, 1))
        notes.add(note("h", 67, 5.5, 2.5, Staff.TREBLE, 1))
        notes.add(note("i", 60, 12.0, 4.0, Staff.TREBLE, 3))
        notes.add(note("j", 41, 0.0, 4.0, Staff.BASS, 0))
        notes.add(note("k", 43, 4.0, 4.0, Staff.BASS, 1))
        notes.add(note("l", 45, 16.0, 4.0, Staff.BASS, 4))
        val score = scoreOf(notes, measureCount = 5)
        val root = parse(xml(score))

        val divisions = MusicXmlExporter.divisionsFor(score)
        val measureTicks = (score.timeSignature.beatsPerMeasure * divisions).roundToInt()
        val measures = root.child("part")!!.children("measure")
        assertEquals(5, measures.size)

        for ((index, measure) in measures.withIndex()) {
            val perStaff = HashMap<Int, Int>()
            for (noteElement in measure.children("note")) {
                val isChord = noteElement.child("chord") != null
                val duration = noteElement.text("duration")!!.toInt()
                val staff = noteElement.text("staff")!!.toInt()
                if (!isChord) perStaff[staff] = (perStaff[staff] ?: 0) + duration
            }
            assertEquals(
                "measure ${index + 1} staff 1",
                measureTicks,
                perStaff[1] ?: 0,
            )
            assertEquals(
                "measure ${index + 1} staff 2",
                measureTicks,
                perStaff[2] ?: 0,
            )
        }
    }

    @Test
    fun `simultaneous notes become one chord`() {
        val score = scoreOf(
            listOf(
                note("a", 60, 0.0, 1.0, Staff.TREBLE),
                note("b", 64, 0.0, 1.0, Staff.TREBLE),
                note("c", 67, 0.0, 1.0, Staff.TREBLE),
            ),
        )
        val notes = parse(xml(score)).child("part")!!.child("measure")!!
            .children("note").filter { it.text("staff") == "1" && it.child("pitch") != null }
        assertEquals(3, notes.size)
        // Exactly one notehead carries the attack, the other two are <chord/> members.
        assertEquals(1, notes.filter { it.child("chord") == null }.size)
        assertEquals(2, notes.filter { it.child("chord") != null }.size)
        assertEquals(listOf("C", "E", "G"), notes.map { it.child("pitch")!!.text("step") })
    }

    @Test
    fun `a note held over the bar line is split and tied`() {
        val score = scoreOf(
            listOf(note("long", 65, 3.0, 2.0, Staff.TREBLE)),
            measureCount = 2,
        )
        val measures = parse(xml(score)).child("part")!!.children("measure")
        val inFirst = measures[0].children("note").filter { it.text("staff") == "1" }
        val inSecond = measures[1].children("note").filter { it.text("staff") == "1" }
        val held = inFirst.first { it.child("pitch") != null }
        assertEquals("4", held.text("duration"))
        assertEquals("start", held.child("tie")?.getAttribute("type"))
        assertEquals("start", held.child("notations")?.child("tied")?.getAttribute("type"))

        val continued = inSecond.first { it.child("pitch") != null }
        assertEquals("F", continued.child("pitch")!!.text("step"))
        assertEquals("4", continued.text("duration"))
        assertEquals("stop", continued.child("tie")?.getAttribute("type"))
        assertEquals("stop", continued.child("notations")?.child("tied")?.getAttribute("type"))
    }

    @Test
    fun `silence is filled with rests so no measure is short`() {
        val score = scoreOf(listOf(note("a", 60, 0.0, 1.0, Staff.TREBLE)), measureCount = 2)
        val measures = parse(xml(score)).child("part")!!.children("measure")
        // Measure 1: one quarter note then a dotted-half rest (3 beats = 12 ticks).
        val restDurations = measures[0].children("note")
            .filter { it.child("rest") != null && it.text("staff") == "1" }
            .map { it.text("duration")!!.toInt() }
        assertEquals(listOf(12), restDurations)
        // Measure 2 has no notes at all: one whole-measure rest per staff.
        val wholeRests = measures[1].children("note").filter { it.child("rest") != null }
        assertEquals(2, wholeRests.size)
        assertTrue(wholeRests.all { it.text("duration") == "16" })
        assertEquals(listOf("1", "2"), wholeRests.map { it.text("staff") })
    }

    @Test
    fun `dotted values carry a dot and the right type`() {
        val score = scoreOf(listOf(note("a", 60, 0.0, 1.5, Staff.TREBLE, dotted = true)))
        val first = parse(xml(score)).child("part")!!.child("measure")!!
            .children("note").first { it.child("pitch") != null }
        assertEquals("quarter", first.text("type"))
        assertEquals("6", first.text("duration"))
        assertNotNull(first.child("dot"))
    }

    @Test
    fun `key signature, accidental and octave survive the round trip`() {
        val score = scoreOf(
            listOf(note("a", 61, 0.0, 1.0, Staff.TREBLE), note("b", 63, 1.0, 1.0, Staff.TREBLE)),
            key = KeySignature(-3, Mode.MINOR),
        )
        val root = parse(xml(score))
        val attributes = root.child("part")!!.child("measure")!!.child("attributes")!!
        assertEquals("-3", attributes.child("key")!!.text("fifths"))
        assertEquals("minor", attributes.child("key")!!.text("mode"))
        val notes = root.child("part")!!.child("measure")!!.children("note")
            .filter { it.child("pitch") != null }
        val first = notes[0].child("pitch")!!
        assertEquals("C", first.text("step"))
        assertEquals("1", first.text("alter"))
        assertEquals("4", first.text("octave"))
        assertEquals("sharp", notes[0].text("accidental"))
    }

    @Test
    fun `titles with xml characters are escaped and still parse`() {
        val score = scoreOf(
            listOf(note("a", 60, 0.0, 1.0, Staff.TREBLE)),
            title = "Rock & Roll <テスト> \"quoted\"",
        )
        val root = parse(xml(score))
        assertEquals("Rock & Roll <テスト> \"quoted\"", root.child("movement-title")?.textContent?.trim())
    }

    @Test
    fun `empty score still produces one legal measure`() {
        val score = scoreOf(emptyList(), measureCount = 1)
        val measures = parse(xml(score)).child("part")!!.children("measure")
        assertEquals(1, measures.size)
        assertEquals(2, measures[0].children("note").count { it.child("rest") != null })
    }

    @Test
    fun `the exported grid makes every duration a writable value`() {
        // Performance timing (0.3333 beats) has no note value; the page snaps it
        // to the sixteenth grid so `<duration>` and `<type>` always agree.
        val score = scoreOf(listOf(note("a", 60, 0.0, 0.3333, Staff.TREBLE)))
        val root = parse(xml(score))
        assertEquals("4", root.child("part")!!.child("measure")!!
            .child("attributes")!!.text("divisions"))
        val durations = root.getElementsByTagName("note").let { nodes ->
            (0 until nodes.length).mapNotNull { index ->
                val element = nodes.item(index) as? Element ?: return@mapNotNull null
                element.text("duration")?.toInt()
            }
        }
        assertTrue("durations $durations", durations.all { it in setOf(1, 2, 3, 4, 6, 8, 12, 16) })
    }

    @Test
    fun `without a grid the performance timing forces a finer division`() {
        val score = scoreOf(listOf(note("a", 60, 0.0, 0.3333, Staff.TREBLE)))
        val options = MusicXmlExporter.Options(gridBeats = 0.0)
        val divisions = MusicXmlExporter.divisionsFor(score, options)
        assertTrue("divisions $divisions", divisions >= 8)
        val root = parse(MusicXmlExporter.toMusicXml(score, options))
        assertEquals(divisions.toString(), root.child("part")!!.child("measure")!!
            .child("attributes")!!.text("divisions"))
    }

    @Test
    fun `a pickup measure is short and marked implicit`() {
        // 1.5 beats of pickup, then full 4/4 bars: what the bar-phase estimate
        // produces for a performance that starts mid-bar.
        val measures = listOf(
            ScoreMeasure(0, 0.0, 1.5),
            ScoreMeasure(1, 1.5, 4.0),
            ScoreMeasure(2, 5.5, 4.0),
        )
        val notes = listOf(
            note("n1", 60, 0.0, 1.0, Staff.TREBLE, measureIndex = 0),
            note("n2", 62, 1.0, 0.5, Staff.TREBLE, measureIndex = 0),
            note("n3", 48, 1.5, 4.0, Staff.BASS, measureIndex = 1),
            note("n4", 64, 1.5, 2.0, Staff.TREBLE, measureIndex = 1),
            note("n5", 48, 5.5, 4.0, Staff.BASS, measureIndex = 2),
            note("n6", 67, 5.5, 2.0, Staff.TREBLE, measureIndex = 2),
        )
        val score = scoreOf(notes, timeSignature = TimeSignature.COMMON).copy(measures = measures)
        val root = parse(MusicXmlExporter.toMusicXml(score))
        val measuresOut = root.child("part")!!.children("measure")
        assertEquals(3, measuresOut.size)
        assertEquals("yes", measuresOut[0].getAttribute("implicit"))
        assertEquals("", measuresOut[1].getAttribute("implicit"))

        val divisions = MusicXmlExporter.divisionsFor(score, MusicXmlExporter.Options())
        for ((index, measure) in measuresOut.withIndex()) {
            val expected = (measures[index].beats * divisions).roundToInt()
            val staff1 = measure.children("note")
                .filter { it.child("chord") == null }
                .filter { it.text("staff") == "1" }
                .sumOf { it.text("duration")!!.toInt() }
            assertEquals("measure ${index + 1} staff 1", expected, staff1)
        }
    }
}
