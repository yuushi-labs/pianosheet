package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.core.dsp.StartupGuard
import com.yuushilabs.pianosheet.domain.live.ChunkDetector
import com.yuushilabs.pianosheet.domain.live.Detection
import kotlin.math.min

/**
 * Basic Pitch behind the live engine: one 2 s window in, note candidates out.
 *
 * A window shorter than the model input (only at the very start of a session)
 * is zero-padded on the *left*, so the newest audio always sits at the end and
 * onset times stay on the recording's clock.
 */
class BasicPitchDetector(
    private val model: BasicPitchModel,
    private val onsetThreshold: Float = BasicPitchConstants.DEFAULT_ONSET_THRESHOLD,
    private val frameThreshold: Float = BasicPitchConstants.DEFAULT_FRAME_THRESHOLD,
    private val minNoteLength: Int = BasicPitchConstants.DEFAULT_MIN_NOTE_LENGTH_FRAMES,
) : ChunkDetector {

    override val name: String = "basic_pitch_onnx"

    var lastComputeMs: Double = 0.0
        private set

    override fun detect(window: FloatArray, windowStartS: Double): List<Detection> {
        var audio = window
        var startS = windowStartS
        val modelSamples = BasicPitchConstants.N_SAMPLES

        if (audio.size > modelSamples) {
            val drop = audio.size - modelSamples
            audio = audio.copyOfRange(drop, audio.size)
            startS += drop.toDouble() / BasicPitchConstants.SAMPLE_RATE
        } else if (audio.size < modelSamples) {
            val pad = modelSamples - audio.size
            val padded = FloatArray(modelSamples)
            // Fading the real audio in stops the step from digital silence to the
            // first sample from being read as a broadband attack.
            StartupGuard.applyEdgeFade(audio, BasicPitchConstants.SAMPLE_RATE).copyInto(padded, pad)
            audio = padded
            startS -= pad.toDouble() / BasicPitchConstants.SAMPLE_RATE
        }

        val inference = model.predict(audio)
        lastComputeMs = inference.computeMs
        val frameSeconds = BasicPitchConstants.frameSeconds
        val notes = BasicPitchDecoder.decode(
            note = inference.note,
            onset = inference.onset,
            onsetThreshold = onsetThreshold,
            frameThreshold = frameThreshold,
            minNoteLength = minNoteLength,
            inferOnsets = true,
            melodiaTrick = true,
        )
        return notes.map { note ->
            Detection(
                pitchMidi = note.pitchMidi,
                onsetS = startS + note.startFrame * frameSeconds,
                endS = startS + min(note.endFrame, BasicPitchConstants.N_FRAMES) * frameSeconds,
                confidence = note.amplitude,
                onsetStrength = note.onsetStrength,
            )
        }
    }
}
