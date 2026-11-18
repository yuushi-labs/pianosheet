package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Whole-file denoiser used by the final-analysis path (and the reference for the
 * tests).  It runs the fixed chain from spec §20.3:
 *
 *   25 Hz high-pass -> stationary noise subtraction (optional notch) -> fade-in
 *
 * The saved recording is never passed through this; it only sees an analysis copy.
 */
class Denoiser(private val config: DenoiseConfig = DenoiseConfig()) {

    private val window = Fft.periodicHann(config.frameSize)
    private val subtractMask = config.subtractMask()
    private val oversubtraction = config.oversubtractionByBin()
    private val floor = config.floorByBin()

    /** Stationary tones removed from the last processed take (diagnostics). */
    var lastToneNotchesHz: List<Double> = emptyList()
        private set

    fun process(input: FloatArray): FloatArray {
        if (!config.enabled || input.isEmpty()) return input.copyOf()

        val x = DoubleArray(input.size) { input[it].toDouble() }
        var y = x
        y = BiquadDesign.highPass(config.highpassHz, config.sampleRate.toDouble(), config.highpassQ)
            .process(y)
        config.notchHz?.let { notch ->
            y = BiquadDesign.notch(notch, config.sampleRate.toDouble(), config.notchQ).process(y)
        }

        // A steady high whistle ignores the statistical noise floor (it is not
        // "between the partials", it is a partial of nothing), so it gets its own
        // narrow notch.  The frequencies come from the caller when it measured
        // them already, otherwise from this take.
        val tones = when {
            !config.toneNotchEnabled -> emptyList()
            config.toneNotchesHz != null -> config.toneNotchesHz
            else -> ToneNoiseDetector.detect(
                audio = input,
                sampleRate = config.sampleRate,
                frameSize = config.frameSize,
                hopSize = config.hopSize,
                maxCount = config.toneNotchMaxCount,
            ).map { it.frequencyHz }
        }
        lastToneNotchesHz = tones
        // Suppression is done inside the STFT (a few bins either side of the tone)
        // rather than with a deep IIR notch: it removes the whistle without
        // carving a hole in the semitone of a real note.
        val toneMask = toneMask(config, tones)
        val toneGain = 10.0.pow(config.toneNotchGainDb / 20.0)

        val frameSize = config.frameSize
        val hop = config.hopSize
        val length = y.size
        val padded = if (length < frameSize) y.copyOf(frameSize) else y
        val nFrames = 1 + (padded.size - frameSize) / hop

        val ola = DoubleArray(length + frameSize)
        val env = DoubleArray(length + frameSize)
        val windowSquared = DoubleArray(frameSize) { window[it] * window[it] }

        var previousGains = DoubleArray(config.nBins) { 1.0 }
        val params = config.strength

        for (frameIndex in 0 until nFrames) {
            val start = frameIndex * hop
            val frame = DoubleArray(frameSize) { i -> padded[start + i] * window[i] }
            val (specRe, specIm) = Fft.rfft(frame, frameSize)
            val magnitude = DoubleArray(config.nBins) { bin ->
                sqrt(specRe[bin] * specRe[bin] + specIm[bin] * specIm[bin])
            }
            val noise = DenoiseMath.spectralFloor(magnitude)
            val target = DenoiseMath.subtractGains(magnitude, noise, oversubtraction, floor)
            val smoothed = DoubleArray(config.nBins) { bin ->
                params.gainSmoothing * previousGains[bin] + (1.0 - params.gainSmoothing) * target[bin]
            }
            previousGains = smoothed
            val clamped = DoubleArray(config.nBins) { bin -> smoothed[bin].coerceIn(0.0, 1.0) }
            val smoothedAcrossBins = DenoiseMath.smooth3(clamped)
            val gains = DoubleArray(config.nBins) { bin ->
                val base = if (subtractMask[bin]) smoothedAcrossBins[bin] else 1.0
                if (toneMask[bin]) minOf(base, toneGain) else base
            }
            for (bin in 0 until config.nBins) {
                specRe[bin] *= gains[bin]
                specIm[bin] *= gains[bin]
            }
            val cleaned = Fft.irfft(specRe, specIm, frameSize)
            for (i in 0 until frameSize) {
                ola[start + i] += cleaned[i] * window[i]
                env[start + i] += windowSquared[i]
            }
        }

        var maxEnv = 0.0
        for (value in env) if (value > maxEnv) maxEnv = value
        val envFloor = if (maxEnv > 0.0) 1e-3 * maxEnv else 1.0
        val fadeSamples = Math.round(DenoiseMath.FADE_IN_MS * config.sampleRate / 1000.0).toInt()
        val fade = DenoiseMath.fadeIn(length, fadeSamples)
        return FloatArray(length) { i ->
            val normalised = ola[i] / max(env[i], envFloor)
            (normalised * fade[i]).toFloat()
        }
    }

    /** Bins covered by the stationary-tone suppression. */
    private fun toneMask(config: DenoiseConfig, tones: List<Double>): BooleanArray {
        val mask = BooleanArray(config.nBins)
        if (!config.toneNotchEnabled || tones.isEmpty()) return mask
        val binHz = config.sampleRate.toDouble() / config.frameSize
        for (tone in tones.take(config.toneNotchMaxCount)) {
            val centre = Math.round(tone / binHz).toInt()
            for (bin in centre - config.toneNotchHalfWidthBins..centre + config.toneNotchHalfWidthBins) {
                if (bin in 0 until config.nBins) mask[bin] = true
            }
        }
        return mask
    }

    /** Diagnostics for the settings screen / logs. */
    fun silenceFloorDb(input: FloatArray): Double {
        if (input.isEmpty()) return -120.0
        val frameSize = config.frameSize
        val hop = config.hopSize
        val nFrames = 1 + ((input.size - frameSize).coerceAtLeast(0)) / hop
        if (nFrames <= 0) return DenoiseMath.rmsDb(input)
        val powers = DoubleArray(nFrames) { frame ->
            var sum = 0.0
            for (i in 0 until frameSize) {
                val sample = input[frame * hop + i].toDouble()
                sum += sample * sample
            }
            sum / frameSize
        }
        powers.sort()
        val quietCount = max(1, nFrames / 10)
        var sum = 0.0
        for (i in 0 until quietCount) sum += powers[i]
        return 20.0 * log10(sqrt(sum / quietCount) + 1e-12)
    }
}

/** Which mains frequency the room is humming at, or null when it is not audible. */
object MainsHumDetector {
    fun detect(
        audio: FloatArray,
        sampleRate: Int = 22050,
        candidates: DoubleArray = doubleArrayOf(50.0, 60.0),
        marginDb: Double = 10.0,
    ): Double? {
        if (audio.size < sampleRate / 2) return null
        val inputSize = audio.size
        var n = 1
        while (n < inputSize) n = n shl 1
        val re = DoubleArray(n) { i -> if (i < inputSize) audio[i].toDouble() * (0.5 - 0.5 * kotlin.math.cos(2.0 * kotlin.math.PI * i / (inputSize - 1))) else 0.0 }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val half = n / 2 + 1
        val magnitude = DoubleArray(half) { bin -> sqrt(re[bin] * re[bin] + im[bin] * im[bin]) }
        val binHz = sampleRate.toDouble() / n

        var bestFreq: Double? = null
        var bestMargin = 0.0
        for (f0 in candidates) {
            var peak = 0.0
            val around = ArrayList<Double>()
            for (bin in 0 until half) {
                val freq = bin * binHz
                val distance = kotlin.math.abs(freq - f0)
                if (distance <= 1.5) peak = max(peak, magnitude[bin])
                if (distance > 1.5 && distance <= 6.0) around.add(magnitude[bin])
            }
            if (peak <= 0.0 || around.isEmpty()) continue
            around.sort()
            val floor = around[around.size / 2]
            if (floor <= 0.0) continue
            val margin = 20.0 * log10(peak / floor)
            if (margin > bestMargin) {
                bestMargin = margin
                bestFreq = f0
            }
        }
        return if (bestMargin >= marginDb) bestFreq else null
    }
}

/** Convenience used by callers that do not need the configuration object. */
fun denoiseSignal(
    audio: FloatArray,
    strength: DenoiseStrength = DenoiseStrength.STANDARD,
    sampleRate: Int = 22050,
    notchHz: Double? = null,
): FloatArray = Denoiser(
    DenoiseConfig(strength = strength, sampleRate = sampleRate, notchHz = notchHz),
).process(audio)

/** dBFS of a signal, for logs and tests. */
fun rmsDb(samples: FloatArray): Double = DenoiseMath.rmsDb(samples)
