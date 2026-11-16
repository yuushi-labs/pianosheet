package com.yuushilabs.pianosheet.core.audio

import com.yuushilabs.pianosheet.core.dsp.StreamingResampler

/**
 * Whole-buffer sample-rate conversion for the final analysis.
 *
 * The live path streams through [StreamingResampler]; a saved file is processed
 * in one go, so the filter's tail has to be flushed explicitly or the last few
 * milliseconds of the take would be cut off.
 */
object AudioResampler {

    fun resample(samples: FloatArray, inputRate: Int, outputRate: Int = 22050): FloatArray {
        if (inputRate == outputRate || samples.isEmpty()) return samples.copyOf()
        require(inputRate > 0 && outputRate > 0) { "sample rates must be positive" }
        val resampler = StreamingResampler(inputRate, outputRate)
        val head = resampler.process(samples)
        // The 63-tap filter needs `half + 2` future samples; feeding silence
        // pushes the remaining audio out instead of dropping it.
        val flush = resampler.process(FloatArray(FlushSamples))
        val expected = (samples.size.toLong() * outputRate / inputRate).toInt()
        val combined = FloatArray(head.size + flush.size)
        head.copyInto(combined, 0)
        flush.copyInto(combined, head.size)
        return if (combined.size > expected) combined.copyOf(expected) else combined
    }

    private const val FlushSamples = 4096
}
