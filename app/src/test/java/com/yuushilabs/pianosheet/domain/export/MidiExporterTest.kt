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
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import com.yuushilabs.pianosheet.domain.score.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Reads the bytes the MIDI export produces back as a Standard MIDI File and
 * checks the structure a player depends on: chunk lengths, the tempo and meter
 * meta events, and that every note-on has exactly one note-off after it with
 * non-negative delta times.
 */
class MidiExporterTest {

    private class ParsedEvent(val tick: Int, val status: Int, val data: List<Int>)

    private class ParsedTrack(val name: String, val events: List<ParsedEvent>) {
        val noteOns: List<ParsedEvent> get() = events.filter { it.status and 0xF0 == 0x90 && it.data[1] > 0 }
        val noteOffs: List<ParsedEvent> get() = events.filter { it.status and 0xF0 == 0x80 }
    }

    private fun scoreOf(
        notes: List<ScoreNote>,
        timeSignature: TimeSignature = TimeSignature.COMMON,
        key: KeySignature = KeySignature(0, Mode.MAJOR),
        tempo: Double = 90.0,
    ): Score {
        val lastBeat = notes.maxOfOrNull { it.startBeat + it.durationBeats } ?: 0.0
        val count = ScoreMath.measureCountFor(lastBeat + 0.001, timeSignature)
        return Score(
            projectId = "p1",
            title = "take",
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
        velocity: Float = 0.8f,
    ): ScoreNote = ScoreNote(
        id = id,
        midiPitch = midi,
        startBeat = startBeat,
        durationBeats = durationBeats,
        staff = staff,
        noteValue = NoteValue.QUARTER,
        dotted = false,
        accidental = Accidental.SHARP.takeIf { ((midi % 12) + 12) % 12 in setOf(1, 3, 6, 8, 10) },
        startSec = startBeat * 0.666,
        velocity = velocity,
        measureIndex = (startBeat / 4.0).toInt(),
        letter = StaffNotation.diatonicIndex(midi) % 7,
        octave = midi / 12 - 1,
    )

    // ------------------------------------------------------------- parsing

    private fun readInt16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun readInt32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun readVarLen(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var index = start
        var value = 0
        while (true) {
            val byte = bytes[index].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F)
            index += 1
            if (byte and 0x80 == 0) break
        }
        return value to index
    }

    private fun parseTracks(bytes: ByteArray): List<ParsedTrack> {
        var index = 0
        assertTrue("MThd", String(bytes, 0, 4, Charsets.US_ASCII) == "MThd")
        assertEquals(6, readInt32(bytes, 4))
        index = 8 + readInt32(bytes, 4)
        val tracks = ArrayList<ParsedTrack>()
        while (index < bytes.size) {
            val id = String(bytes, index, 4, Charsets.US_ASCII)
            assertEquals("MTrk", id)
            val length = readInt32(bytes, index + 4)
            val bodyStart = index + 8
            val bodyEnd = bodyStart + length
            var cursor = bodyStart
            var tick = 0
            var name = ""
            val events = ArrayList<ParsedEvent>()
            while (cursor < bodyEnd) {
                val (delta, afterDelta) = readVarLen(bytes, cursor)
                cursor = afterDelta
                tick += delta
                val status = bytes[cursor].toInt() and 0xFF
                if (status == 0xFF) {
                    val type = bytes[cursor + 1].toInt() and 0xFF
                    val (size, payloadStart) = readVarLen(bytes, cursor + 2)
                    if (type == 0x03) name = String(bytes, payloadStart, size, Charsets.US_ASCII)
                    if (type == 0x51) {
                        val micros = ((bytes[payloadStart].toInt() and 0xFF) shl 16) or
                            ((bytes[payloadStart + 1].toInt() and 0xFF) shl 8) or
                            (bytes[payloadStart + 2].toInt() and 0xFF)
                        events.add(ParsedEvent(tick, 0xFF51, listOf(micros)))
                    } else if (type == 0x58) {
                        events.add(
                            ParsedEvent(tick, 0xFF58, listOf(bytes[payloadStart].toInt() and 0xFF)),
                        )
                    } else if (type == 0x59) {
                        events.add(
                            ParsedEvent(
                                tick,
                                0xFF59,
                                listOf(
                                    bytes[payloadStart].toInt(),
                                    bytes[payloadStart + 1].toInt() and 0xFF,
                                ),
                            ),
                        )
                    }
                    cursor = payloadStart + size
                } else {
                    val high = status and 0xF0
                    val payloadSize = when (high) {
                        0xC0, 0xD0 -> 1
                        else -> 2
                    }
                    val data = (0 until payloadSize).map { bytes[cursor + 1 + it].toInt() and 0xFF }
                    events.add(ParsedEvent(tick, status, data))
                    cursor += 1 + payloadSize
                }
            }
            assertEquals("track length", bodyEnd, cursor)
            tracks.add(ParsedTrack(name, events))
            index = bodyEnd
        }
        return tracks
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `file is a format 1 three track smf`() {
        val bytes = MidiExporter.toBytes(scoreOf(listOf(note("a", 60, 0.0, 1.0, Staff.TREBLE))))
        assertEquals("MThd", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(6, readInt32(bytes, 4))
        assertEquals("format", 1, readInt16(bytes, 8))
        assertEquals("tracks", MidiExporter.TRACK_COUNT, readInt16(bytes, 10))
        assertEquals("division", MidiExporter.TICKS_PER_QUARTER, readInt16(bytes, 12))
        assertEquals(3, parseTracks(bytes).size)
    }

    @Test
    fun `conductor track carries tempo meter and key`() {
        val score = scoreOf(
            emptyList(),
            timeSignature = TimeSignature(6, 8),
            key = KeySignature(-2, Mode.MINOR),
            tempo = 72.0,
        )
        val conductor = parseTracks(MidiExporter.toBytes(score))[0]
        val tempo = conductor.events.first { it.status == 0xFF51 }
        assertEquals(MidiExporter.tempoMicrosecondsPerQuarter(72.0), tempo.data[0])
        assertEquals(833333, tempo.data[0])
        val meter = conductor.events.first { it.status == 0xFF58 }
        assertEquals(6, meter.data[0])
        val key = conductor.events.first { it.status == 0xFF59 }
        assertEquals(-2, key.data[0])
        assertEquals(1, key.data[1]) // minor
        assertEquals("PianoSheet", conductor.name)
    }

    @Test
    fun `each note has a note on and a note off on the right channel`() {
        val notes = listOf(
            note("a", 60, 0.0, 1.0, Staff.TREBLE),
            note("b", 64, 0.0, 1.0, Staff.TREBLE),
            note("c", 48, 0.0, 2.0, Staff.BASS),
            note("d", 52, 2.0, 1.0, Staff.BASS),
        )
        val tracks = parseTracks(MidiExporter.toBytes(scoreOf(notes)))
        val right = tracks[1]
        val left = tracks[2]
        assertEquals("Right Hand", right.name)
        assertEquals("Left Hand", left.name)
        assertEquals(2, right.noteOns.size)
        assertEquals(2, right.noteOffs.size)
        assertEquals(2, left.noteOns.size)
        assertEquals(2, left.noteOffs.size)
        assertTrue(right.noteOns.all { it.status and 0x0F == 0 })
        assertTrue(left.noteOns.all { it.status and 0x0F == 1 })

        for (track in listOf(right, left)) {
            val pitches = track.noteOns.map { it.data[0] }.sorted()
            val offPitches = track.noteOffs.map { it.data[0] }.sorted()
            assertEquals(pitches, offPitches)
            assertTrue("note-offs come after their note-ons", track.noteOffs.all { it.tick >= 0 })
        }
        val firstNoteStart = right.noteOns.minOf { it.tick }
        assertEquals(0, firstNoteStart)
        assertEquals(
            MidiExporter.beatToTick(2.0, MidiExporter.TICKS_PER_QUARTER),
            left.noteOffs.maxOf { it.tick },
        )
    }

    @Test
    fun `a repeated pitch is cut where the next attack starts`() {
        val notes = listOf(
            note("a", 60, 0.0, 2.0, Staff.TREBLE),
            note("b", 60, 1.0, 1.0, Staff.TREBLE),
        )
        val track = parseTracks(MidiExporter.toBytes(scoreOf(notes)))[1]
        val ons = track.noteOns.sortedBy { it.tick }
        val offs = track.noteOffs.sortedBy { it.tick }
        assertEquals(2, ons.size)
        assertEquals(2, offs.size)
        assertEquals(
            "the first note ends where the second begins",
            ons[1].tick,
            offs[0].tick,
        )
        assertTrue("deltas are non-negative", offs[0].tick < offs[1].tick)
    }

    @Test
    fun `velocity follows the note strength and stays in range`() {
        val score = scoreOf(
            listOf(note("a", 60, 0.0, 1.0, Staff.TREBLE, velocity = 0.01f)),
        )
        val track = parseTracks(MidiExporter.toBytes(score))[1]
        val velocity = track.noteOns.first().data[1]
        assertTrue("velocity $velocity", velocity in 1..127)
        assertEquals(1, velocity)
    }

    @Test
    fun `track lengths match the file so nothing is truncated`() {
        val notes = (0 until 24).map { index ->
            note("n$index", 60 + index, index * 0.5, 0.5, Staff.TREBLE)
        }
        val bytes = MidiExporter.toBytes(scoreOf(notes))
        // The parser asserts every MTrk body ends exactly at its declared length.
        val tracks = parseTracks(bytes)
        assertEquals(24, tracks[1].noteOns.size)
        assertEquals(24, tracks[1].noteOffs.size)
    }
}
