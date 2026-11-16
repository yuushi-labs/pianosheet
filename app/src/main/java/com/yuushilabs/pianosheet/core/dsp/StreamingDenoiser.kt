package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.max
import kotlin.math.pow

/**
 * Chunk-in / chunk-out denoiser for the live transcription path (spec §20.5).
 *
 * `processChunk` returns only the samples that can no longer change, so a call
 * may return slightly less audio than it was given (never more).  Sample
 * indices keep the input numbering — output sample *n* is input sample *n* —
 * but the newest audio is held back until the windows covering it have been
 * seen, so the wall-clock latency of the stage is [delayMs] (frameSize + hop,
 * ~58 ms at 22.05 kHz).  The caller adds that delay to its display-latency
 * budget; onset times themselves need no correction.
 */
class StreamingDenoiser(private val config: DenoiseConfig = DenoiseConfig()) {

    private val window = Fft.periodicHann(config.frameSize)
    private val windowSquared = DoubleArray(config.frameSize) { window[it] * window[it] }
    private val subtractMask = config.subtractMask()
    private val envFloor = 1e-3 * 1.5

    private val frontFilters: MutableList<Biquad> = ArrayList(2)
    private var pending = DoubleArray(0)
    private var pendingLength = 0
    private var ola = DoubleArray(config.frameSize * 4)
    private var env = DoubleArray(config.frameSize * 4)
    private var base = 0L
    private var frameStart = 0L
    private var emitted = 0L
    private var previousGains = DoubleArray(config.nBins) { 1.0 }
    private var floorTracker = FloorTracker(config.nBins)
    private var toneTracker = ToneNoiseTracker(config.sampleRate, config.frameSize, maxCount = config.toneNotchMaxCount)
    private val activeToneBins = ArrayList<Int>()
    private var out = FloatArray(0)
    private var outLength = 0
    private var fadeLength = 0
    private var fadePosition = 0

    init {
        reset()
    }

    val delaySamples: Int get() = if (config.enabled) config.frameSize + config.hopSize else 0
    val delayMs: Double get() = 1000.0 * delaySamples / config.sampleRate

    fun reset() {
        frontFilters.clear()
        frontFilters.add(BiquadDesign.highPass(config.highpassHz, config.sampleRate.toDouble(), config.highpassQ))
        config.notchHz?.let { notch ->
            frontFilters.add(BiquadDesign.notch(notch, config.sampleRate.toDouble(), config.notchQ))
        }
        pending = DoubleArray(0)
        pendingLength = 0
        ola = DoubleArray(config.frameSize * 4)
        env = DoubleArray(config.frameSize * 4)
        base = 0L
        frameStart = 0L
        emitted = 0L
        previousGains = DoubleArray(config.nBins) { 1.0 }
        floorTracker = FloorTracker(config.nBins)
        toneTracker = ToneNoiseTracker(config.sampleRate, config.frameSize, maxCount = config.toneNotchMaxCount)
        activeToneBins.clear()
        out = FloatArray(0)
        outLength = 0
        fadeLength = Math.round(DenoiseMath.FADE_IN_MS * config.sampleRate / 1000.0).toInt()
        fadePosition = 0
    }

    fun processChunk(chunk: FloatArray): FloatArray {
        if (!config.enabled || chunk.isEmpty()) return chunk.copyOf()

        val filtered = DoubleArray(chunk.size) { chunk[it].toDouble() }
        for (filter in frontFilters) filter.processInPlace(filtered)
        appendPending(filtered)

        while (pendingLength >= config.frameSize) {
            val frame = DoubleArray(config.frameSize) { i -> pending[i] }
            dropPending(config.hopSize)
            processFrame(frame)
            frameStart += config.hopSize
            // Samples below the newest window start are covered by every window
            // that can still change them, so they are final.
            read(frameStart)
        }
        return take()
    }

    /** Samples returned so far (the caller's audio clock for this stage). */
    val available: Long get() = emitted

    private fun appendPending(values: DoubleArray) {
        if (pendingLength + values.size > pending.size) {
            pending = pending.copyOf(maxOf(pendingLength + values.size, maxOf(1024, pending.size * 2)))
        }
        values.copyInto(pending, pendingLength)
        pendingLength += values.size
    }

    private fun dropPending(count: Int) {
        val drop = minOf(count, pendingLength)
        pending.copyInto(pending, 0, drop, pendingLength)
        pendingLength -= drop
    }

    private fun processFrame(frame: DoubleArray) {
        val params = config.strength
        val windowed = DoubleArray(config.frameSize) { frame[it] * window[it] }
        val (specRe, specIm) = Fft.rfft(windowed, config.frameSize)
        val magnitude = DoubleArray(config.nBins) { bin ->
            kotlin.math.sqrt(specRe[bin] * specRe[bin] + specIm[bin] * specIm[bin])
        }
        val noise = floorTracker.push(magnitude)
        // Once about half a second of audio agrees on a stationary tone, the rest
        // of the take is notched there too; the live staff stops receiving ghost
        // high notes from it.
        if (config.toneNotchEnabled) {
            val tones = toneTracker.push(magnitude)
            if (tones.isNotEmpty()) {
                val binHz = config.sampleRate.toDouble() / config.frameSize
                for (tone in tones) {
                    val centre = Math.round(tone.frequencyHz / binHz).toInt()
                    if (activeToneBins.any { kotlin.math.abs(it - centre) <= config.toneNotchHalfWidthBins }) continue
                    activeToneBins.add(centre)
                }
            }
        }
        val target = DenoiseMath.subtractGains(magnitude, noise, params)
        val smoothed = DoubleArray(config.nBins) { bin ->
            params.gainSmoothing * previousGains[bin] + (1.0 - params.gainSmoothing) * target[bin]
        }
        previousGains = smoothed
        val clamped = DoubleArray(config.nBins) { bin -> smoothed[bin].coerceIn(0.0, 1.0) }
        val smoothedAcrossBins = DenoiseMath.smooth3(clamped)
        val bins = toneBins()
        val toneGain = 10.0.pow(config.toneNotchGainDb / 20.0)
        val gains = DoubleArray(config.nBins) { bin ->
            val base = if (subtractMask[bin]) smoothedAcrossBins[bin] else 1.0
            if (bins[bin]) minOf(base, toneGain) else base
        }
        for (bin in 0 until config.nBins) {
            specRe[bin] *= gains[bin]
            specIm[bin] *= gains[bin]
        }
        val cleaned = Fft.irfft(specRe, specIm, config.frameSize)
        writeFrame(cleaned)
    }

    /** Bins covered by whichever stationary tones have been locked so far. */
    private fun toneBins(): BooleanArray {
        val mask = BooleanArray(config.nBins)
        if (!config.toneNotchEnabled) return mask
        for (centre in activeToneBins) {
            for (bin in centre - config.toneNotchHalfWidthBins..centre + config.toneNotchHalfWidthBins) {
                if (bin in 0 until config.nBins) mask[bin] = true
            }
        }
        return mask
    }

    private fun writeFrame(cleaned: DoubleArray) {
        val frameSize = config.frameSize
        var start = (frameStart - base).toInt()
        ensureCapacity(start + frameSize)
        start = (frameStart - base).toInt()
        for (i in 0 until frameSize) {
            ola[start + i] += cleaned[i] * window[i]
            env[start + i] += windowSquared[i]
        }
    }

    private fun ensureCapacity(neededEnd: Int) {
        if (neededEnd <= ola.size) return
        val cut = (emitted - base).toInt().coerceAtLeast(0)
        if (cut > 0) {
            val remaining = ola.size - cut
            ola.copyInto(ola, 0, cut, ola.size)
            env.copyInto(env, 0, cut, env.size)
            java.util.Arrays.fill(ola, remaining, ola.size, 0.0)
            java.util.Arrays.fill(env, remaining, env.size, 0.0)
            base += cut
        }
        val required = (frameStart - base).toInt() + config.frameSize
        if (required > ola.size) {
            val newSize = maxOf(required, ola.size * 2)
            ola = ola.copyOf(newSize)
            env = env.copyOf(newSize)
        }
    }

    private fun read(upto: Long) {
        val start = (emitted - base).toInt()
        val end = (upto - base).toInt()
        if (end <= start) return
        appendOut(end - start)
        for (i in start until end) {
            val envelope = max(env[i], envFloor)
            out[outLength++] = (ola[i] / envelope).toFloat()
            ola[i] = 0.0
            env[i] = 0.0
        }
        emitted = upto
    }

    private fun appendOut(count: Int) {
        if (outLength + count > out.size) {
            out = out.copyOf(maxOf(outLength + count, maxOf(2048, out.size * 2)))
        }
    }

    private fun take(): FloatArray {
        val result = out.copyOf(outLength)
        if (result.isNotEmpty() && fadePosition < fadeLength) {
            val fade = DenoiseMath.fadeIn(result.size, fadeLength - fadePosition)
            for (i in result.indices) result[i] = (result[i] * fade[i]).toFloat()
            fadePosition += result.size
        }
        out = FloatArray(0)
        outLength = 0
        return result
    }
}
