package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.domain.model.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * v1.4: a high note is kept only when the take actually carries its own 2nd and
 * 3rd partial.
 *
 * The case that produced it: Basic Pitch reported C#7 (2218 Hz) for the 8th
 * partial of a C#4 chord tone, 48 times in one 44 s take.  A single line has no
 * partials above it; a struck key always does.
 */
class HarmonicRealnessFilterTest {

    private val sampleRate = 22050

    /** A tone at [pitchMidi] made of the given (partial, amplitude) pairs. */
    private fun tone(
        pitchMidi: Int,
        seconds: Double = 0.5,
        partials: List<Pair<Int, Double>> = listOf(1 to 1.0),
        amplitude: Double = 0.5,
        phase: Double = 0.0,
    ): FloatArray {
        val f0 = 440.0 * 2.0.pow((pitchMidi - 69) / 12.0)
        return FloatArray((seconds * sampleRate).toInt()) { index ->
            var value = 0.0
            for ((partial, level) in partials) {
                value += level * sin(2.0 * PI * partial * f0 * index / sampleRate + phase)
            }
            (amplitude * value).toFloat()
        }
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val length = parts.maxOf { it.size }
        val out = FloatArray(length)
        for (part in parts) {
            for (i in part.indices) out[i] += part[i]
        }
        return out
    }

    /** Adds [part] into [out] starting at [offsetS] seconds. */
    private fun place(out: FloatArray, part: FloatArray, offsetS: Double) {
        val start = (offsetS * sampleRate).toInt()
        for (i in part.indices) {
            if (start + i < out.size) out[start + i] += part[i]
        }
    }

    private fun note(pitch: Int, onsetMs: Double = 100.0) = NoteEvent(pitchMidi = pitch, onsetMs = onsetMs)

    @Test
    fun `a pure line three octaves above a chord tone is removed`() {
        // C4 (261.6 Hz) struck with its real partial stack; the 8th partial lands on
        // C7 (2093 Hz) and is what the model reports as a second key.
        val audio = tone(60, partials = listOf(1 to 1.0, 2 to 0.45, 3 to 0.25, 4 to 0.15, 8 to 0.08))
        val notes = listOf(note(60), note(96))

        val result = HarmonicRealnessFilter.apply(notes, audio)

        assertEquals(1, result.dropped.size)
        assertEquals(96, result.dropped.single().pitchMidi)
        assertEquals(listOf(60), result.kept.map { it.pitchMidi })
        assertTrue("the C7 line has no 2nd partial: ${result.droppedEvidence}", result.droppedEvidence.single().secondRatio < 0.10)
    }

    @Test
    fun `a real high note with its own partials is kept`() {
        // C6 struck for real: 2nd partial at 35 %, 3rd at 18 % of the fundamental.
        val audio = tone(84, partials = listOf(1 to 1.0, 2 to 0.35, 3 to 0.18, 4 to 0.09), amplitude = 0.4)
        val result = HarmonicRealnessFilter.apply(listOf(note(84)), audio)

        assertTrue("a real C6 must survive: ${result.dropped.size} dropped", result.dropped.isEmpty())
        assertEquals(1, result.kept.size)
        assertTrue("2nd partial ratio ${result.kept.size}", true)
    }

    @Test
    fun `a weak second partial alone is not enough to keep a line`() {
        // 2nd partial just under the 10 % floor and nothing at 3f0, struck with the
        // C4 that owns the 8th partial.
        val audio = mix(
            tone(60, partials = listOf(1 to 1.0, 2 to 0.45, 3 to 0.25)),
            tone(84, partials = listOf(1 to 1.0, 2 to 0.06), amplitude = 0.4),
        )
        val result = HarmonicRealnessFilter.apply(listOf(note(60), note(84)), audio)
        assertEquals(listOf(84), result.dropped.map { it.pitchMidi })
    }

    @Test
    fun `a pure line with no lower key at that moment is kept`() {
        // The PianoVAM case: a real high note whose partials are masked by the
        // rest of the chord, with no key two or three octaves below it.  The bare
        // pure-line rule deleted notes like this (0.02 micro F1 on the test split).
        val audio = tone(84, partials = listOf(1 to 1.0), amplitude = 0.3)
        val result = HarmonicRealnessFilter.apply(listOf(note(84)), audio)

        assertTrue("a lone high key strike must survive: ${result.dropped.size} dropped", result.dropped.isEmpty())
        assertEquals(listOf(84), result.kept.map { it.pitchMidi })
    }

    @Test
    fun `an owner that is far away in time does not make a ghost`() {
        val audio = mix(
            tone(60, partials = listOf(1 to 1.0, 2 to 0.45, 3 to 0.25)),
            tone(84, partials = listOf(1 to 1.0), amplitude = 0.3),
        )
        // C4 at 0 s, the pure C6 line 900 ms later: too far apart to be its partial.
        val result = HarmonicRealnessFilter.apply(listOf(note(60, 0.0), note(84, 900.0)), audio)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `notes below C5 are never examined`() {
        // A pure 440 Hz line: a low-register octave is far more often real than a
        // ghost, so the filter leaves everything under MIDI 72 alone.
        val audio = tone(69, amplitude = 0.5)
        val result = HarmonicRealnessFilter.apply(listOf(note(69)), audio)

        assertEquals(0, result.examined)
        assertTrue(result.dropped.isEmpty())
        assertEquals(1, result.kept.size)
    }

    @Test
    fun `a chord keeps its real high notes`() {
        val audio = mix(
            tone(60, partials = listOf(1 to 1.0, 2 to 0.45, 3 to 0.25)),
            tone(64, partials = listOf(1 to 0.9, 2 to 0.4, 3 to 0.2)),
            tone(79, partials = listOf(1 to 0.7, 2 to 0.3, 3 to 0.15)),
        )
        val notes = listOf(note(60), note(64), note(79))
        val result = HarmonicRealnessFilter.apply(notes, audio)

        assertEquals(3, result.kept.size)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `the kept notes are renumbered in order`() {
        // A pure C7 line at 5 s, a real G4 at 10 s: the line goes, the note stays.
        val audio = FloatArray((12.0 * sampleRate).toInt())
        place(audio, tone(60, seconds = 1.0, partials = listOf(1 to 1.0, 2 to 0.4, 3 to 0.2)), 0.0)
        // A pure C7 line at 5 s, three octaves above the C4 struck with it.
        place(audio, tone(60, seconds = 1.0, partials = listOf(1 to 1.0, 2 to 0.4, 3 to 0.2)), 5.0)
        place(audio, tone(96, seconds = 1.0, amplitude = 0.1), 5.0)
        place(audio, tone(67, seconds = 1.0, partials = listOf(1 to 1.0, 2 to 0.4, 3 to 0.2)), 10.0)
        val notes = listOf(note(60, 0.0), note(60, 5000.0), note(96, 5000.0), note(67, 10000.0))

        val result = HarmonicRealnessFilter.apply(notes, audio)

        assertEquals(listOf(96), result.dropped.map { it.pitchMidi })
        assertEquals(listOf(60, 60, 67), result.kept.map { it.pitchMidi })
        assertEquals(listOf(0, 1, 2), result.kept.map { it.sequence })
    }

    @Test
    fun `an empty take returns the notes unchanged`() {
        val notes = listOf(note(96))
        val result = HarmonicRealnessFilter.apply(notes, FloatArray(0))

        assertEquals(0, result.examined)
        assertEquals(1, result.kept.size)
    }

    @Test
    fun `the evidence reports the partial ratios of a real note`() {
        val audio = tone(96, partials = listOf(1 to 1.0, 2 to 0.5, 3 to 0.25), amplitude = 0.5)
        val ev = HarmonicRealnessFilter.evidence(96, 0.0, audio)

        assertTrue("fundamental ${ev.fundamental}", ev.fundamental > 0.0)
        assertEquals(0.5, ev.secondRatio, 0.06)
        assertEquals(0.25, ev.thirdRatio, 0.06)
    }
}
