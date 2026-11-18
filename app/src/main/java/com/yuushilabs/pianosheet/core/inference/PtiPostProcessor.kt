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
 * plus the three tuning changes measured on PianoVAM and on the user's own
 * takes (`docs/phase3-pti-report.md`, `docs/pedal-length-0.4.8-report.md`):
 *
 *  1. A note's end is pulled back to the point where its key activity has
 *     fallen to 30 % of the note's peak for 5 consecutive frames (50 ms).
 *     The ratio was 40 % in 0.4.1: on the user's take that cut the median note
 *     90 ms short (0.97 s → 0.88 s), which is what the playback sounded like
 *     notes stopping early; 30 % gives back about 60 ms of it.
 *  2. That trim is only applied when the audio agrees the note has stopped.
 *     A string held by the sustain pedal rings on after the key is released and
 *     the frame output (key activity) can dip while it does, so the trim used to
 *     cut pedal notes short — on the user's takes 31–90 notes per take lost
 *     200 ms to 5 s of a sustain the recording still has.  With the audio the
 *     trim keeps the model's own end while the note's partials are still within
 *     [Settings.sustainRingDb] of its peak [Settings.sustainHoldSec] after the
 *     point where it would have cut, and have fallen less than
 *     [Settings.sustainDecayDb] in that time (a pedal-freed string dies slowly,
 *     a damped one is gone).  Trims fall from 31–90 per take to 0–12.
 *  3. A same-pitch re-onset that arrives while the note never stopped is merged
 *     into it instead of becoming a second note.  The model sometimes fires an
 *     onset peak in the middle of a sustain (pedal, legato, a phone recording's
 *     own ring); the check is the model's own release evidence — no offset peak
 *     and only a weak onset peak at the boundary — plus no rise in the note's
 *     partials there.  On PianoVAM this merges 18 of the 41 duplicate notes
 *     while keeping 638 of 658 real repeated notes (a re-strike fires the offset
 *     head and re-excites the string), and without the audio it does nothing, so
 *     the pure reference decode is still reproducible (`PtiPostProcessorTest`).
 *
 * Measured on PianoVAM v1.2 (6 pieces, 2,220 notes) with these defaults:
 * note F1 0.9571 → 0.9596, note+offset F1 0.7614 → 0.7745, chord 0.9113 →
 * 0.9083, un-pedalled offset median +71 → +97 ms (p95 521 → 582 ms).
 * `docs/pedal-length-0.4.8-report.md` has the per-piece tables.
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
        val decayTrimRatio: Float = 0.30f,
        /** ... for this many consecutive frames (5 frames = 50 ms at 100 fps). */
        val decayTrimPersist: Int = 5,
        /** A trim never shortens a note below this, so a real short note survives. */
        val decayTrimMinSec: Double = 0.06,
        /** Turn the measured trim off to get the reference decoder's own ends. */
        val decayTrim: Boolean = true,
        /** Keep the model's end while the note is still ringing this long after the cut. */
        val sustainHoldSec: Double = 0.25,
        /** "Still ringing" means within this many dB of the note's own peak. */
        val sustainRingDb: Double = 20.0,
        /** ... and fallen by less than this between the cut and that later moment. */
        val sustainDecayDb: Double = 12.0,
        /** Merge a same-pitch re-onset that never let the note stop. */
        val mergeSamePitch: Boolean = true,
        /** ... when the gap between the two is at most this long. */
        val mergeGapSec: Double = 0.06,
        /** ... the offset head shows no release at the boundary (peak below this). */
        val mergeOffsetPeak: Float = 0.3f,
        /** ... and the onset head peak there is no louder than this. */
        val mergeOnsetPeak: Float = 0.75f,
        /** ... and the note's partials do not rise by this many dB at the boundary. */
        val mergeReattackDb: Double = 4.0,
        /** `note_detection_with_onset_offset_regress` closes an open note here. */
        val maxNoteFrames: Int = 600,
    ) {
        val frameSec: Double get() = 1.0 / framesPerSecond
    }

    private class Peaks(val binary: BooleanArray, val shift: FloatArray)

    /**
     * Decode one window's model frames into notes on the window's clock.
     *
     * [audio] is the same take at [NoteEnergy.SAMPLE_RATE], on the frames' own
     * clock; it is what tells a pedal-held sustain from a note that really
     * stopped.  Without it (the fidelity test, or a caller that has no audio)
     * the decode is exactly the reference post-processor with the decay trim.
     */
    fun decode(frames: PtiFrames, audio: FloatArray? = null): List<PtiNote> {
        val n = frames.frameCount
        var notes = ArrayList<PtiNote>(256)
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
        val energy = if (audio != null && audio.isNotEmpty()) NoteEnergy(audio) else null
        if (energy != null && settings.mergeSamePitch && settings.mergeGapSec > 0.0) {
            notes = mergePhantomRepeats(notes, frames, energy)
        }
        if (settings.decayTrim && settings.decayTrimPersist > 1) {
            // The ring check is what needs the audio; without it the trim is the
            // measured 0.4.x behaviour (settings.sustainHoldSec = 0 turns it off).
            val trimEnergy = if (energy != null && settings.sustainHoldSec > 0.0) energy else null
            notes = trimEnds(notes, frames, trimEnergy)
        }
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
                    addNote(out, pitch, bgn, fin, onset.shift[bgn], 0f, velocityColumn, n)
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
                    addNote(out, pitch, bgn, fin, onset.shift[bgn], offset.shift[fin], velocityColumn, n)
                    bgn = -1
                    frameDisappear = -1
                    offsetOccur = -1
                } else if (bgn > 0 && (i - bgn >= settings.maxNoteFrames || i == n - 1)) {
                    addNote(out, pitch, bgn, i, onset.shift[bgn], offset.shift[i], velocityColumn, n)
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
        n: Int,
    ) {
        val onsetS = (onsetFrame + onsetShift) * settings.frameSec
        val endS = (offsetFrame + offsetShift) * settings.frameSec
        if (endS <= onsetS) return
        out += PtiNote(
            pitchMidi = pitch,
            onsetFrame = onsetFrame,
            endFrame = offsetFrame.coerceIn(0, max(n - 1, 0)),
            onsetS = onsetS,
            endS = endS,
            velocity = velocityColumn[onsetFrame].coerceIn(0f, 1f),
            trimmed = false,
        )
    }

    /**
     * Folds a phantom same-pitch re-onset into the note it belongs to.
     *
     * A note that is still sounding (pedal down, or a legato line) can collect a
     * second onset peak; the reference then closes the note one frame before it
     * and opens another, so one sustained note is printed as two.  The two are
     * one note when the model shows no release at the boundary — the offset head
     * stays below [Settings.mergeOffsetPeak] (a key really coming up fires it) —
     * the onset there is only [Settings.mergeOnsetPeak] loud, and the note's own
     * partials do not rise by [Settings.mergeReattackDb] dB, which is what a real
     * re-strike does.  Measured on PianoVAM: 638 of 658 real repeated notes are
     * kept, 22 of the 41 duplicates go, F1 0.9571 → 0.9596.
     */
    private fun mergePhantomRepeats(
        notes: List<PtiNote>,
        frames: PtiFrames,
        energy: NoteEnergy,
    ): ArrayList<PtiNote> {
        val out = ArrayList<PtiNote>(notes.size)
        val lastByPitch = HashMap<Int, Int>()
        for (note in notes) {
            val previousIndex = lastByPitch[note.pitchMidi]
            if (previousIndex != null) {
                val previous = out[previousIndex]
                val gap = note.onsetS - previous.endS
                if (gap >= -1e-9 && gap <= settings.mergeGapSec + 1e-9 &&
                    isPhantomReonset(note, frames, energy)
                ) {
                    out[previousIndex] = previous.copy(
                        endFrame = max(previous.endFrame, note.endFrame),
                        endS = max(previous.endS, note.endS),
                        velocity = max(previous.velocity, note.velocity),
                        trimmed = previous.trimmed || note.trimmed,
                    )
                    continue
                }
            }
            lastByPitch[note.pitchMidi] = out.size
            out.add(note)
        }
        return out
    }

    private fun isPhantomReonset(next: PtiNote, frames: PtiFrames, energy: NoteEnergy): Boolean {
        val key = next.pitchMidi - settings.beginNote
        if (key !in 0 until PtiFrames.CLASSES) return false
        val lastFrame = frames.frameCount - 1
        val onsetFrame = next.onsetFrame.coerceIn(0, lastFrame)
        var offsetPeak = 0f
        for (frame in max(onsetFrame - OFFSET_LOOKBACK, 0)..min(onsetFrame + 1, lastFrame)) {
            offsetPeak = max(offsetPeak, frames.offsetReg[frame * PtiFrames.CLASSES + key])
        }
        if (offsetPeak >= settings.mergeOffsetPeak) return false
        var onsetPeak = 0f
        for (frame in max(onsetFrame - ONSET_LOOKBACK, 0)..min(onsetFrame + ONSET_LOOKBACK, lastFrame)) {
            onsetPeak = max(onsetPeak, frames.onsetReg[frame * PtiFrames.CLASSES + key])
        }
        if (onsetPeak > settings.mergeOnsetPeak) return false
        return energy.riseDb(next.pitchMidi, next.onsetS) < settings.mergeReattackDb
    }

    /** Applies the decay trim to every note that the audio agrees has stopped. */
    private fun trimEnds(
        notes: List<PtiNote>,
        frames: PtiFrames,
        energy: NoteEnergy?,
    ): ArrayList<PtiNote> {
        val out = ArrayList<PtiNote>(notes.size)
        for (note in notes) {
            val candidate = trimCandidate(note, frames)
            if (candidate >= note.endS) {
                out.add(note)
                continue
            }
            if (energy != null && stillRinging(note, candidate, energy)) {
                out.add(note)
                continue
            }
            out.add(
                note.copy(
                    endFrame = (candidate / settings.frameSec).roundToInt(),
                    endS = candidate,
                    trimmed = true,
                ),
            )
        }
        return out
    }

    /**
     * A pedal-held string keeps sounding after the key comes up, so the trim must
     * not cut a note the recording still has.  The note counts as ringing if its
     * own partials are still within [Settings.sustainRingDb] of its peak
     * [Settings.sustainHoldSec] after the point where the trim would end it.
     */
    private fun stillRinging(note: PtiNote, candidateS: Double, energy: NoteEnergy): Boolean {
        val peak = energy.peakDb(note.pitchMidi, note.onsetS, note.endS)
        val atCut = energy.partialDb(note.pitchMidi, candidateS)
        val later = min(candidateS + settings.sustainHoldSec, note.endS)
        val level = energy.partialDb(note.pitchMidi, later)
        // A string freed by the pedal dies slowly; a damped one is gone within a
        // fraction of a second, which is the shape the trim is meant to catch.
        return level > peak - settings.sustainRingDb && atCut - level < settings.sustainDecayDb
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
     * 32 notes better and 4 worse, onsets and F1 untouched.  At 30 % the same
     * measurement gives the same median and a p95 of +130 ms against +127 ms.
     */
    private fun trimCandidate(note: PtiNote, frames: PtiFrames): Double {
        val n = frames.frameCount
        val from = note.onsetFrame.coerceIn(0, n - 1)
        val to = note.endFrame.coerceIn(from, n - 1)
        var peak = 0f
        for (frame in from..to) {
            val value = frames.frameAt(frame, note.pitchMidi)
            if (value > peak) peak = value
        }
        val level = settings.decayTrimRatio * peak
        val persist = settings.decayTrimPersist
        var candidate = -1
        var frame = from
        while (frame <= to) {
            if (frames.frameAt(frame, note.pitchMidi) >= level) {
                frame += 1
                continue
            }
            // Count how long the column stays below the level from here.
            var run = 0
            var i = frame
            while (i <= to && frames.frameAt(i, note.pitchMidi) < level) {
                run += 1
                i += 1
            }
            if (run >= persist) {
                candidate = frame
                break
            }
            frame = i
        }
        if (candidate < 0) return note.endS
        val candidateS = max(candidate * settings.frameSec, note.onsetS + settings.decayTrimMinSec)
        return min(note.endS, candidateS)
    }

    private companion object {
        /** How far before a re-onset the offset head may have fired. */
        const val OFFSET_LOOKBACK = 10
        /** Half-width of the onset-peak search around a re-onset. */
        const val ONSET_LOOKBACK = 5
    }
}
