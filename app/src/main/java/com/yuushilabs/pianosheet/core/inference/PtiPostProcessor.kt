package com.yuushilabs.pianosheet.core.inference

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One note as PTI sees it, on the window's own clock. */
data class PtiNote(
    val pitchMidi: Int,
    /** Frame index of the onset peak (100 fps). */
    val onsetFrame: Int,
    /** Frame index of the end, after any decay trim. */
    val endFrame: Int,
    val onsetS: Double,
    val endS: Double,
    /** Normalised 0..1 key velocity. */
    val velocity: Float,
    /** True when the decay trim moved the end earlier than the model regressed it. */
    val trimmed: Boolean,
) {
    val durationS: Double get() = endS - onsetS
}

/**
 * Kotlin port of ByteDance PTI's `RegressionPostProcessor`
 * (`piano_transcription_inference.utilities`, Kong et al., IEEE/ACM TASLP 2021),
 * plus the one tuning change measured on PianoVAM
 * (`docs/phase3-pti-report.md`): a note's end is pulled back to the point where
 * its key activity has fallen to 40 % of the note's peak for 5 consecutive
 * frames (50 ms).
 *
 * The port is checked against the Python reference by `PtiPostProcessorTest`,
 * which decodes the model frames the Python benchmark exported and compares the
 * notes one by one.
 *
 * The reference's arithmetic is kept as it is, including its quirks:
 *  - a regression peak needs the column to rise monotonically for `neighbour`
 *    frames on both sides, and the sub-frame shift interpolates the neighbours;
 *  - a new onset closes the previous note at the frame before it, with a zero
 *    offset shift;
 *  - an offset peak is only used when it is at least as close to the onset as
 *    the frame-output disappearance, otherwise the disappearance wins;
 *  - a note still open after 600 frames (6 s) is closed there;
 *  - the reference tests its onset marker for truthiness, so a note whose onset
 *    lands on frame 0 never closes. That is reproduced rather than fixed,
 *    because frame 0 is the first 10 ms of a window and the Python reference
 *    behaves the same way.
 */
class PtiPostProcessor(
    private val settings: Settings = Settings(),
) {

    data class Settings(
        val onsetThreshold: Float = 0.3f,
        val offsetThreshold: Float = 0.3f,
        val frameThreshold: Float = 0.1f,
        val onsetNeighbour: Int = 2,
        val offsetNeighbour: Int = 4,
        val framesPerSecond: Int = 100,
        val beginNote: Int = PtiFrames.BEGIN_NOTE,
        val velocityScale: Int = 128,
        /** Pull a note's end to where the frame output stays below this share of its peak. */
        val decayTrimRatio: Float = 0.4f,
        /** ... for this many consecutive frames (5 frames = 50 ms at 100 fps). */
        val decayTrimPersist: Int = 5,
        /** A trim never shortens a note below this, so a real short note survives. */
        val decayTrimMinSec: Double = 0.06,
        /** Turn the measured trim off to get the reference decoder's own ends. */
        val decayTrim: Boolean = true,
        /** `note_detection_with_onset_offset_regress` closes an open note here. */
        val maxNoteFrames: Int = 600,
    ) {
        val frameSec: Double get() = 1.0 / framesPerSecond
    }

    private class Peaks(val binary: BooleanArray, val shift: FloatArray)

    /** Decode one window's model frames into notes on the window's clock. */
    fun decode(frames: PtiFrames): List<PtiNote> {
        val n = frames.frameCount
        val notes = ArrayList<PtiNote>(256)
        val column = FloatArray(n)
        val frameColumn = FloatArray(n)
        val velocityColumn = FloatArray(n)
        for (key in 0 until PtiFrames.CLASSES) {
            extract(frames.onsetReg, key, n, column)
            val onset = binarize(column, n, settings.onsetThreshold, settings.onsetNeighbour)
            extract(frames.offsetReg, key, n, column)
            val offset = binarize(column, n, settings.offsetThreshold, settings.offsetNeighbour)
            extract(frames.frame, key, n, frameColumn)
            extract(frames.velocity, key, n, velocityColumn)
            collectNotes(key + settings.beginNote, onset, offset, frameColumn, velocityColumn, n, notes)
        }
        notes.sortWith(compareBy({ it.onsetS }, { it.pitchMidi }))
        return notes
    }

    /** `reg[frame * 88 + key]` → a contiguous column, for the same walk as the reference. */
    private fun extract(reg: FloatArray, key: Int, n: Int, out: FloatArray) {
        var index = key
        for (frame in 0 until n) {
            out[frame] = reg[index]
            index += PtiFrames.CLASSES
        }
    }

    /** `get_binarized_output_from_regression` for one key column. */
    private fun binarize(reg: FloatArray, n: Int, threshold: Float, neighbour: Int): Peaks {
        val binary = BooleanArray(n)
        val shift = FloatArray(n)
        if (neighbour < 1 || n <= neighbour * 2) return Peaks(binary, shift)
        for (frame in neighbour until n - neighbour) {
            val value = reg[frame]
            if (value <= threshold) continue
            if (!isMonotonicNeighbour(reg, frame, neighbour)) continue
            binary[frame] = true
            val before = reg[frame - 1]
            val after = reg[frame + 1]
            shift[frame] = if (before > after) {
                (after - before) / (value - after) / 2f
            } else {
                (after - before) / (value - before) / 2f
            }
        }
        return Peaks(binary, shift)
    }

    private fun isMonotonicNeighbour(reg: FloatArray, frame: Int, neighbour: Int): Boolean {
        for (i in 0 until neighbour) {
            if (reg[frame - i] < reg[frame - i - 1]) return false
            if (reg[frame + i] < reg[frame + i + 1]) return false
        }
        return true
    }

    /**
     * `note_detection_with_onset_offset_regress`.  `-1` stands for the
     * reference's `None`; `0` is treated as "no note in progress" exactly as
     * the reference's truthiness test does.
     */
    private fun collectNotes(
        pitch: Int,
        onset: Peaks,
        offset: Peaks,
        frameColumn: FloatArray,
        velocityColumn: FloatArray,
        n: Int,
        out: MutableList<PtiNote>,
    ) {
        var bgn = -1
        var frameDisappear = -1
        var offsetOccur = -1
        for (i in 0 until n) {
            if (onset.binary[i]) {
                if (bgn > 0) {
                    val fin = max(i - 1, 0)
                    addNote(out, pitch, bgn, fin, onset.shift[bgn], 0f, velocityColumn, frameColumn, n)
                    frameDisappear = -1
                    offsetOccur = -1
                }
                bgn = i
            }
            if (bgn > 0 && i > bgn) {
                if (frameColumn[i] <= settings.frameThreshold && frameDisappear < 0) {
                    frameDisappear = i
                }
                if (offset.binary[i] && offsetOccur < 0) {
                    offsetOccur = i
                }
                if (frameDisappear >= 0) {
                    val fin = if (offsetOccur >= 0 && offsetOccur - bgn > frameDisappear - offsetOccur) {
                        offsetOccur
                    } else {
                        frameDisappear
                    }
                    addNote(out, pitch, bgn, fin, onset.shift[bgn], offset.shift[fin], velocityColumn, frameColumn, n)
                    bgn = -1
                    frameDisappear = -1
                    offsetOccur = -1
                } else if (bgn > 0 && (i - bgn >= settings.maxNoteFrames || i == n - 1)) {
                    addNote(out, pitch, bgn, i, onset.shift[bgn], offset.shift[i], velocityColumn, frameColumn, n)
                    bgn = -1
                    frameDisappear = -1
                    offsetOccur = -1
                }
            }
        }
    }

    private fun addNote(
        out: MutableList<PtiNote>,
        pitch: Int,
        onsetFrame: Int,
        offsetFrame: Int,
        onsetShift: Float,
        offsetShift: Float,
        velocityColumn: FloatArray,
        frameColumn: FloatArray,
        n: Int,
    ) {
        val onsetS = (onsetFrame + onsetShift) * settings.frameSec
        var endS = (offsetFrame + offsetShift) * settings.frameSec
        if (endS <= onsetS) return
        var trimmed = false
        if (settings.decayTrim && settings.decayTrimPersist > 1) {
            val trimmedS = trimEnd(frameColumn, n, onsetFrame, offsetFrame, onsetS, endS)
            if (trimmedS < endS) {
                endS = trimmedS
                trimmed = true
            }
        }
        val endFrame = (endS / settings.frameSec).roundToInt()
        out += PtiNote(
            pitchMidi = pitch,
            onsetFrame = onsetFrame,
            endFrame = endFrame,
            onsetS = onsetS,
            endS = endS,
            velocity = velocityColumn[onsetFrame].coerceIn(0f, 1f),
            trimmed = trimmed,
        )
    }

    /**
     * Where the key activity of this note has really stopped.
     *
     * The notes branch of PTI keeps a note alive while the string rings, so a
     * short key release under a long decay comes out much longer than the key
     * was held.  The frame output (key activity) falls before the regressed
     * offset does: the end is the first frame from which the column stays below
     * [Settings.decayTrimRatio] of the note's peak for [Settings.decayTrimPersist]
     * frames.  PianoVAM: median note-end error +102 ms → +58 ms, p95 607 → 508 ms,
     * 32 notes better and 4 worse, onsets and F1 untouched.
     */
    private fun trimEnd(
        frameColumn: FloatArray,
        n: Int,
        onsetFrame: Int,
        offsetFrame: Int,
        onsetS: Double,
        endS: Double,
    ): Double {
        val from = onsetFrame.coerceIn(0, n - 1)
        val to = offsetFrame.coerceIn(from, n - 1)
        var peak = 0f
        for (i in from..to) if (frameColumn[i] > peak) peak = frameColumn[i]
        val level = settings.decayTrimRatio * peak
        val persist = settings.decayTrimPersist
        var candidate = -1
        var i = from
        while (i <= to) {
            if (frameColumn[i] >= level) {
                i += 1
                continue
            }
            // Count how long the column stays below the level from here.
            var run = 0
            var j = i
            while (j <= to && frameColumn[j] < level) {
                run += 1
                j += 1
            }
            if (run >= persist) {
                candidate = i
                break
            }
            i = j
        }
        if (candidate < 0) return endS
        val candidateS = max(candidate * settings.frameSec, onsetS + settings.decayTrimMinSec)
        return min(endS, candidateS)
    }
}
