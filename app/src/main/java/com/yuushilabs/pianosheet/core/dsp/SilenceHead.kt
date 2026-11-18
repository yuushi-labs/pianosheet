package com.yuushilabs.pianosheet.core.dsp

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where the sound of the take actually starts, in seconds.
 *
 * A recording opens with the room and the microphone settling, and a
 * transcription model reports that settling as notes: the studio recording of
 * Gymnopédie No. 1 used as a test fixture begins with two made-up low notes 23 ms
 * in, 0.8 s before the piano plays a single note.  The first note of the result
 * becomes beat 0 of the score, so such notes printed the wait before the playing
 * as the opening bars (the user: "録音開始してから最初の音がなるまでの間も楽譜に
 * 出てる").
 *
 * The level is measured in 20 ms frames.  The threshold follows the take itself
 * (1 % of its loudest frame, and never below -55 dBFS), so a quiet performance is
 * not mistaken for silence, and a noisy room raises the threshold instead of
 * cutting the music off.  The answer is the start of the first frame that stands
 * above the threshold *and* is followed by sustained sound, so a single click
 * cannot mark the beginning.
 */
object SilenceHead {

    /** Analysis frames are this long; 20 ms is ~3 cycles of a low A on the piano. */
    const val FRAME_SEC = 0.02

    /** Below this the take is treated as silent, whatever its own peak is. */
    const val FLOOR_DB = -55.0

    /** A frame counts as sound at this share of the take's loudest frame. */
    const val RELATIVE_LEVEL = 0.01

    /** How long the sound has to hold after the first loud frame. */
    const val HOLD_SEC = 0.10

    /**
     * @return seconds from the start of [samples] to the first audible moment;
     *   0.0 when the take starts with sound (or is empty / silent throughout).
     */
    fun firstSoundSec(samples: FloatArray, sampleRate: Int): Double {
        if (samples.isEmpty() || sampleRate <= 0) return 0.0
        val frame = (FRAME_SEC * sampleRate).toInt().coerceAtLeast(1)
        val frames = samples.size / frame
        if (frames < 2) return 0.0
        val rms = DoubleArray(frames)
        var loudest = 0.0
        for (index in 0 until frames) {
            var sum = 0.0
            val from = index * frame
            for (offset in 0 until frame) {
                val value = samples[from + offset].toDouble()
                sum += value * value
            }
            rms[index] = sqrt(sum / frame)
            if (rms[index] > loudest) loudest = rms[index]
        }
        val threshold = max(10.0.pow(FLOOR_DB / 20.0), loudest * RELATIVE_LEVEL)
        if (loudest < threshold) return 0.0
        val hold = (HOLD_SEC / FRAME_SEC).toInt().coerceAtLeast(1)
        for (index in 0 until frames) {
            if (rms[index] < threshold) continue
            var above = 0
            var counted = 0
            for (next in index until minOf(frames, index + hold)) {
                counted += 1
                if (rms[next] >= threshold * 0.5) above += 1
            }
            val sustained = counted > 0 && above * 2 >= counted
            if (sustained) return index * FRAME_SEC
        }
        return 0.0
    }
}
