package com.yuushilabs.pianosheet.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * Window plan, stitching and cancellation of the PTI pass, with a scripted model.
 *
 * ONNX Runtime cannot run on the JVM, so the model is replaced by one that writes
 * a recognisable pattern into every frame: the stitched timeline then proves that
 * exactly the middle 50 % of each window was kept, in the right order, at the
 * right place.
 */
class PtiOnnxTranscriberTest {

    /** Frames carry `windowIndex * 10_000 + localFrame`, so a value names its source. */
    private class TaggingModel(private val frameCount: Int = PtiFrames.FRAMES) : PtiWindowModel {
        var calls = 0
            private set

        override fun predict(segment: FloatArray): PtiFrames {
            require(segment.size == PtiOnnxTranscriber.SEGMENT_SAMPLES)
            val n = frameCount
            val size = n * PtiFrames.CLASSES
            val onset = FloatArray(size)
            val offset = FloatArray(size)
            val frame = FloatArray(size)
            val velocity = FloatArray(size)
            for (f in 0 until n) {
                val tag = calls * 10_000f + f
                onset[f * PtiFrames.CLASSES] = tag
                offset[f * PtiFrames.CLASSES] = tag
                frame[f * PtiFrames.CLASSES] = tag
                velocity[f * PtiFrames.CLASSES] = tag
            }
            calls += 1
            return PtiFrames(n, onset, offset, frame, velocity)
        }

        override fun close() {}
    }

    private fun samples(seconds: Double) = (seconds * PtiOnnxTranscriber.SAMPLE_RATE).toInt()

    @Test
    fun `a 40 second take runs seven windows and reports each one`() {
        val model = TaggingModel()
        val progress = ArrayList<Pair<Int, Int>>()
        val result = PtiOnnxTranscriber(model).transcribe(
            FloatArray(samples(40.0)),
            onProgress = { done, total -> progress += done to total },
        )

        assertEquals(7, result.chunkCount)
        assertEquals(7, model.calls)
        assertEquals((1..7).map { it to 7 }, progress)
        // 40 s of audio is 4000 frames at 100 fps.
        assertEquals(4000, result.frameCount)
    }

    @Test
    fun `each window contributes its middle half except at the ends`() {
        val model = TaggingModel()
        val frames = PtiOnnxTranscriber(model).stitch(FloatArray(samples(40.0)))
        val plan = PtiOnnxTranscriber(model).keepPlan(samples(40.0))

        assertEquals(7, plan.size)
        // First window keeps frames 0..749, the middles 250..749, the last 250..999.
        assertEquals(PtiOnnxTranscriber.Keep(0, 0, 750), plan.first())
        assertEquals(PtiOnnxTranscriber.Keep(6, 250, 1000), plan.last())

        var cursor = 0
        for (item in plan) {
            for (local in item.from until item.to) {
                val expected = item.window * 10_000f + local
                assertEquals(
                    "frame $cursor should come from window ${item.window} frame $local",
                    expected,
                    frames.frame[cursor * PtiFrames.CLASSES],
                    1e-3f,
                )
                cursor += 1
            }
        }
        assertEquals(4000, cursor)
    }

    @Test
    fun `a short take is one padded window and keeps the audio's frames`() {
        val model = TaggingModel()
        val result = PtiOnnxTranscriber(model).transcribe(FloatArray(samples(3.5)))
        assertEquals(1, result.chunkCount)
        assertEquals(1, model.calls)
        assertEquals(350, result.frameCount)
    }

    @Test
    fun `audio longer than a whole number of windows is padded, not truncated`() {
        val model = TaggingModel()
        val plan = PtiOnnxTranscriber(model).keepPlan(samples(25.0))
        // 25 s → 3 windows of 10 s (padded to 30 s) → 5 half-overlapping windows.
        assertEquals(5, plan.size)
        val windows = ceil(25.0 / 10.0).toInt()
        assertEquals(3, windows)
        val stitched = PtiOnnxTranscriber(model).stitch(FloatArray(samples(25.0)))
        assertEquals(2500, stitched.frameCount)
    }

    @Test
    fun `cancelling stops before the next window runs`() {
        val model = TaggingModel()
        val transcriber = PtiOnnxTranscriber(model)
        var windows = 0
        var cancel = false
        try {
            transcriber.transcribe(
                FloatArray(samples(40.0)),
                shouldCancel = { cancel },
                onProgress = { _, _ ->
                    windows += 1
                    if (windows == 2) cancel = true
                },
            )
            throw AssertionError("the pass should have been interrupted")
        } catch (expected: InterruptedException) {
            assertEquals(2, model.calls)
        }
    }

    @Test
    fun `a silent window produces no notes`() {
        val silent = object : PtiWindowModel {
            override fun predict(segment: FloatArray): PtiFrames {
                val size = PtiFrames.FRAMES * PtiFrames.CLASSES
                return PtiFrames(PtiFrames.FRAMES, FloatArray(size), FloatArray(size),
                    FloatArray(size), FloatArray(size))
            }

            override fun close() {}
        }
        val result = PtiOnnxTranscriber(silent).transcribe(FloatArray(samples(10.0)))
        assertTrue(result.notes.isEmpty())
    }
}
