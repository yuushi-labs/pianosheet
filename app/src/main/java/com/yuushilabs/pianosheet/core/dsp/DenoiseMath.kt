package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Low-level pieces of the spectral-subtraction stage, kept pure and testable. */
object DenoiseMath {
    const val FLOOR_BINS = 31 // odd; ~670 Hz at 22.05 kHz / 1024-sample frames
    const val FLOOR_REFRESH_FRAMES = 8
    const val FLOOR_WINDOW_FRAMES = 86 // ~1 s at 86 fps
    const val FLOOR_BIAS = 1.6
    const val FADE_IN_MS = 5.0

    /**
     * Local median of one frame's magnitude spectrum — the level *between* the
     * partials, where the room noise sits. Edge bins are replicated, like
     * `np.pad(..., mode="edge")`.
     */
    fun spectralFloor(magnitude: DoubleArray, size: Int = FLOOR_BINS): DoubleArray {
        val n = magnitude.size
        val window = max(3, size or 1)
        val pad = window / 2
        val out = DoubleArray(n)
        val scratch = DoubleArray(window)
        for (bin in 0 until n) {
            for (k in 0 until window) {
                val index = (bin - pad + k).coerceIn(0, n - 1)
                scratch[k] = magnitude[index]
            }
            scratch.sort()
            out[bin] = scratch[window / 2]
        }
        return out
    }

    /** Per-bin gain for one frame: 1 - alpha*N/M, floored and capped. */
    fun subtractGains(
        magnitude: DoubleArray,
        noise: DoubleArray,
        params: DenoiseStrength,
    ): DoubleArray {
        val floor = 10.0.pow(params.floorDb / 20.0)
        return DoubleArray(magnitude.size) { i ->
            val gain = 1.0 - params.oversubtraction * noise[i] / (magnitude[i] + 1e-12)
            gain.coerceIn(floor, 1.0)
        }
    }

    /**
     * The same subtraction with a per-bin factor and floor, which is how the high
     * band is weighted ([DenoiseConfig.oversubtractionByBin], [DenoiseConfig.floorByBin]).
     */
    fun subtractGains(
        magnitude: DoubleArray,
        noise: DoubleArray,
        oversubtraction: DoubleArray,
        floor: DoubleArray,
    ): DoubleArray = DoubleArray(magnitude.size) { i ->
        val gain = 1.0 - oversubtraction[i] * noise[i] / (magnitude[i] + 1e-12)
        gain.coerceIn(floor[i], 1.0)
    }

    /** 3-bin moving average over frequency, killing isolated musical-noise spikes. */
    fun smooth3(gains: DoubleArray): DoubleArray {
        if (gains.size < 3) return gains.copyOf()
        val out = gains.copyOf()
        for (i in 1 until gains.size - 1) {
            out[i] = (gains[i - 1] + gains[i] + gains[i + 1]) / 3.0
        }
        return out
    }

    /** Raised-cosine fade over the first [fadeSamples] (removes the STFT edge). */
    fun fadeIn(nSamples: Int, fadeSamples: Int): DoubleArray {
        val out = DoubleArray(nSamples) { 1.0 }
        val f = minOf(fadeSamples, nSamples)
        if (f > 1) {
            for (i in 0 until f) {
                out[i] = 0.5 - 0.5 * cos(kotlin.math.PI * i / (f - 1))
            }
        }
        return out
    }

    fun rmsDb(samples: FloatArray): Double {
        if (samples.isEmpty()) return -120.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        val rms = sqrt(sum / samples.size)
        return 20.0 * log10(rms + 1e-12)
    }

    fun rmsDb(samples: DoubleArray): Double {
        if (samples.isEmpty()) return -120.0
        var sum = 0.0
        for (s in samples) sum += s * s
        return 20.0 * log10(sqrt(sum / samples.size) + 1e-12)
    }

    fun meanDb(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }

    fun peak(samples: FloatArray): Double {
        var peak = 0.0
        for (s in samples) {
            val magnitude = abs(s.toDouble())
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }
}

/**
 * Stationary floor per bin: minimum of the local median over ~1 s.
 *
 * The frequency median finds the level between the partials; taking the minimum
 * over the last second keeps only the part of it that stays there when the piano
 * is quiet — a stationary hiss.  A clean take therefore has a floor of ~0.
 */
class FloorTracker(
    private val nBins: Int,
    private val windowFrames: Int = DenoiseMath.FLOOR_WINDOW_FRAMES,
    private val refreshFrames: Int = DenoiseMath.FLOOR_REFRESH_FRAMES,
    private val bias: Double = DenoiseMath.FLOOR_BIAS,
) {
    private val buffer = Array(windowFrames) { FloatArray(nBins) }
    private var index = 0
    private var filled = 0
    private var count = 0
    private var estimate = DoubleArray(nBins)

    fun push(magnitude: DoubleArray): DoubleArray {
        val median = DenoiseMath.spectralFloor(magnitude)
        val row = buffer[index]
        for (i in 0 until nBins) row[i] = median[i].toFloat()
        index = (index + 1) % windowFrames
        filled = minOf(filled + 1, windowFrames)
        count += 1
        if (count == 1 || count % refreshFrames == 0) {
            val next = DoubleArray(nBins) { bin ->
                var minValue = Double.MAX_VALUE
                for (frame in 0 until filled) {
                    val value = buffer[frame][bin].toDouble()
                    if (value < minValue) minValue = value
                }
                minValue * bias
            }
            estimate = next
        }
        return estimate
    }

    fun reset() {
        for (row in buffer) row.fill(0f)
        index = 0
        filled = 0
        count = 0
        estimate = DoubleArray(nBins)
    }
}
