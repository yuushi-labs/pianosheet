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

data class WindowInference(
    val note: Array<FloatArray>,
    val onset: Array<FloatArray>,
    val contour: Array<FloatArray>,
    val computeMs: Double,
)

/**
 * ONNX Runtime adapter for the Basic Pitch (ICASSP 2022) model.
 *
 * The model file ships in `assets/models/nmp.onnx` (Apache License 2.0, see
 * THIRD_PARTY_NOTICES.md) so the installed APK works on its own, offline. It is
 * copied to app storage once, because ONNX Runtime opens sessions by path.
 *
 * All inference happens on the caller's dedicated dispatcher; this class only
 * serialises access to the session, which is not re-entrant.
 */
class BasicPitchModel(context: Context) : Closeable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = ReentrantLock()
    private val session: OrtSession
    private val inputName: String
    private val noteIndex: Int
    private val onsetIndex: Int
    private val contourIndex: Int

    init {
        val modelFile = ensureModelFile(context)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = environment.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.first()
        val outputNames = session.outputNames.toList()
        noteIndex = outputNames.indexOfFirst { it == BasicPitchConstants.ONNX_NOTE }.let { if (it >= 0) it else 0 }
        onsetIndex = outputNames.indexOfFirst { it == BasicPitchConstants.ONNX_ONSET }.let { if (it >= 0) it else 1 }
        contourIndex = outputNames.indexOfFirst { it == BasicPitchConstants.ONNX_CONTOUR }.let { if (it >= 0) it else 2 }
    }

    /** [audio] is mono 22.05 kHz; it is cropped or zero-padded to one model window. */
    fun predict(audio: FloatArray): WindowInference {
        val input = FloatArray(BasicPitchConstants.N_SAMPLES)
        val copy = minOf(audio.size, input.size)
        audio.copyInto(input, 0, 0, copy)

        val started = System.nanoTime()
        return lock.withLock {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, BasicPitchConstants.N_SAMPLES.toLong(), 1),
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    WindowInference(
                        note = readMatrix(result.get(noteIndex)),
                        onset = readMatrix(result.get(onsetIndex)),
                        contour = readMatrix(result.get(contourIndex)),
                        computeMs = (System.nanoTime() - started) / 1_000_000.0,
                    )
                }
            }
        }
    }

    private fun readMatrix(value: OnnxValue): Array<FloatArray> {
        val tensor = value as OnnxTensor
        val shape = tensor.info.shape
        val frames = if (shape.size > 1) shape[1].toInt() else 1
        val bins = if (shape.size > 2) shape[2].toInt() else 1
        val buffer = tensor.floatBuffer
        val flat = FloatArray(frames * bins)
        buffer.get(flat)
        return Array(frames) { frame ->
            val row = FloatArray(bins)
            flat.copyInto(row, 0, frame * bins, frame * bins + bins)
            row
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val ASSET_PATH = "models/nmp.onnx"

        fun ensureModelFile(context: Context): File {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val target = File(dir, "nmp.onnx")
            if (!target.exists() || target.length() < 1_000) {
                context.assets.open(ASSET_PATH).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return target
        }
    }
}
