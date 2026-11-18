package com.yuushilabs.pianosheet.core.inference

import kotlin.math.ceil
import kotlin.math.min

/**
 * PTI over a whole take: 10 s windows every 5 s, each window's middle 50 % kept
 * and stitched into one frame timeline, then decoded in one go.
 *
 * That is `PianoTranscription.enframe` / `deframe` from the reference
 * implementation: with a 5 s hop every sample outside the first and last 2.5 s
 * of the take is heard in two windows, and the middle 50 % of each window is
 * the part whose context is complete on both sides.
 *
 * The model itself is behind [PtiWindowModel] so the whole class runs on the
 * JVM in tests with a scripted model.
 */
class PtiOnnxTranscriber(
    private val model: PtiWindowModel,
    private val postProcessor: PtiPostProcessor = PtiPostProcessor(),
) {

    data class Result(
        val notes: List<PtiNote>,
        val chunkCount: Int,
        /** Frames in the stitched timeline (100 fps). */
        val frameCount: Int,
        val sampleRate: Int,
    )

    /** One window's kept slice in the stitched timeline, for the tests and the report. */
    internal data class Keep(val window: Int, val from: Int, val to: Int)

    /**
     * [audio] is mono at [SAMPLE_RATE]; [onProgress] gets (windowsDone, windowCount).
     * Throws whatever the model throws (an `OutOfMemoryError` included) so the
     * caller can fall back to the other engine.
     */
    fun transcribe(
        audio: FloatArray,
        shouldCancel: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val stitched = stitch(audio, shouldCancel, onProgress)
        return Result(
            notes = postProcessor.decode(stitched, audio),
            chunkCount = chunkCount(audio.size),
            frameCount = stitched.frameCount,
            sampleRate = SAMPLE_RATE,
        )
    }

    /** The stitched model frames for [audio] (100 fps), before decoding. */
    internal fun stitch(
        audio: FloatArray,
        shouldCancel: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PtiFrames {
        val segment = SEGMENT_SAMPLES
        val chunks = ceil(audio.size.toDouble() / segment).toInt().coerceAtLeast(1)
        val paddedSamples = chunks * segment
        val windows = windowCount(paddedSamples)
        val padded = FloatArray(paddedSamples)
        audio.copyInto(padded, 0, 0, min(audio.size, paddedSamples))

        val expectFrames = windows * (PtiFrames.FRAMES - 1) + 2
        val onset = FloatArray(expectFrames * PtiFrames.CLASSES)
        val offset = FloatArray(expectFrames * PtiFrames.CLASSES)
        val frame = FloatArray(expectFrames * PtiFrames.CLASSES)
        val velocity = FloatArray(expectFrames * PtiFrames.CLASSES)

        var start = 0
        var index = 0
        var written = 0
        while (start + segment <= paddedSamples) {
            if (shouldCancel()) throw InterruptedException("解析を中止しました")
            val window = FloatArray(segment)
            padded.copyInto(window, 0, start, start + segment)
            val frames = model.predict(window)
            val local = frames.frameCount
            // deframe: the first window keeps its first 75 %, the middle ones
            // their middle 50 %, the last one everything from 25 % on.
            val from = if (index == 0) 0 else local * 25 / 100
            val to = if (start + segment >= paddedSamples) local else local * 75 / 100
            val count = to - from
            if (count > 0) {
                copyFrames(frames.onsetReg, from, count, onset, written)
                copyFrames(frames.offsetReg, from, count, offset, written)
                copyFrames(frames.frame, from, count, frame, written)
                copyFrames(frames.velocity, from, count, velocity, written)
                written += count
            }
            index += 1
            onProgress(index, windows)
            start += HOP_SAMPLES
        }

        // The reference keeps exactly 100 frames per second of audio.
        val audioFrames = audio.size / SAMPLES_PER_FRAME
        val frames = if (audioFrames > 0) min(written, audioFrames).coerceAtLeast(1) else
            written.coerceAtLeast(1)
        val size = frames * PtiFrames.CLASSES
        return PtiFrames(
            frameCount = frames,
            onsetReg = onset.copyOf(size),
            offsetReg = offset.copyOf(size),
            frame = frame.copyOf(size),
            velocity = velocity.copyOf(size),
        )
    }

    /** Where each window's kept slice landed, for the stitching test. */
    internal fun keepPlan(audioSamples: Int): List<Keep> {
        val segment = SEGMENT_SAMPLES
        val chunks = ceil(audioSamples.toDouble() / segment).toInt().coerceAtLeast(1)
        val paddedSamples = chunks * segment
        val windows = windowCount(paddedSamples)
        val plan = ArrayList<Keep>(windows)
        var start = 0
        var index = 0
        while (start + segment <= paddedSamples) {
            val local = PtiFrames.FRAMES
            val from = if (index == 0) 0 else local * 25 / 100
            val to = if (start + segment >= paddedSamples) local else local * 75 / 100
            plan += Keep(index, from, to)
            index += 1
            start += HOP_SAMPLES
        }
        return plan
    }

    private fun copyFrames(source: FloatArray, from: Int, count: Int, target: FloatArray, at: Int) {
        source.copyInto(
            target,
            destinationOffset = at * PtiFrames.CLASSES,
            startIndex = from * PtiFrames.CLASSES,
            endIndex = (from + count) * PtiFrames.CLASSES,
        )
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 10 s at 16 kHz — the model's fixed input length. */
        const val SEGMENT_SAMPLES = 160_000
        const val HOP_SAMPLES = SEGMENT_SAMPLES / 2
        /** 16 kHz / 100 fps: one model frame is 160 samples. */
        const val FRAMES_PER_SECOND = 100
        const val SAMPLES_PER_FRAME = SAMPLE_RATE / FRAMES_PER_SECOND

        /** Windows a take of [samples] needs, for the progress text. */
        fun chunkCount(samples: Int): Int {
            val chunks = ceil(samples.toDouble() / SEGMENT_SAMPLES).toInt().coerceAtLeast(1)
            return windowCount(chunks * SEGMENT_SAMPLES)
        }

        private fun windowCount(paddedSamples: Int): Int =
            ((paddedSamples - SEGMENT_SAMPLES) / HOP_SAMPLES) + 1
    }
}
