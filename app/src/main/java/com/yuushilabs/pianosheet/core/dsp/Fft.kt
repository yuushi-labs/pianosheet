package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal radix-2 FFT, enough for the 1024-sample STFT frames on the analysis path.
 *
 * Keeping it here (instead of pulling in a maths library) means the DSP core stays
 * plain Kotlin: the unit tests run on the JVM and the same code runs on the device.
 */
object Fft {
    /** In-place complex FFT. [re] and [im] must have the same power-of-two length. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "re/im length mismatch" }
        require(n > 0 && (n and (n - 1)) == 0) { "length must be a power of two: $n" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val even = start + k
                    val odd = even + len / 2
                    val tRe = re[odd] * curRe - im[odd] * curIm
                    val tIm = re[odd] * curIm + im[odd] * curRe
                    re[odd] = re[even] - tRe
                    im[odd] = im[even] - tIm
                    re[even] += tRe
                    im[even] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }

    fun inverse(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        transform(re, im)
        for (i in 0 until n) {
            re[i] = re[i] / n
            im[i] = -im[i] / n
        }
    }

    /** Real input -> bins 0..n/2 (inclusive). Input is zero-padded to [n]. */
    fun rfft(input: DoubleArray, n: Int): Pair<DoubleArray, DoubleArray> {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val count = minOf(input.size, n)
        input.copyInto(re, 0, 0, count)
        transform(re, im)
        val half = n / 2 + 1
        return Pair(re.copyOf(half), im.copyOf(half))
    }

    /** Bins 0..n/2 -> [n] real samples. */
    fun irfft(re: DoubleArray, im: DoubleArray, n: Int): DoubleArray {
        val half = n / 2 + 1
        val fullRe = DoubleArray(n)
        val fullIm = DoubleArray(n)
        for (k in 0 until half) {
            fullRe[k] = re[k]
            fullIm[k] = im[k]
        }
        for (k in 1 until n / 2) {
            fullRe[n - k] = re[k]
            fullIm[n - k] = -im[k]
        }
        inverse(fullRe, fullIm)
        return fullRe
    }

    /** Periodic Hann window, matching `np.hanning(n + 1)[:-1]`. */
    fun periodicHann(n: Int): DoubleArray = DoubleArray(n) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / n)
    }
}
