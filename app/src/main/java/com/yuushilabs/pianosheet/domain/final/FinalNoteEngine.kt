package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.model.NoteEvent

/**
 * A note source for the final analysis (spec §5).
 *
 * The app's final pass is PTI-ONNX (`pti_onnx`); Basic Pitch stays the live
 * detector and the fallback when the PTI session cannot be built or the device
 * runs out of memory.  Keeping the two behind this interface is what lets the
 * analysis screen say which engine produced the score.
 */
interface FinalNoteEngine {

    /** Stable id stored with the score, e.g. `pti_onnx`. */
    val id: String

    /** Text for the analysis screen, e.g. "PTI-ONNX（ピアノ専用）". */
    val displayName: String

    /**
     * [audio] is the analysis copy (denoised, startup-treated), mono at
     * [sampleRate].  Implementations may resample internally.
     *
     * Throws whatever the model throws; an `OutOfMemoryError` or a failed
     * session build must reach [FinalTranscriber] so it can fall back.
     */
    fun notes(
        audio: FloatArray,
        sampleRate: Int,
        shouldCancel: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): EngineResult
}

data class EngineResult(
    val notes: List<NoteEvent>,
    /** Inference windows the engine ran, for the progress and the report. */
    val chunkCount: Int,
    val detail: Map<String, String> = emptyMap(),
)

/** The engine cannot run on this take (session build failed, memory too low). */
class EngineUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
