package com.yuushilabs.pianosheet.domain.live

/**
 * Parameters of the streaming engine, taken from the Phase 0 measurements
 * (`docs/phase0-report.md`): 2.0 s window / 0.25 s step, onset strength >= 0.30,
 * a 260 ms guard at the newest edge, and 120 ms same-pitch merging.
 */
data class LiveConfig(
    val sampleRate: Int = 22050,
    val windowS: Double = 2.0,
    val stepS: Double = 0.25,
    val onsetToleranceMs: Double = 120.0,
    val tailMergeMs: Double = 120.0,
    val confidenceFloor: Float = 0f,
    val onsetStrengthMin: Float = 0.30f,
    val restrikeOnsetMin: Float = 0.50f,
    val guardMs: Double = 260.0,
    /** Fade-in on the analysis copy at the very start of the take. */
    val startupRampMs: Double = 250.0,
    /** Onsets this early are checked for the microphone-settling artefact. */
    val startupGuardMs: Double = 250.0,
) {
    val windowSamples: Long get() = Math.round(windowS * sampleRate)
    val stepSamples: Long get() = Math.round(stepS * sampleRate)
}

/** One note candidate returned by a detector for a window. */
data class Detection(
    val pitchMidi: Int,
    val onsetS: Double,
    val endS: Double,
    val confidence: Float,
    val onsetStrength: Float?,
)

/**
 * A detector turns a window of audio into note candidates.  The UI never sees
 * the model directly (spec §9.4.5), so Basic Pitch, a DSP fallback or a future
 * model can be swapped here without touching the live view.
 */
interface ChunkDetector {
    val name: String
    fun detect(window: FloatArray, windowStartS: Double): List<Detection>
}

data class StepStat(
    val stepIndex: Int,
    val audioTimeS: Double,
    val computeMs: Double,
    val nCandidates: Int,
    val nNew: Int,
    val bufferMs: Double,
)
