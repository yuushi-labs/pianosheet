package com.yuushilabs.pianosheet.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Kotlin decoder against the Python reference.
 *
 * `app/src/test/resources/pti/pv9_10s.frames.bin` holds one 10 s window of real
 * model output (PianoVAM excerpt #9, seconds 10–20), exported by
 * `work/pianovam_models/export_pti_fixture.py`.  `pv9_10s.json` holds the notes
 * the Python `RegressionPostProcessor` produced from exactly those frames, both
 * without and with the decay trim that this port adds.
 *
 * So this test is the fidelity proof that ONNX Runtime cannot give on the JVM:
 * same input tensors, same note list.
 */
class PtiPostProcessorTest {

    private class Fixture(
        val frames: PtiFrames,
        val reference: List<Triple<Int, Double, Double>>,
        val trimmed: List<Triple<Int, Double, Double>>,
        val trimmedCount: Int,
        val meta: String,
    )

    private fun load(): Fixture {
        val bin = javaClass.getResourceAsStream("/pti/pv9_10s.frames.bin")
            ?: error("fixture frames missing")
        val bytes = bin.use { it.readBytes() }
        val json = javaClass.getResourceAsStream("/pti/pv9_10s.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("fixture json missing")
        val frameCount = intField(json, "frameCount")
        val classes = 88
        val headBytes = frameCount * classes * 4
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun readHead(offset: Int): FloatArray {
            val values = FloatArray(frameCount * classes)
            // duplicate() resets the byte order to BIG_ENDIAN, so set it again.
            val slice = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            slice.position(offset)
            slice.asFloatBuffer().get(values)
            return values
        }
        val frames = PtiFrames(
            frameCount = frameCount,
            onsetReg = readHead(0),
            offsetReg = readHead(headBytes),
            frame = readHead(headBytes * 2),
            velocity = readHead(headBytes * 3),
        )
        return Fixture(
            frames = frames,
            reference = parseNotes(json, "notesReference"),
            trimmed = parseNotes(json, "notesTrimmed"),
            trimmedCount = intField(json, "trimmedCount"),
            meta = json,
        )
    }

    /** Minimal parse of the `[{pitch, onset, end}, ...]` arrays in the fixture. */
    private fun parseNotes(json: String, key: String): List<Triple<Int, Double, Double>> {
        val start = json.indexOf("\"$key\"")
        require(start >= 0) { "$key missing" }
        val open = json.indexOf('[', start)
        val close = json.indexOf(']', open)
        val body = json.substring(open + 1, close)
        val notes = ArrayList<Triple<Int, Double, Double>>()
        for (chunk in body.split("},").map { if (it.endsWith("}")) it else "$it}" }) {
            if (!chunk.contains("pitch")) continue
            val pitch = field(chunk, "pitch").toInt()
            val onset = field(chunk, "onset").toDouble()
            val end = field(chunk, "end").toDouble()
            notes += Triple(pitch, onset, end)
        }
        return notes
    }

    private fun field(chunk: String, name: String): String {
        val i = chunk.indexOf("\"$name\"")
        require(i >= 0) { "$name missing in $chunk" }
        val colon = chunk.indexOf(':', i)
        var end = colon + 1
        while (end < chunk.length && (chunk[end] == ' ' || chunk[end] == '\t')) end++
        var j = end
        while (j < chunk.length && chunk[j] !in ",}\n") j++
        return chunk.substring(end, j).trim()
    }

    private fun intField(json: String, name: String): Int {
        val i = json.indexOf("\"$name\"")
        require(i >= 0) { "$name missing" }
        val colon = json.indexOf(':', i)
        var j = colon + 1
        while (json[j] == ' ') j++
        var k = j
        while (k < json.length && json[k].isDigit()) k++
        return json.substring(j, k).toInt()
    }

    @Test
    fun `decodes the same notes as the Python reference`() {
        val fixture = load()
        val decoder = PtiPostProcessor(PtiPostProcessor.Settings(decayTrim = false))
        val notes = decoder.decode(fixture.frames)

        assertEquals(
            "note count differs from the Python reference",
            fixture.reference.size,
            notes.size,
        )
        var maxOnset = 0.0
        var maxEnd = 0.0
        for ((index, expected) in fixture.reference.withIndex()) {
            val actual = notes[index]
            assertEquals("pitch at #$index", expected.first, actual.pitchMidi)
            maxOnset = maxOf(maxOnset, kotlin.math.abs(actual.onsetS - expected.second))
            maxEnd = maxOf(maxEnd, kotlin.math.abs(actual.endS - expected.third))
        }
        // 1 ms tolerance: the port keeps the reference's float arithmetic, so the
        // only difference left is float32 vs float64 rounding of the shift.
        assertTrue("onset differs by ${maxOnset * 1000} ms", maxOnset < 0.001)
        assertTrue("note end differs by ${maxEnd * 1000} ms", maxEnd < 0.001)
    }

    @Test
    fun `the decay trim matches the tuning experiment`() {
        val fixture = load()
        val decoder = PtiPostProcessor() // defaults: trim 0.4 / 5 frames / 60 ms
        val notes = decoder.decode(fixture.frames)

        assertEquals(fixture.trimmed.size, notes.size)
        var maxOnset = 0.0
        var maxEnd = 0.0
        for ((index, expected) in fixture.trimmed.withIndex()) {
            val actual = notes[index]
            assertEquals(expected.first, actual.pitchMidi)
            maxOnset = maxOf(maxOnset, kotlin.math.abs(actual.onsetS - expected.second))
            maxEnd = maxOf(maxEnd, kotlin.math.abs(actual.endS - expected.third))
        }
        assertTrue("onset differs by ${maxOnset * 1000} ms", maxOnset < 0.001)
        assertTrue("trimmed end differs by ${maxEnd * 1000} ms", maxEnd < 0.001)
        assertEquals(
            "trimmed note count",
            fixture.trimmedCount,
            notes.count { it.trimmed },
        )
    }

    @Test
    fun `onsets are never moved by the trim and notes keep their duration`() {
        val fixture = load()
        val plain = PtiPostProcessor(PtiPostProcessor.Settings(decayTrim = false)).decode(fixture.frames)
        val trimmed = PtiPostProcessor().decode(fixture.frames)
        assertEquals(plain.size, trimmed.size)
        for ((a, b) in plain.zip(trimmed)) {
            assertEquals(a.pitchMidi, b.pitchMidi)
            assertEquals(a.onsetS, b.onsetS, 1e-9)
            assertTrue("the trim only shortens notes", b.endS <= a.endS + 1e-9)
            // Never shorter than 60 ms unless the model itself ended it sooner.
            val floor = minOf(a.durationS, 0.06)
            assertTrue(
                "trimmed to ${b.durationS}s from ${a.durationS}s",
                b.durationS >= floor - 1e-9,
            )
        }
    }
}
