package com.yuushilabs.pianosheet.domain.export

import com.yuushilabs.pianosheet.core.inference.NoteCandidateFilter
import com.yuushilabs.pianosheet.domain.final.FinalNote
import com.yuushilabs.pianosheet.domain.final.FinalNoteMerger
import com.yuushilabs.pianosheet.domain.final.ScoreBuilder
import com.yuushilabs.pianosheet.domain.live.Detection
import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.score.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * The export has to survive the real takes, not only hand-written scores: a
 * transcription has overlapping notes, repeated pitches, a wandering key and
 * hundreds of measures.
 *
 * The MusicXML of the user's own take is also printed between markers so the
 * same bytes can be opened by an outside reader (music21 on the build machine)
 * during review, see `docs/export.md`.
 */
class ExportRealTakeTest {

    private val EXPORT_OPTIONS = MusicXmlExporter.Options(encodingDate = "2026-11-17")

    private fun analyse(name: String): Score {
        val stream = javaClass.classLoader!!.getResourceAsStream("real-takes/$name.csv")
            ?: error("missing fixture real-takes/$name.csv")
        val lines = stream.bufferedReader().readLines().drop(1).filter { it.isNotBlank() }
        val byWindow = LinkedHashMap<Double, MutableList<Detection>>()
        for (line in lines) {
            val parts = line.split(",")
            byWindow.getOrPut(parts[0].toDouble()) { ArrayList() }.add(
                Detection(
                    pitchMidi = parts[3].toInt(),
                    onsetS = parts[1].toDouble(),
                    endS = parts[2].toDouble(),
                    confidence = parts[4].toFloat(),
                    onsetStrength = parts[5].toFloat(),
                ),
            )
        }
        val windows = byWindow.map { (start, detections) ->
            FinalNoteMerger.WindowResult(
                windowStartS = start,
                windowEndS = start + 2.0,
                detections = NoteCandidateFilter.filter(detections).kept,
            )
        }
        val merged = FinalNoteMerger.mergeDetailed(windows)
        return ScoreBuilder.build(
            projectId = name,
            title = name,
            notes = merged.notes.map {
                FinalNote(
                    pitch = it.pitchMidi,
                    onsetSec = it.onsetMs / 1000.0,
                    durationSec = (it.durationMs ?: 0.0) / 1000.0,
                    amplitude = it.confidence.coerceIn(0.05f, 1f),
                )
            },
            durationSec = windows.maxOf { it.windowEndS },
        )
    }

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val builder = factory.newDocumentBuilder()
        return builder.parse(xml.byteInputStream(Charsets.UTF_8)).documentElement
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

    /**
     * Every measure must hold exactly its own length in both staves: the meter,
     * except for a pickup (anacrusis) measure, which holds the beats before the
     * first downbeat and is marked `implicit="yes"`.
     */
    private fun assertMeasureArithmetic(score: Score, root: Element) {
        val divisions = MusicXmlExporter.divisionsFor(score, EXPORT_OPTIONS)
        val measures = root.child("part")!!.children("measure")
        assertEquals(score.measures.size, measures.size)
        for ((index, measure) in measures.withIndex()) {
            val measureTicks = (score.measures[index].beats * divisions).roundToInt()
            if (index == 0 && score.measures.size > 1 &&
                score.measures[0].beats < score.timeSignature.beatsPerMeasure - 1e-6
            ) {
                assertEquals("pickup measure must be implicit", "yes", measure.getAttribute("implicit"))
            }
            val perStaff = HashMap<Int, Int>()
            for (note in measure.children("note")) {
                if (note.child("chord") != null) continue
                val staff = note.text("staff")!!.toInt()
                perStaff[staff] = (perStaff[staff] ?: 0) + note.text("duration")!!.toInt()
            }
            assertEquals("measure ${index + 1} staff 1", measureTicks, perStaff[1] ?: 0)
            assertEquals("measure ${index + 1} staff 2", measureTicks, perStaff[2] ?: 0)
        }
    }

    @Test
    fun `every real take exports to legal musicxml`() {
        for (name in listOf("fur-elise", "gymnopedie", "user-take")) {
            val score = analyse(name)
            val xml = MusicXmlExporter.toMusicXml(score, EXPORT_OPTIONS)
            val root = parse(xml)
            assertEquals("score-partwise", root.tagName)
            assertMeasureArithmetic(score, root)
            val notes = root.getElementsByTagName("note").length
            val chords = root.getElementsByTagName("chord").length
            println("$name -> ${score.notes.size} notes, ${score.measures.size} measures, $notes MusicXML notes, $chords chords")
            // Tie splits and chord members can only add noteheads, never lose one.
            val noteheads = notes - root.getElementsByTagName("rest").length
            assertTrue("$name: $noteheads noteheads for ${score.notes.size} notes", noteheads >= score.notes.size)
        }
    }

    @Test
    fun `every real take exports to playable midi`() {
        for (name in listOf("fur-elise", "gymnopedie", "user-take")) {
            val score = analyse(name)
            val bytes = MidiExporter.toBytes(score)
            assertEquals("MThd", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(3, (bytes[11].toInt() and 0xFF) or ((bytes[10].toInt() and 0xFF) shl 8))
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            println("$name midi -> ${bytes.size} bytes, sha256=${digest.joinToString("") { "%02x".format(it) }}")
            assertTrue("$name midi size", bytes.size > 200)
        }
    }

    @Test
    fun `the users take prints its musicxml for outside readers`() {
        val score = analyse("user-take")
        val xml = MusicXmlExporter.toMusicXml(score, EXPORT_OPTIONS)
        assertMeasureArithmetic(score, parse(xml))
        println("MUSICXML-BEGIN user-take")
        print(xml)
        println("MUSICXML-END user-take")
        assertTrue(xml.contains("<staves>2</staves>"))
        assertTrue(score.notes.any { it.staff == Staff.TREBLE })
    }
}
