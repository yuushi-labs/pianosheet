package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** A stationary narrow-band tone found in the take. */
data class ToneNotch(
    val frequencyHz: Double,
    /** How far the tone stands above the level between the partials, in dB. */
    val prominenceDb: Double,
    /** Fraction of the analysed frames in which the bin sticks out. */
    val presence: Double,
)

/**
 * Finds a high, perfectly steady whistle in a recording (spec v1.3 §20.3, §20.5).
 *
 * The take that exposed this sounded fine musically but carried a ~3.2 kHz sine
 * that never stopped; Basic Pitch reads it as a high key and the finished score
 * fills up with notes nobody played.  Two properties separate such a tone from
 * music, and both are needed:
 *
 *  - **narrow**: inside one frame the bin stands far above the local median of
 *    its neighbours (a piano partial is one of many, and moves with the pitch);
 *  - **always there**: the same absolute bin is above that local median in most
 *    frames of the take, which a melody only does when it holds one high note
 *    for the whole recording.
 *
 * The frequency is taken from the mean spectrum (parabolic interpolation), so the
 * caller can place a narrow notch instead of guessing.
 */
object ToneNoiseDetector {

    const val DEFAULT_MIN_HZ = 1200.0
    const val DEFAULT_MAX_HZ = 8000.0

    /** A bin counts as tonal in a frame when it is this many times the local median. */
    const val PRESENCE_RATIO = 3.0

    /**
     * Fraction of frames the bin must be tonal in to be called stationary.
     * Deliberately high: a clean recording of dense piano music already has bins
     * that stick out in ~70% of frames (a recurring pitch), and notching those
     * would remove real notes.  Only a tone that never stops reaches 0.8.
     */
    const val PRESENCE_MIN = 0.80

    /** How far above the shoulders the peak must stand (15 dB ≈ 5.6×). */
    const val MIN_PROMINENCE_DB = 15.0
    const val MAX_NOTCHES = 3
    const val MIN_FRAMES = 60
    const val MERGE_HZ = 60.0

    fun detect(
        audio: FloatArray,
        sampleRate: Int = 22050,
        frameSize: Int = 1024,
        hopSize: Int = 256,
        minHz: Double = DEFAULT_MIN_HZ,
        maxHz: Double = DEFAULT_MAX_HZ,
        maxCount: Int = MAX_NOTCHES,
    ): List<ToneNotch> {
        if (audio.size < frameSize || frameSize < 64) return emptyList()
        val nFrames = 1 + (audio.size - frameSize) / hopSize
        if (nFrames < MIN_FRAMES) return emptyList()
        val nBins = frameSize / 2 + 1
        val window = Fft.periodicHann(frameSize)
        val tonalFrames = IntArray(nBins)
        val mean = DoubleArray(nBins)
        val frame = DoubleArray(frameSize)
        val magnitude = DoubleArray(nBins)

        for (f in 0 until nFrames) {
            val start = f * hopSize
            for (i in 0 until frameSize) frame[i] = audio[start + i].toDouble() * window[i]
            val (re, im) = Fft.rfft(frame, frameSize)
            for (b in 0 until nBins) magnitude[b] = sqrt(re[b] * re[b] + im[b] * im[b])
            val floor = DenoiseMath.spectralFloor(magnitude)
            for (b in 0 until nBins) {
                mean[b] += magnitude[b]
                if (magnitude[b] > PRESENCE_RATIO * max(floor[b], 1e-12)) tonalFrames[b] += 1
            }
        }
        for (b in 0 until nBins) mean[b] = mean[b] / nFrames

        return pickPeaks(
            mean = mean,
            presence = DoubleArray(nBins) { tonalFrames[it].toDouble() / nFrames },
            binHz = sampleRate.toDouble() / frameSize,
            nBins = nBins,
            minHz = minHz,
            maxHz = maxHz,
            maxCount = maxCount,
        )
    }

    /**
     * Shared peak picker: a candidate peak of the (mean) spectrum has to be
     * stationary, stand [MIN_PROMINENCE_DB] above the shoulders 4–20 bins away,
     * and be separated from the other wins by [MERGE_HZ].
     */
    internal fun pickPeaks(
        mean: DoubleArray,
        presence: DoubleArray,
        binHz: Double,
        nBins: Int,
        minHz: Double,
        maxHz: Double,
        maxCount: Int,
    ): List<ToneNotch> {
        val found = ArrayList<ToneNotch>()
        for (bin in 2 until nBins - 2) {
            val frequency = bin * binHz
            if (frequency < minHz || frequency > maxHz) continue
            if (presence[bin] < PRESENCE_MIN) continue
            // Local maximum of the mean spectrum.
            if (mean[bin] < mean[bin - 1] || mean[bin] < mean[bin + 1]) continue
            val shoulders = ArrayList<Double>(32)
            for (distance in 4..20) {
                if (bin - distance >= 0) shoulders.add(mean[bin - distance])
                if (bin + distance < nBins) shoulders.add(mean[bin + distance])
            }
            if (shoulders.isEmpty()) continue
            shoulders.sort()
            val shoulder = shoulders[shoulders.size / 2]
            if (shoulder <= 0.0) continue
            val prominence = 20.0 * log10(mean[bin] / shoulder)
            if (prominence < MIN_PROMINENCE_DB) continue
            val left = mean[bin - 1]
            val centre = mean[bin]
            val right = mean[bin + 1]
            val denominator = left - 2.0 * centre + right
            val offset = if (abs(denominator) < 1e-12) 0.0 else 0.5 * (left - right) / denominator
            found.add(
                ToneNotch(
                    frequencyHz = (bin + offset) * binHz,
                    prominenceDb = prominence,
                    presence = presence[bin],
                ),
            )
        }
        found.sortWith(compareByDescending { it.prominenceDb })
        val chosen = ArrayList<ToneNotch>(maxCount)
        for (candidate in found) {
            if (chosen.any { abs(it.frequencyHz - candidate.frequencyHz) <= MERGE_HZ }) continue
            chosen.add(candidate)
            if (chosen.size >= maxCount) break
        }
        return chosen
    }
}

/**
 * Streaming version of [ToneNoiseDetector] for the live path.
 *
 * The live denoiser sees the take chunk by chunk, so the presence of a bin is an
 * exponential moving average and the tone is reported once about half a second
 * of audio agrees.  The estimate keeps improving afterwards, but the audio that
 * follows is what the live staff shows.
 */
class ToneNoiseTracker(
    private val sampleRate: Int = 22050,
    private val frameSize: Int = 1024,
    private val minHz: Double = ToneNoiseDetector.DEFAULT_MIN_HZ,
    private val maxHz: Double = ToneNoiseDetector.DEFAULT_MAX_HZ,
    private val maxCount: Int = ToneNoiseDetector.MAX_NOTCHES,
    private val alpha: Double = 0.02,
    private val reportEveryFrames: Int = 16,
) {
    private val nBins = frameSize / 2 + 1
    private val presence = DoubleArray(nBins)
    private val mean = DoubleArray(nBins)
    private var frames = 0
    private var reported: List<ToneNotch> = emptyList()

    /** Feeds one frame's magnitude spectrum; returns the notches currently believed. */
    fun push(magnitude: DoubleArray): List<ToneNotch> {
        if (magnitude.size != nBins) return reported
        frames += 1
        val floor = DenoiseMath.spectralFloor(magnitude)
        for (b in 0 until nBins) {
            val present = if (magnitude[b] > ToneNoiseDetector.PRESENCE_RATIO * max(floor[b], 1e-12)) 1.0 else 0.0
            presence[b] = (1.0 - alpha) * presence[b] + alpha * present
            mean[b] = if (frames == 1) magnitude[b] else 0.98 * mean[b] + 0.02 * magnitude[b]
        }
        if (frames < 40 || frames % reportEveryFrames != 0) return reported
        reported = ToneNoiseDetector.pickPeaks(
            mean = mean,
            presence = presence,
            binHz = sampleRate.toDouble() / frameSize,
            nBins = nBins,
            minHz = minHz,
            maxHz = maxHz,
            maxCount = maxCount,
        )
        return reported
    }

    fun reset() {
        presence.fill(0.0)
        mean.fill(0.0)
        frames = 0
        reported = emptyList()
    }
}
