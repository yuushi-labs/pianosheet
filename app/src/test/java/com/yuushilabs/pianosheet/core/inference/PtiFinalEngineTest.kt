package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.domain.final.EngineUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PTI engine with a scripted model: what it asks for, what it releases, and
 * how it maps model notes to app notes.
 */
class PtiFinalEngineTest {

    /** Beeps one note (MIDI 60) at 0.5 s in every window. */
    private class BeepModel : PtiWindowModel {
        var segments = 0
            private set
        var closed = false
            private set

        override fun predict(segment: FloatArray): PtiFrames {
            segments += 1
            val n = PtiFrames.FRAMES
            val size = n * PtiFrames.CLASSES
            val onset = FloatArray(size)
            val offset = FloatArray(size)
            val frame = FloatArray(size)
            val velocity = FloatArray(size)
            val key = 60 - PtiFrames.BEGIN_NOTE
            // A peak at 0.5 s, monotonic on both sides for two frames, and a held
            // key from there so the decoder closes the note on the frame output.
            for (i in 48..54) onset[i * PtiFrames.CLASSES + key] = 1f - 0.1f * kotlin.math.abs(i - 50)
            for (i in 50 until 90) {
                offset[i * PtiFrames.CLASSES + key] = 1f - 0.1f * kotlin.math.abs(i - 60)
                frame[i * PtiFrames.CLASSES + key] = 0.9f
                velocity[i * PtiFrames.CLASSES + key] = 0.6f
            }
            return PtiFrames(n, onset, offset, frame, velocity)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `notes come from the session and the session is released afterwards`() {
        val model = BeepModel()
        var released: PtiWindowModel? = null
        val engine = PtiFinalEngine(
            modelProvider = { model },
            releaseModel = { released = it },
        )
        val progress = ArrayList<Pair<Int, Int>>()
        val result = engine.notes(
            audio = FloatArray(16_000 * 4), // 4 s at the take's rate
            sampleRate = 16_000,
            shouldCancel = { false },
            onProgress = { done, total -> progress += done to total },
        )

        assertEquals(1, model.segments)
        assertEquals(model, released)
        assertNotNull(result.notes.firstOrNull())
        val first = result.notes.first()
        assertEquals(60, first.pitchMidi)
        assertEquals(0.5, first.onsetMs / 1000.0, 0.05)
        assertTrue("duration should be positive", (first.durationMs ?: 0.0) > 0.0)
        assertEquals("pti_onnx", first.detector)
        assertEquals(1, result.chunkCount)
        assertEquals(listOf(1 to 1), progress)
    }

    @Test
    fun `the session is released even when the model throws`() {
        val model = object : PtiWindowModel {
            override fun predict(segment: FloatArray): PtiFrames =
                throw OutOfMemoryError("cannot allocate")
            override fun close() {}
        }
        var released = false
        val engine = PtiFinalEngine(
            modelProvider = { model },
            releaseModel = { released = true },
        )
        try {
            engine.notes(FloatArray(16_000), 16_000, { false }, { _, _ -> })
            throw AssertionError("the OOM should reach the caller")
        } catch (expected: OutOfMemoryError) {
            assertTrue("the session must be freed on the way out", released)
        }
    }

    @Test
    fun `a device without free memory does not even ask for the session`() {
        var asked = false
        val engine = PtiFinalEngine(
            modelProvider = { asked = true; BeepModel() },
            canRun = { false },
        )
        try {
            engine.notes(FloatArray(16_000), 16_000, { false }, { _, _ -> })
            throw AssertionError("should have refused")
        } catch (expected: EngineUnavailableException) {
            assertTrue(expected.message!!.contains("メモリ"))
            assertEquals(false, asked)
        }
    }
}
