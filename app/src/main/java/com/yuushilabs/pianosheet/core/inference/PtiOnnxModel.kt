package com.yuushilabs.pianosheet.core.inference

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Model-frame outputs of one 10 s PTI window.
 *
 * All four heads are `[frames][88]` with the key of MIDI note `21 + key`, stored
 * flat as `head[frame * CLASSES + key]` because the decoder walks a key column
 * frame by frame and a flat array keeps that walk allocation-free.
 */
class PtiFrames(
    val frameCount: Int,
    val onsetReg: FloatArray,
    val offsetReg: FloatArray,
    val frame: FloatArray,
    val velocity: FloatArray,
) {
    init {
        val expected = frameCount * CLASSES
        require(onsetReg.size == expected && offsetReg.size == expected &&
            frame.size == expected && velocity.size == expected) {
            "PTI frame arrays must be ${expected} values each"
        }
    }

    /** Probability that a key is being held, at [frame] for MIDI [pitch]. */
    fun frameAt(frame: Int, pitch: Int): Float = this.frame[frame * CLASSES + (pitch - BEGIN_NOTE)]

    companion object {
        const val CLASSES = 88
        const val BEGIN_NOTE = 21
        /** 10 s at the model's 100 frames per second. */
        const val FRAMES = 1000
        /** The model also emits one extra frame per window that is dropped. */
        const val RAW_FRAMES = 1001
    }
}

/** A PTI model that turns one 10 s, 16 kHz window into [PtiFrames]. */
interface PtiWindowModel : Closeable {
    fun predict(segment: FloatArray): PtiFrames
}

/**
 * ONNX Runtime adapter for ByteDance's PTI note branch (`piano-notes-v1.onnx`,
 * Kong et al., IEEE/ACM TASLP 2021, CC BY 4.0 — see THIRD_PARTY_NOTICES.md).
 *
 * The 94 MB weights ship in `assets/models/piano-notes-v1.onnx` and are copied
 * to app storage once, because ONNX Runtime opens sessions by path.  The model
 * contains its own log-mel front end, so the caller only resamples to 16 kHz.
 */
class PtiOnnxModel(
    context: Context,
    threads: Int = 2,
) : PtiWindowModel {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = ReentrantLock()
    private val session: OrtSession
    private val inputName: String
    /** Output positions of onset / offset / frame / velocity, by name where possible. */
    private val indices: IntArray

    init {
        val modelFile = ensureModelFile(context)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads.coerceAtLeast(1))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = environment.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.first()
        val names = session.outputNames.toList()
        indices = intArrayOf(
            names.indexOfFirst { it == OUTPUT_ONSET }.let { if (it >= 0) it else 0 },
            names.indexOfFirst { it == OUTPUT_OFFSET }.let { if (it >= 0) it else 1 },
            names.indexOfFirst { it == OUTPUT_FRAME }.let { if (it >= 0) it else 2 },
            names.indexOfFirst { it == OUTPUT_VELOCITY }.let { if (it >= 0) it else 3 },
        )
    }

    /** [segment] must be exactly [PtiOnnxTranscriber.SEGMENT_SAMPLES] mono 16 kHz samples. */
    override fun predict(segment: FloatArray): PtiFrames {
        require(segment.size == PtiOnnxTranscriber.SEGMENT_SAMPLES) {
            "PTI window must be ${PtiOnnxTranscriber.SEGMENT_SAMPLES} samples, got ${segment.size}"
        }
        return lock.withLock {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(segment),
                longArrayOf(1, segment.size.toLong()),
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val raw = PtiFrames.RAW_FRAMES
                    PtiFrames(
                        frameCount = raw - 1,
                        onsetReg = flatten(result.get(indices[0])),
                        offsetReg = flatten(result.get(indices[1])),
                        frame = flatten(result.get(indices[2])),
                        velocity = flatten(result.get(indices[3])),
                    )
                }
            }
        }
    }

    /** `[1, 1001, 88]` → the first 1000 frames, flattened. */
    private fun flatten(value: OnnxValue): FloatArray {
        val tensor = value as OnnxTensor
        val shape = tensor.info.shape
        val frames = shape[1].toInt()
        val classes = shape[2].toInt()
        val all = FloatArray(frames * classes)
        tensor.floatBuffer.get(all)
        val keep = (frames - 1) * classes
        return all.copyOf(keep)
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ASSET_PATH = "models/piano-notes-v1.onnx"
        const val FILE_NAME = "piano-notes-v1.onnx"
        const val MODEL_BYTES = 98_689_388L
        const val SHA256 = "9e33ffc0382fb023e0b1aac2abd0c3cb26a5d56fbcc246d7f2c0fc33c569f2ba"

        private const val OUTPUT_ONSET = "reg_onset_output"
        private const val OUTPUT_OFFSET = "reg_offset_output"
        private const val OUTPUT_FRAME = "frame_output"
        private const val OUTPUT_VELOCITY = "velocity_output"

        /** Copy the asset once; a truncated copy (app killed mid-copy) is redone. */
        fun ensureModelFile(context: Context): File {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val target = File(dir, FILE_NAME)
            if (!target.exists() || target.length() != MODEL_BYTES) {
                val temp = File(dir, "$FILE_NAME.part")
                temp.delete()
                context.assets.open(ASSET_PATH).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                if (temp.length() != MODEL_BYTES) {
                    temp.delete()
                    error("モデルファイルを書き出せませんでした")
                }
                if (target.exists()) target.delete()
                temp.renameTo(target)
            }
            return target
        }
    }
}
