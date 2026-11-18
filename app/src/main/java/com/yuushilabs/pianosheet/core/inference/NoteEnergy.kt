package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.core.dsp.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How loud one note still is at a given moment, measured on the audio itself.
 *
 * The decoder knows the model's key activity, not the sound: a string held by
 * the sustain pedal keeps ringing long after the key came up, and on a phone
 * recording the model's frame output dips in the middle of such a note.  This
 * class answers "is this pitch still audible here?" by summing the energy of the
 * note's first partials in a short window.
 *
 * Windows are 512 samples (32 ms) at 16 kHz, Hann-windowed, and only the bins
 * around harmonics 1..6 are summed, so a chord's other notes and the room do not
 * decide the answer.  Below roughly C2 the bins cannot separate the partials of
 * one note from its neighbours; the figure is then a level for the low register,
 * which is still what the callers ask for (sounding / not sounding).
 */
internal class NoteEnergy(
    private val audio: FloatArray,
    private val sampleRate: Int = SAMPLE_RATE,
) {

    private val window = WINDOW
    private val hann = DoubleArray(window) { 0.5 - 0.5 * cos(2.0 * PI * it / window) }
    private val samples = DoubleArray(window)
    private val binHz = sampleRate.toDouble() / window

    /** Energy of the note's partials at [timeSec], in dB (1e-12 floor). */
    fun partialDb(pitchMidi: Int, timeSec: Double): Double {
        if (audio.isEmpty()) return SILENCE_DB
        val start = (timeSec * sampleRate).roundToInt() - window / 2
        for (i in 0 until window) {
            val index = start + i
            samples[i] = if (index in audio.indices) audio[index] * hann[i] else 0.0
        }
        val (re, im) = Fft.rfft(samples, window)
        val f0 = 440.0 * Math.pow(2.0, (pitchMidi - 69) / 12.0)
        var energy = 0.0
        for (harmonic in 1..HARMONICS) {
            val f = f0 * harmonic
            if (f > sampleRate / 2.0 - 100.0) break
            val centre = (f / binHz).roundToInt()
            val spread = max(1, (f * 0.015 / binHz).roundToInt())
            for (k in max(1, centre - spread)..min(re.size - 2, centre + spread)) {
                energy += re[k] * re[k] + im[k] * im[k]
            }
        }
        return 10.0 * log10(energy + 1e-12)
    }

    /** Loudest the note gets between [fromSec] and [toSec]. */
    fun peakDb(pitchMidi: Int, fromSec: Double, toSec: Double): Double {
        val from = min(fromSec, toSec)
        val to = max(fromSec, toSec)
        var peak = partialDb(pitchMidi, from)
        var t = from + PEAK_STEP_SEC
        while (t < to) {
            val level = partialDb(pitchMidi, t)
            if (level > peak) peak = level
            t += PEAK_STEP_SEC
        }
        if (to > from) {
            val level = partialDb(pitchMidi, to)
            if (level > peak) peak = level
        }
        return peak
    }

    /**
     * How much the note's own partials rise across [onsetSec]: positive for a
     * re-strike, near zero or negative while a note merely rings on.
     */
    fun riseDb(pitchMidi: Int, onsetSec: Double): Double {
        var before = SILENCE_DB
        for (offset in BEFORE_SEC) {
            val level = partialDb(pitchMidi, onsetSec + offset)
            if (level > before) before = level
        }
        var after = SILENCE_DB
        for (offset in AFTER_SEC) {
            val level = partialDb(pitchMidi, onsetSec + offset)
            if (level > after) after = level
        }
        return after - before
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val WINDOW = 512
        private const val HARMONICS = 6
        private const val SILENCE_DB = -120.0
        private const val PEAK_STEP_SEC = 0.05

        /** Windows just before / after an onset the re-attack test compares. */
        private val BEFORE_SEC = doubleArrayOf(-0.07, -0.045, -0.02)
        private val AFTER_SEC = doubleArrayOf(0.01, 0.035, 0.06)
    }
}
