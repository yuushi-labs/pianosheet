package com.yuushilabs.pianosheet.domain.export

import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.score.Mode
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes a finished [Score] as a Standard MIDI File, format 1:
 *
 *  - track 0: tempo, time signature, key signature (the conductor track),
 *  - track 1: right hand on channel 1,
 *  - track 2: left hand on channel 2.
 *
 * The two note tracks share one beat grid from the score, so the file opens with
 * the same tempo and meter as the notation, and a DAW or any MIDI player can
 * follow it.  A repeated pitch is cut where the next attack of that pitch begins,
 * so a note-off never silences the note that follows it.
 */
object MidiExporter {

    const val MIME_TYPE = "audio/midi"
    const val FILE_EXTENSION = "mid"
    const val TICKS_PER_QUARTER = 480
    const val TRACK_COUNT = 3

    private const val META_END_OF_TRACK = 0x2F
    private const val META_TEMPO = 0x51
    private const val META_TIME_SIGNATURE = 0x58
    private const val META_KEY_SIGNATURE = 0x59
    private const val META_TRACK_NAME = 0x03

    fun toBytes(score: Score, ticksPerQuarter: Int = TICKS_PER_QUARTER): ByteArray {
        val ppq = max(24, ticksPerQuarter)
        val out = ByteArrayOutputStream(4096)
        writeHeader(out, ppq)
        writeConductorTrack(out, score)
        writeNoteTrack(out, score, Staff.TREBLE, channel = 0, name = "Right Hand", ppq = ppq)
        writeNoteTrack(out, score, Staff.BASS, channel = 1, name = "Left Hand", ppq = ppq)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- chunks

    private fun writeHeader(out: ByteArrayOutputStream, ppq: Int) {
        out.writeAscii("MThd")
        writeInt32(out, 6)
        writeInt16(out, 1) // format 1: several tracks, one tempo track
        writeInt16(out, TRACK_COUNT)
        writeInt16(out, ppq)
    }

    private fun writeChunk(out: ByteArrayOutputStream, id: String, body: ByteArray) {
        out.writeAscii(id)
        writeInt32(out, body.size)
        out.write(body)
    }

    private fun writeConductorTrack(out: ByteArrayOutputStream, score: Score) {
        val body = ByteArrayOutputStream(64)
        writeMeta(body, 0, META_TRACK_NAME, "PianoSheet".toByteArray(Charsets.US_ASCII))

        val signature = score.timeSignature
        val denominatorPower = when (signature.denominator) {
            1 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            16 -> 4
            else -> 2
        }
        writeMeta(
            body,
            0,
            META_TIME_SIGNATURE,
            byteArrayOf(
                signature.numerator.toByte(),
                denominatorPower.toByte(),
                24, // MIDI clocks per metronome click
                8, // notated 32nd notes per quarter
            ),
        )
        writeMeta(
            body,
            0,
            META_KEY_SIGNATURE,
            byteArrayOf(
                score.keySignature.fifths.toByte(),
                if (score.keySignature.mode == Mode.MAJOR) 0 else 1,
            ),
        )
        val microseconds = (60_000_000.0 / score.tempoBpm.coerceIn(20.0, 400.0)).roundToInt()
        writeMeta(
            body,
            0,
            META_TEMPO,
            byteArrayOf(
                ((microseconds shr 16) and 0xFF).toByte(),
                ((microseconds shr 8) and 0xFF).toByte(),
                (microseconds and 0xFF).toByte(),
            ),
        )
        writeMeta(body, 0, META_END_OF_TRACK, ByteArray(0))
        writeChunk(out, "MTrk", body.toByteArray())
    }

    private fun writeNoteTrack(
        out: ByteArrayOutputStream,
        score: Score,
        staff: Staff,
        channel: Int,
        name: String,
        ppq: Int,
    ) {
        val events = ArrayList<Event>()
        for (note in notesOf(score, staff)) {
            val start = beatToTick(note.startBeat, ppq)
            var end = beatToTick(note.startBeat + note.durationBeats, ppq)
            if (end <= start) end = start + 1
            val velocity = (note.velocity.coerceIn(0.02f, 1f) * 127f).roundToInt().coerceIn(1, 127)
            events.add(Event(start, PRIORITY_NOTE_ON, noteOn(channel, note.midiPitch, velocity)))
            events.add(Event(end, PRIORITY_NOTE_OFF, noteOff(channel, note.midiPitch)))
        }
        truncateRepeatedPitches(events, channel)

        val body = ByteArrayOutputStream(4096)
        writeMeta(body, 0, META_TRACK_NAME, name.toByteArray(Charsets.US_ASCII))
        // Program 0 = Acoustic Grand Piano on both channels.
        writeEvent(body, 0, byteArrayOf((0xC0 or channel).toByte(), 0))

        events.sortWith(compareBy({ it.tick }, { it.priority }))
        var previousTick = 0
        for (event in events) {
            val delta = max(0, event.tick - previousTick)
            writeEvent(body, delta, event.bytes)
            previousTick = event.tick
        }
        writeMeta(body, 0, META_END_OF_TRACK, ByteArray(0))
        writeChunk(out, "MTrk", body.toByteArray())
    }

    /**
     * A note-off that comes after the next attack of the same pitch would cut it
     * short, so the earlier note stops exactly where the later one starts.
     */
    private fun truncateRepeatedPitches(events: MutableList<Event>, channel: Int) {
        val byPitch = events.groupBy { it.bytes[1].toInt() and 0x7F }
        for ((_, group) in byPitch) {
            val ons = group.filter { it.priority == PRIORITY_NOTE_ON }.sortedBy { it.tick }
            val offs = group.filter { it.priority == PRIORITY_NOTE_OFF }.sortedBy { it.tick }
            var offIndex = 0
            for (on in ons) {
                while (offIndex < offs.size && offs[offIndex].tick <= on.tick) offIndex += 1
                if (offIndex < offs.size) {
                    val off = offs[offIndex]
                    if (off.tick > on.tick) off.truncatedTick = on.tick
                }
            }
        }
        for (event in events) {
            if (event.truncatedTick >= 0) event.tick = event.truncatedTick
        }
    }

    // ---------------------------------------------------------------- events

    private const val PRIORITY_NOTE_OFF = 0
    private const val PRIORITY_NOTE_ON = 1

    private class Event(val tick: Int, val priority: Int, val bytes: ByteArray) {
        /** Set when a later attack of the same pitch shortens this note-off. */
        var truncatedTick: Int = -1
    }

    private fun noteOn(channel: Int, pitch: Int, velocity: Int): ByteArray =
        byteArrayOf((0x90 or channel).toByte(), pitch.toByte(), velocity.toByte())

    private fun noteOff(channel: Int, pitch: Int): ByteArray =
        byteArrayOf((0x80 or channel).toByte(), pitch.toByte(), 0x40)

    private fun notesOf(score: Score, staff: Staff): List<ScoreNote> =
        score.notes.filter { it.staff == staff }.sortedWith(compareBy({ it.startBeat }, { it.midiPitch }))

    // ------------------------------------------------------------- primitives

    private fun writeMeta(out: ByteArrayOutputStream, delta: Int, type: Int, payload: ByteArray) {
        writeVarLen(out, delta)
        out.write(0xFF)
        out.write(type)
        writeVarLen(out, payload.size)
        out.write(payload)
    }

    private fun writeEvent(out: ByteArrayOutputStream, delta: Int, bytes: ByteArray) {
        writeVarLen(out, delta)
        out.write(bytes)
    }

    private fun writeVarLen(out: ByteArrayOutputStream, delta: Int) {
        var value = max(0, delta) and 0x0FFFFFFF
        val bytes = ArrayList<Int>(4)
        bytes.add(value and 0x7F)
        value = value ushr 7
        while (value > 0) {
            bytes.add((value and 0x7F) or 0x80)
            value = value ushr 7
        }
        for (index in bytes.indices.reversed()) out.write(bytes[index])
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }

    fun beatToTick(beat: Double, ticksPerQuarter: Int): Int = (beat * ticksPerQuarter).roundToInt()

    /** Exposed for the tests: the tempo the conductor track carries. */
    fun tempoMicrosecondsPerQuarter(bpm: Double): Int =
        (60_000_000.0 / bpm.coerceIn(20.0, 400.0)).roundToInt()
}
