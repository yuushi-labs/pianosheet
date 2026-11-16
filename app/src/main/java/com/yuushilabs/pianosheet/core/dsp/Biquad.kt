package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A second-order IIR section (RBJ cookbook form, direct form II transposed).
 *
 * The coefficients are normalised by a0 when the section is designed, so the
 * processing loop only needs b0/b1/b2 and a1/a2.  State (`z1`, `z2`) is kept
 * across calls, which is what lets the live path filter chunk after chunk
 * without a discontinuity at the chunk boundary.
 */
class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(input: DoubleArray, count: Int = input.size): DoubleArray {
        val out = DoubleArray(count)
        processInto(input, out, count)
        return out
    }

    fun processInPlace(samples: DoubleArray, count: Int = samples.size) {
        processInto(samples, samples, count)
    }

    fun processInto(input: DoubleArray, output: DoubleArray, count: Int = input.size) {
        var s1 = z1
        var s2 = z2
        for (n in 0 until count) {
            val x = input[n]
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            output[n] = y
        }
        z1 = s1
        z2 = s2
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    /** |H(e^{jw})| at [freqHz], used by tests and the high-pass response check. */
    fun magnitudeAt(freqHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val c1 = cos(w)
        val s1 = -sin(w)
        val c2 = cos(2.0 * w)
        val s2 = -sin(2.0 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = b1 * s1 + b2 * s2
        val denRe = 1.0 + a1 * c1 + a2 * c2
        val denIm = a1 * s1 + a2 * s2
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        return if (den == 0.0) 0.0 else num / den
    }

    fun magnitudeDbAt(freqHz: Double, sampleRate: Double): Double {
        val magnitude = magnitudeAt(freqHz, sampleRate).coerceAtLeast(1e-12)
        return 20.0 * kotlin.math.log10(magnitude)
    }
}

object BiquadDesign {
    const val BUTTERWORTH_Q = 0.7071067811865476

    /** 2nd-order high-pass: -3 dB at [cutoffHz], Butterworth shape at Q = 1/sqrt(2). */
    fun highPass(cutoffHz: Double, sampleRate: Double, q: Double = BUTTERWORTH_Q): Biquad {
        require(cutoffHz > 0.0 && cutoffHz < sampleRate / 2.0) { "cutoff out of range: $cutoffHz" }
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Narrow notch at the mains frequency (Q=30 gives ~1.7 Hz at 50 Hz). */
    fun notch(freqHz: Double, sampleRate: Double, q: Double = 30.0): Biquad {
        require(freqHz > 0.0 && freqHz < sampleRate / 2.0) { "notch out of range: $freqHz" }
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(1.0 / a0, -2.0 * cosW0 / a0, 1.0 / a0, -2.0 * cosW0 / a0, (1.0 - alpha) / a0)
    }
}
