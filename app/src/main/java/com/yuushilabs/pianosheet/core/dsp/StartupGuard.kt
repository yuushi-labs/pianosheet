package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Protection against the artefacts of the first quarter second of a recording.
 *
 * Two things happen when the microphone opens: the input amplifier settles
 * (a DC step / pop), and the very first analysis window is zero-padded on the
 * left, which is a step edge in the signal.  Basic Pitch hears both as attacks,
 * which is why a take used to open with a stray low-confidence note around
 * 0.1–0.2 s.
 *
 * The guard therefore
 *  1. ramps the *analysis copy* from silence over [RAMP_MS] (the saved WAV is
 *     never touched, spec §20.1),
 *  2. removes the DC offset in that first stretch so the step cannot look like
 *     a broadband attack,
 *  3. rejects onsets inside the guard window unless they are a genuinely strong
 *     key strike, so a real first note that the player hits hard still appears.
 */
object StartupGuard {

    /** Linear fade-in applied to the analysis copy. */
    const val RAMP_MS = 250.0

    /** Extra fade when a detection window had to be zero-padded on the left. */
    const val EDGE_FADE_MS = 30.0

    /** Onsets below this position are checked for the artefact rules. */
    const val GUARD_MS = 250.0

    /** A strike this strong (and this confident) is kept even inside the guard. */
    const val STRONG_ONSET_STRENGTH = 0.70f
    const val STRONG_CONFIDENCE = 0.45f

    /** Gain (0..1) at [elapsedS] into the recording. */
    fun gainAt(elapsedS: Double, rampMs: Double = RAMP_MS): Float {
        if (rampMs <= 0.0) return 1f
        if (elapsedS <= 0.0) return 0f
        val fraction = elapsedS * 1000.0 / rampMs
        return if (fraction >= 1.0) 1f else fraction.toFloat()
    }

    /**
     * Applies the start ramp to one chunk.  [startSample] is the chunk's absolute
     * position in the recording, so chunks that begin after the ramp are returned
     * unchanged (no copy).
     */
    fun applyRamp(
        audio: FloatArray,
        startSample: Long,
        sampleRate: Int,
        rampMs: Double = RAMP_MS,
    ): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0 || rampMs <= 0.0) return audio
        val rampSamples = (rampMs * sampleRate / 1000.0).toLong()
        if (startSample >= rampSamples) return audio
        val result = audio.copyOf()
        var index = 0
        while (index < result.size && startSample + index < rampSamples) {
            val elapsedS = (startSample + index).toDouble() / sampleRate
            result[index] = result[index] * gainAt(elapsedS, rampMs)
            index += 1
        }
        return result
    }

    /**
     * Whole-buffer treatment for the final analysis and for the live engine's
     * first chunk: start ramp + DC removal inside the guard window.
     */
    fun applyStartupTreatment(
        audio: FloatArray,
        startSample: Long = 0L,
        sampleRate: Int = 22050,
        rampMs: Double = RAMP_MS,
    ): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        val guardSamples = (GUARD_MS * sampleRate / 1000.0).toInt()
        val overlapStart = (startSample).coerceAtLeast(0L)
        val overlapEnd = min(audio.size.toLong(), (guardSamples - overlapStart)).toInt()
        val working = if (overlapEnd > 0) {
            val copy = audio.copyOf()
            var sum = 0.0
            for (i in 0 until overlapEnd) sum += copy[i]
            val mean = (sum / overlapEnd).toFloat()
            for (i in 0 until overlapEnd) copy[i] -= mean
            copy
        } else {
            audio
        }
        return applyRamp(working, startSample, sampleRate, rampMs)
    }

    /** A short cosine fade at the very start of a zero-padded window. */
    fun applyEdgeFade(
        audio: FloatArray,
        sampleRate: Int,
        fadeMs: Double = EDGE_FADE_MS,
    ): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0 || fadeMs <= 0.0) return audio
        val fadeSamples = min(audio.size, (fadeMs * sampleRate / 1000.0).toInt())
        if (fadeSamples <= 0) return audio
        val result = audio.copyOf()
        for (i in 0 until fadeSamples) {
            val gain = (0.5 - 0.5 * cos(PI * i / fadeSamples)).toFloat()
            result[i] *= gain
        }
        return result
    }

    /** True when an onset inside the guard window looks like an artefact. */
    fun isStartupArtifact(
        onsetS: Double,
        confidence: Float,
        onsetStrength: Float?,
        guardMs: Double = GUARD_MS,
    ): Boolean {
        if (onsetS >= guardMs / 1000.0) return false
        val strongStrike = (onsetStrength ?: 0f) >= STRONG_ONSET_STRENGTH &&
            confidence >= STRONG_CONFIDENCE
        return !strongStrike
    }

    /** Soft "tick" remover: a one-sample spike inside the guard window is smoothed. */
    fun suppressSpikes(
        audio: FloatArray,
        sampleRate: Int,
        windowMs: Double = GUARD_MS,
    ): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        val limit = min(audio.size, (windowMs * sampleRate / 1000.0).toInt())
        if (limit < 3) return audio
        var peak = 0f
        for (i in 0 until limit) peak = maxOf(peak, kotlin.math.abs(audio[i]))
        if (peak <= 1e-6f) return audio
        val threshold = peak * 0.85f
        var result: FloatArray? = null
        for (i in 1 until limit - 1) {
            val previous = audio[i - 1]
            val current = audio[i]
            val next = audio[i + 1]
            val neighbour = (previous + next) / 2f
            if (kotlin.math.abs(current) > threshold && kotlin.math.abs(neighbour) < threshold * 0.3f) {
                val copy = result ?: audio.copyOf().also { result = it }
                copy[i] = neighbour
            }
        }
        return result ?: audio
    }

    /** A sine used only by the startup tests to describe a settling microphone. */
    internal fun testTone(length: Int, sampleRate: Int, frequency: Double, amplitude: Float): FloatArray =
        FloatArray(length) { i ->
            (amplitude * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
        }
}
