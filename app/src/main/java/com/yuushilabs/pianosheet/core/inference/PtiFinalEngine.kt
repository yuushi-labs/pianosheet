package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.core.audio.AudioResampler
import com.yuushilabs.pianosheet.domain.final.EngineResult
import com.yuushilabs.pianosheet.domain.final.EngineUnavailableException
import com.yuushilabs.pianosheet.domain.final.FinalNoteEngine
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.NoteState

/**
 * The final analysis engine: PTI-ONNX over the whole take.
 *
 * Onsets and offsets come from the model's regression heads (100 fps, so the
 * grid is 10 ms instead of Basic Pitch's 11.6 ms), velocity is mapped to the
 * note's confidence, and nothing else from the Basic Pitch post-processing
 * chain is reused: PianoVAM showed PTI needs neither the octave-ghost filter
 * (the pure high lines simply do not appear) nor the short-note rejection
 * (its offsets are regression times, not frame runs).
 *
 * The session is asked for only when there is something to transcribe and is
 * released again as soon as the notes are out: its arena peaks around 1.1–1.2 GB,
 * so holding it between analyses would be the difference between a 3 GB phone
 * keeping the app alive and losing it to the background killer.
 */
class PtiFinalEngine(
    private val modelProvider: () -> PtiWindowModel,
    private val releaseModel: (PtiWindowModel) -> Unit = {},
    private val postProcessor: PtiPostProcessor = PtiPostProcessor(),
    /** Checked before the session is used, e.g. free RAM on the device. */
    private val canRun: () -> Boolean = { true },
) : FinalNoteEngine {

    /** Convenience for a session that lives as long as this engine. */
    constructor(
        model: PtiWindowModel,
        postProcessor: PtiPostProcessor = PtiPostProcessor(),
        canRun: () -> Boolean = { true },
    ) : this(
        modelProvider = { model },
        releaseModel = {},
        postProcessor = postProcessor,
        canRun = canRun,
    )

    override val id: String = ID
    override val displayName: String = "PTI-ONNX（ピアノ専用）"

    override fun notes(
        audio: FloatArray,
        sampleRate: Int,
        shouldCancel: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): EngineResult {
        if (!canRun()) {
            throw EngineUnavailableException("端末の空きメモリが足りません")
        }
        val model = modelProvider()
        val result = try {
            val audio16 = if (sampleRate == SAMPLE_RATE) {
                audio
            } else {
                AudioResampler.resample(audio, sampleRate, SAMPLE_RATE)
            }
            PtiOnnxTranscriber(model, postProcessor).transcribe(
                audio = audio16,
                shouldCancel = shouldCancel,
                onProgress = onProgress,
            )
        } finally {
            releaseModel(model)
        }
        val notes = ArrayList<NoteEvent>(result.notes.size)
        for (note in result.notes) {
            // The 250 ms ramp already shaped the very start; a note inside the
            // first 100 ms with a weak velocity is the microphone settling.
            if (note.onsetS < STARTUP_GUARD_S && note.velocity < STARTUP_CONFIDENCE) continue
            notes += NoteEvent(
                pitchMidi = note.pitchMidi,
                onsetMs = note.onsetS * 1000.0,
                durationMs = note.durationS * 1000.0,
                confidence = note.velocity.coerceIn(0.05f, 1f),
                state = NoteState.CONFIRMED,
                detector = ID,
            )
        }
        notes.sortWith(compareBy({ it.onsetMs }, { it.pitchMidi }))
        return EngineResult(
            notes = notes,
            chunkCount = result.chunkCount,
            detail = mapOf(
                "sample_rate" to result.sampleRate.toString(),
                "frames" to result.frameCount.toString(),
                "trimmed" to result.notes.count { it.trimmed }.toString(),
            ),
        )
    }

    companion object {
        const val ID = "pti_onnx"
        const val SAMPLE_RATE = PtiOnnxTranscriber.SAMPLE_RATE
        const val STARTUP_GUARD_S = 0.1
        const val STARTUP_CONFIDENCE = 0.45f
    }
}
