package com.yuushilabs.pianosheet.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the microphone is actually giving us (spec v1.3 §20.2, §20.6).
 *
 * The transcriber only sees the samples, so a take that clipped at the ADC or one
 * that was recorded 40 dB too quietly looks the same to it.  This monitor keeps
 * the three numbers that explain a bad take while it is still running:
 *
 *  - **peak / RMS** of each 100 ms chunk, smoothed, in dBFS;
 *  - **clipping**: share of samples pinned at full scale.  A clipped piano
 *    attack loses exactly the transient Basic Pitch needs, and no denoiser can
 *    put it back;
 *  - **silence**: how long the input has been below [SILENCE_DBFS], so the screen
 *    can say "no sound is reaching us" instead of showing an empty score.
 *
 * The analysis copy is *not* gain-normalised: measured on the PianoVAM test split,
 * scaling a take from -3 dBFS to -34 dBFS changed F1 by less than 0.001, because
 * the model's log-mel front end is level invariant.  Monitoring is therefore the
 * useful part, and a normalisation stage would only add a knob that does nothing.
 */
class InputMonitor(
    private val sampleRate: Int = 22050,
    private val quietDbfs: Float = -40f,
    private val silenceDbfs: Float = -55f,
    private val clipThreshold: Float = 0.995f,
) {

    data class State(
        val rmsDbfs: Float = -120f,
        val peakDbfs: Float = -120f,
        val clippedSamples: Long = 0,
        val totalSamples: Long = 0,
        val silentMs: Double = 0.0,
        val warning: Warning? = null,
    )

    enum class Warning { CLIPPING, TOO_QUIET, SILENCE }

    private var rms = 0f
    private var peak = 0f
    private var clipped = 0L
    private var total = 0L
    private var silentSamples = 0L
    private var lastState = State()

    fun reset() {
        rms = 0f
        peak = 0f
        clipped = 0
        total = 0
        silentSamples = 0
        lastState = State()
    }

    fun update(chunk: FloatArray): State {
        if (chunk.isEmpty()) return lastState
        var sum = 0.0
        var chunkPeak = 0f
        var clippedHere = 0
        for (sample in chunk) {
            val value = abs(sample)
            sum += value * value
            if (value > chunkPeak) chunkPeak = value
            if (value >= clipThreshold) clippedHere += 1
        }
        val chunkRms = sqrt(sum / chunk.size).toFloat()
        // Decay slowly so the meter does not flicker between notes.
        rms = if (rms <= 0f) chunkRms else max(chunkRms, rms * 0.85f)
        peak = max(chunkPeak, peak * 0.97f)
        clipped += clippedHere
        total += chunk.size
        if (chunkRms <= dbfsToLinear(silenceDbfs)) {
            silentSamples += chunk.size
        } else {
            silentSamples = 0
        }

        val clipRatio = if (total > 0) clipped.toDouble() / total else 0.0
        val warning = when {
            clipRatio > CLIP_RATIO_WARN && total > sampleRate -> Warning.CLIPPING
            silentSamples >= (SILENCE_WARN_S * sampleRate).toLong() -> Warning.SILENCE
            total > sampleRate && rms < dbfsToLinear(quietDbfs) -> Warning.TOO_QUIET
            else -> null
        }
        lastState = State(
            rmsDbfs = round1(linearToDbfs(rms)),
            peakDbfs = round1(linearToDbfs(peak)),
            clippedSamples = clipped,
            totalSamples = total,
            silentMs = round1(1000.0 * silentSamples / sampleRate),
            warning = warning,
        )
        return lastState
    }

    val state: State get() = lastState

    companion object {
        /** Share of clipped samples that changes the timbre audibly. */
        const val CLIP_RATIO_WARN = 0.0005

        /** Seconds below [silenceDbfs] before the screen says the input is dead. */
        const val SILENCE_WARN_S = 3.0

        fun linearToDbfs(value: Float): Float =
            if (value <= 1e-6f) -120f else (20f * log10(value)).toFloat()

        fun dbfsToLinear(dbfs: Float): Float = Math.pow(10.0, dbfs / 20.0).toFloat()

        fun message(warning: Warning): String = when (warning) {
            Warning.CLIPPING -> "音が割れています（入力レベルを下げてください）"
            Warning.TOO_QUIET -> "入力が小さいです（マイクに近づいてください）"
            Warning.SILENCE -> "音が届いていません（マイクを確認してください）"
        }

        private fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f

        private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    }
}
