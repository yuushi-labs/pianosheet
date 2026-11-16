package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming sample-rate conversion to the model rate (22.05 kHz).
 *
 * Capture uses the device's native rate when 22.05 kHz is not available, so the
 * live path needs a resampler that carries state across chunks: a windowed-sinc
 * low-pass (63 taps, Hamming) removes what would otherwise alias below 11 kHz,
 * then linear interpolation picks the output instants.  Sample numbering is
 * continuous across chunks.
 */
class StreamingResampler(
    val inputRate: Int,
    val outputRate: Int = 22050,
) {
    private val taps = designLowPass(inputRate, outputRate)
    private val half = taps.size / 2
    private val step = inputRate.toDouble() / outputRate
    private var buffer = DoubleArray(8192)
    private var baseIndex = 0L
    private var size = 0
    private var inputCount = 0L
    private var nextPosition = 0.0
    private var out = FloatArray(0)
    private var outLength = 0

    val enabled: Boolean get() = inputRate != outputRate

    fun process(chunk: FloatArray): FloatArray {
        if (!enabled) return chunk.copyOf()
        if (chunk.isEmpty()) return FloatArray(0)

        append(chunk)

        while (nextPosition + half + 2.0 <= inputCount.toDouble()) {
            val index = floor(nextPosition).toLong()
            val fraction = nextPosition - index
            val first = filterAt(index)
            val second = filterAt(index + 1)
            val value = first * (1.0 - fraction) + second * fraction
            if (outLength + 1 > out.size) out = out.copyOf(maxOf(1024, out.size * 2))
            out[outLength++] = value.toFloat()
            nextPosition += step
        }

        dropConsumed()
        val result = out.copyOf(outLength)
        out = FloatArray(0)
        outLength = 0
        return result
    }

    /** Remaining input samples not yet turned into output (kept for flush). */
    val bufferedInputSamples: Int get() = size

    private fun append(chunk: FloatArray) {
        if (size + chunk.size > buffer.size) {
            val target = maxOf(size + chunk.size, buffer.size * 2)
            val grown = DoubleArray(target)
            buffer.copyInto(grown, 0, 0, size)
            buffer = grown
        }
        for (i in chunk.indices) buffer[size + i] = chunk[i].toDouble()
        size += chunk.size
        inputCount += chunk.size
    }

    private fun sampleAt(index: Long): Double {
        if (index < baseIndex) return 0.0
        val offset = index - baseIndex
        if (offset >= size) return 0.0
        return buffer[offset.toInt()]
    }

    private fun filterAt(index: Long): Double {
        var sum = 0.0
        for (k in -half..half) {
            sum += taps[k + half] * sampleAt(index - k)
        }
        return sum
    }

    private fun dropConsumed() {
        val keepFrom = floor(nextPosition).toLong() - half - 2
        val drop = (keepFrom - baseIndex).coerceAtLeast(0L).coerceAtMost(size.toLong()).toInt()
        if (drop <= 0) return
        buffer.copyInto(buffer, 0, drop, size)
        size -= drop
        baseIndex += drop
    }

    companion object {
        fun designLowPass(inputRate: Int, outputRate: Int, length: Int = 63): DoubleArray {
            require(length % 2 == 1) { "length must be odd" }
            val cutoff = 0.45 * outputRate
            val normalized = cutoff / inputRate // cycles per input sample (Nyquist = 0.5)
            val mid = (length - 1) / 2
            val taps = DoubleArray(length)
            var sum = 0.0
            for (n in 0 until length) {
                val m = n - mid
                val sinc = if (m == 0) 2.0 * normalized else sin(2.0 * PI * normalized * m) / (PI * m)
                val hamming = 0.54 - 0.46 * cos(2.0 * PI * n / (length - 1))
                taps[n] = sinc * hamming
                sum += taps[n]
            }
            if (abs(sum) > 1e-9) {
                for (n in 0 until length) taps[n] /= sum
            }
            return taps
        }

        /** One-shot convenience for tests and fixed-buffer paths. */
        fun resample(input: FloatArray, inputRate: Int, outputRate: Int = 22050): FloatArray {
            if (inputRate == outputRate) return input.copyOf()
            val resampler = StreamingResampler(inputRate, outputRate)
            return resampler.process(input)
        }
    }
}
