package com.yuushilabs.pianosheet.core.inference

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.4.8 pedal behaviour, on frames built by hand: a note that never stops
 * must not be split into two, a real re-strike must stay two, and the decay trim
 * must not cut a note the recording still has.
 *
 * The model frames are made with one key column: an onset peak where a key is
 * struck, a frame-output (key activity) trace, and an optional release peak in
 * the offset head.  The audio is a sine at the note's own pitch, which is what
 * the decoder measures to tell "still ringing" from "stopped".
 */
class PtiSustainTest {

    private val sampleRate = PtiFrames.BEGIN_NOTE // placeholder, see SEC
    private val pitch = 69 // A4, 440 Hz

    // ------------------------------------------------------------------ frames
    private class Head(val name: String)

    private fun frames(count: Int, build: PtiFramesBuilder.() -> Unit): PtiFrames {
        val builder = PtiFramesBuilder(count)
        builder.build()
        return PtiFrames(
            frameCount = count,
            onsetReg = builder.onset,
            offsetReg = builder.offset,
            frame = builder.activity,
            velocity = builder.velocity,
        )
    }

    private class PtiFramesBuilder(val count: Int) {
        val onset = FloatArray(count * PtiFrames.CLASSES)
        val offset = FloatArray(count * PtiFrames.CLASSES)
        val activity = FloatArray(count * PtiFrames.CLASSES)
        val velocity = FloatArray(count * PtiFrames.CLASSES)

        private fun index(frame: Int, pitch: Int) = frame * PtiFrames.CLASSES + (pitch - PtiFrames.BEGIN_NOTE)

        /** A regression peak the binarizer accepts: monotonic over ±2 frames. */
        fun onsetPeak(frame: Int, pitch: Int, value: Float) = peak(onset, frame, pitch, value)

        fun offsetPeak(frame: Int, pitch: Int, value: Float) = peak(offset, frame, pitch, value)

        private fun peak(head: FloatArray, frame: Int, pitch: Int, value: Float) {
            head[index(frame, pitch)] = value
            for (d in 1..2) {
                val v = value * (1f - 0.3f * d)
                if (frame - d >= 0) head[index(frame - d, pitch)] = v
                if (frame + d < count) head[index(frame + d, pitch)] = v
            }
        }

        fun activity(from: Int, to: Int, pitch: Int, value: Float) {
            for (frame in from..to) this.activity[index(frame, pitch)] = value
        }

        fun activity(frame: Int, pitch: Int, value: Float) {
            this.activity[index(frame, pitch)] = value
        }

        fun velocity(frame: Int, pitch: Int, value: Float) {
            this.velocity[index(frame, pitch)] = value
        }
    }

    // ------------------------------------------------------------------- audio
    /** [pitch]'s tone at [sampleRate] between [fromSec] and [toSec]. */
    private fun tone(
        seconds: Double,
        pitchMidi: Int = 69,
        fromSec: Double = 0.0,
        toSec: Double = seconds,
        amplitude: Double = 0.4,
        sampleRate: Int = 16_000,
    ): FloatArray {
        val samples = (seconds * sampleRate).toInt()
        val out = FloatArray(samples)
        val f = 440.0 * Math.pow(2.0, (pitchMidi - 69) / 12.0)
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            if (t in fromSec..toSec) {
                // A little of the 2nd/3rd partial, like a real string.
                out[i] = (amplitude * (sin(2 * PI * f * t) + 0.4 * sin(2.0 * 2 * PI * f * t) +
                    0.2 * sin(3.0 * 2 * PI * f * t))).toFloat()
            }
        }
        return out
    }

    // ------------------------------------------------------------------- tests
    @Test
    fun `a sustained note with a phantom re-onset stays one note`() {
        // Key held from frame 10; onset peaks at 10 and again at 60 (the model
        // firing inside the sustain); the key activity only drops at frame 150.
        val frames = frames(200) {
            onsetPeak(10, pitch, 0.9f)
            onsetPeak(60, pitch, 0.55f)
            activity(10, 150, pitch, 0.9f)
            activity(151, 199, pitch, 0.02f)
            velocity(10, pitch, 0.7f)
            velocity(60, pitch, 0.5f)
        }
        // The string rings on at full level across the boundary: no re-attack.
        val audio = tone(2.0)

        val reference = PtiPostProcessor().decode(frames)
        assertEquals("without audio the reference split is reproduced", 2, reference.size)
        assertEquals(0.49, reference[0].durationS, 0.02)

        val notes = PtiPostProcessor().decode(frames, audio)
        assertEquals("a note that never stopped is one note", 1, notes.size)
        assertEquals(pitch, notes[0].pitchMidi)
        assertEquals(0.09, notes[0].onsetS, 0.02)
        assertEquals(1.4, notes[0].durationS, 0.05)
        assertEquals(0.7f, notes[0].velocity, 1e-6f)
    }

    @Test
    fun `a real re-strike is kept as two notes`() {
        val frames = frames(200) {
            onsetPeak(10, pitch, 0.9f)
            onsetPeak(60, pitch, 0.95f)
            offsetPeak(55, pitch, 0.9f)
            activity(10, 150, pitch, 0.9f)
            activity(151, 199, pitch, 0.02f)
            velocity(10, pitch, 0.7f)
            velocity(60, pitch, 0.8f)
        }
        // The note has decayed and is struck again: the partials jump.
        val audio = tone(2.0, fromSec = 0.0, toSec = 0.55, amplitude = 0.05)
        val second = tone(2.0, fromSec = 0.6, toSec = 2.0, amplitude = 0.4)
        for (i in audio.indices) audio[i] += second[i]

        val notes = PtiPostProcessor().decode(frames, audio)
        assertEquals(2, notes.size)
        assertEquals(0.09, notes[0].onsetS, 0.02)
        assertEquals(0.59, notes[1].onsetS, 0.02)
    }

    @Test
    fun `the trim keeps a pedal sustain the recording still has`() {
        // Activity drops to 20 % of its peak at frame 20 (the trim's candidate)
        // but the key never comes fully up: the note ends at frame 60.
        val frames = frames(200) {
            onsetPeak(10, pitch, 0.9f)
            activity(10, 19, pitch, 0.9f)
            activity(20, 59, pitch, 0.2f)
            activity(60, 199, pitch, 0.02f)
            velocity(10, pitch, 0.7f)
        }
        val ringing = tone(2.0, toSec = 2.0)
        val trimmed = PtiPostProcessor().decode(frames, ringing)
        assertEquals(1, trimmed.size)
        assertEquals("the sustain is kept", 0.5, trimmed[0].durationS, 0.05)
        assertTrue("not marked as trimmed", !trimmed[0].trimmed)

        // The same note on a recording that really stopped at the drop.
        val stopped = tone(2.0, toSec = 0.24)
        val cut = PtiPostProcessor().decode(frames, stopped)
        assertEquals(1, cut.size)
        assertEquals("the trim still shortens a note that stopped", 0.11, cut[0].durationS, 0.03)
        assertTrue(cut[0].trimmed)
    }

    @Test
    fun `the reference decode is untouched without audio`() {
        val frames = frames(200) {
            onsetPeak(10, pitch, 0.9f)
            activity(10, 19, pitch, 0.9f)
            activity(20, 59, pitch, 0.2f)
            activity(60, 199, pitch, 0.02f)
            velocity(10, pitch, 0.7f)
        }
        val reference = PtiPostProcessor(
            PtiPostProcessor.Settings(decayTrim = false, sustainHoldSec = 0.0),
        ).decode(frames)
        assertEquals(1, reference.size)
        assertEquals(0.5, reference[0].durationS, 0.01)
        assertTrue(!reference[0].trimmed)
    }

    @Test
    fun `note energy finds the attack and the ring`() {
        val audio = tone(2.0, fromSec = 0.5, toSec = 1.5)
        val energy = NoteEnergy(audio)
        val silent = energy.partialDb(pitch, 0.2)
        val loud = energy.partialDb(pitch, 0.8)
        assertTrue("silence is far below the tone", loud - silent > 40.0)
        assertTrue("a strike rises", energy.riseDb(pitch, 0.5) > 6.0)
        assertTrue("a steady ring does not", Math.abs(energy.riseDb(pitch, 1.0)) < 3.0)
        assertEquals(loud, energy.peakDb(pitch, 0.6, 1.4), 6.0)
    }
}
