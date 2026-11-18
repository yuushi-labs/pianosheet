package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.core.dsp.Fft
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 倍音実在チェック（v1.4）— is a high note a real key strike, or one spectral line
 * that belongs to a lower note?
 *
 * A piano key always excites a stack of partials, so a real note carries energy at
 * its own 2f0 and 3f0.  The notes this filter removes are the opposite: one take of
 * ordinary playing came back with 305 notes, of which 48 were a single thin line
 * three octaves above a chord tone (C#4 277.2 Hz × 8 = 2218 Hz reported as C#7),
 * with the 2nd partial at 4 % of the fundamental where a real note has about 27 %.
 * Basic Pitch cannot tell them apart — both are one line on one piano key's
 * semitone — but the spectrum can.
 *
 * For every note at or above C5 (MIDI [Settings.registerGate]) the filter takes the
 * strongest magnitude in a ±[Settings.bandTolerance] band around f0, 2·f0 and 3·f0
 * over the frames of [onset, onset + 200 ms].  When both the 2nd and the 3rd
 * partial stay under [Settings.maxSecondPartialRatio] / [Settings.maxThirdPartialRatio]
 * of the fundamental, the note is a pure line: a ghost.
 *
 * Cost: one 2048-point frame per frame an examined note needs, and only those
 * frames — about 2,000 for a 44 s take, well under a second on a phone.  The model
 * and its weights are untouched (spec §5.1, §20).
 */
object HarmonicRealnessFilter {

    data class Settings(
        /** Only notes from C5 upward are examined; the low register keeps its octaves. */
        val registerGate: Int = 72,
        /** Judgement window after the onset. */
        val windowS: Double = 0.200,
        val frameSize: Int = 2048,
        val hopSize: Int = 256,
        /** Band around each partial that counts as "this partial" (±2 %). */
        val bandTolerance: Double = 0.02,
        val maxSecondPartialRatio: Double = 0.10,
        val maxThirdPartialRatio: Double = 0.05,
        val sampleRate: Int = 22050,
        /**
         * A pure line is only a ghost when a key two or three octaves below it
         * is struck at the same moment: that note owns the 4th/8th partial the
         * line is.  Without the owner test, real high notes whose partials are
         * masked (a fast run, a chord's top voice) were deleted too — on the
         * PianoVAM test split the owner test keeps 0.02 micro F1 that the bare
         * pure-line rule threw away, and still removes every ghost of the noisy
         * take the filter was written for.
         */
        val requireOwnerNote: Boolean = true,
        val ownerSpanMs: Double = 80.0,
        /**
         * Intervals below the note that can own one of its spectral lines.  24 and
         * 36 are the 4th and 8th partial (Basic Pitch's ghost); 12 is the 2nd
         * partial, which is what PTI doubles an octave up on a noisy take, and 19
         * is the 3rd partial (a 12th, and the other line such a take doubles).
         */
        val ownerIntervals: List<Int> = listOf(24, 36),
        /**
         * A pure line is also a ghost when the spectrum itself carries the lower
         * note, even if the model did not report it: energy at f0/2 or f0/4 that
         * is at least this share of the line at f0 means the line *is* that lower
         * note's 2nd or 4th partial.  A real note struck alone has nothing below
         * it, so this costs nothing there.
         *
         * Why it is needed: a sustained line (a pedalled note, a room or speaker
         * resonance around 1.2 kHz) makes the model emit high notes whose lower
         * owner it never reports, and the pure-line test plus the owner test both
         * keep them.  On the user's 40 s take this rule removes 44 of the 85 notes
         * at C6 and above; on PianoVAM's six excerpts it removes no real note
         * (micro F1 0.9566 → 0.9559, three notes).
         */
        val spectralOwnerRatio: Double = 0.0,
    )

    /**
     * The PTI pass (0.4.2).  The model doubles a high note an octave up on a quiet,
     * noisy take (C6 → C7 as one line at the 2nd partial), and the doubled note
     * carries no partials of its own: h2 ≈ 0.02, h3 ≈ 0.005 against 0.4–1.0 for a
     * real key.  PianoVAM's six excerpts keep 0.9561 micro F1 against 0.9571 (one
     * more removed note in the Improvisation), and on the user's take the same rule
     * drops the C7/D7/D#7 lines under real C6/D6/D#6 keys while keeping every real
     * high note.
     */
    val PTI = Settings(
        registerGate = 72,
        maxSecondPartialRatio = 0.05,
        maxThirdPartialRatio = 0.015,
        ownerSpanMs = 60.0,
        ownerIntervals = listOf(12, 19, 24, 36),
        spectralOwnerRatio = 0.25,
    )

    /** What the spectrum says about one note: linear magnitudes, and their ratios. */
    data class Evidence(
        val fundamental: Double,
        val secondPartial: Double,
        val thirdPartial: Double,
        /** Magnitudes an octave and two octaves below the note (the possible owners). */
        val halfFundamental: Double = 0.0,
        val quarterFundamental: Double = 0.0,
    ) {
        val secondRatio: Double get() = if (fundamental > 0.0) secondPartial / fundamental else 0.0
        val thirdRatio: Double get() = if (fundamental > 0.0) thirdPartial / fundamental else 0.0
        val halfRatio: Double get() = if (fundamental > 0.0) halfFundamental / fundamental else 0.0
        val quarterRatio: Double get() = if (fundamental > 0.0) quarterFundamental / fundamental else 0.0
    }

    data class Result(
        val kept: List<NoteEvent>,
        val dropped: List<NoteEvent>,
        /** Notes at or above the register gate that were looked at. */
        val examined: Int,
        /** Evidence of every dropped note, in the same order as [dropped]. */
        val droppedEvidence: List<Evidence> = emptyList(),
    )

    /** Drops the notes whose own partials are missing, and renumbers the rest. */
    fun apply(
        notes: List<NoteEvent>,
        audio: FloatArray,
        settings: Settings = Settings(),
    ): Result {
        if (notes.isEmpty() || audio.isEmpty()) return Result(notes, emptyList(), 0)

        val frameSize = settings.frameSize
        val hop = settings.hopSize
        val nBins = frameSize / 2 + 1
        val binHz = settings.sampleRate.toDouble() / frameSize
        // Frames are centred on their sample position (as the reference STFT does),
        // so the frame that holds a note starts half a frame before its onset.
        val columnCount = 1 + audio.size / hop

        val targets = ArrayList<Target>()
        for ((index, note) in notes.withIndex()) {
            if (note.pitchMidi < settings.registerGate) continue
            val first = frameOf(note.onsetS, settings.sampleRate, hop).coerceIn(0, columnCount - 1)
            val last = max(
                first + 1,
                frameOf(note.onsetS + settings.windowS, settings.sampleRate, hop),
            ).coerceAtMost(columnCount)
            targets.add(Target(index, note.pitchMidi, first, last))
        }
        if (targets.isEmpty()) return Result(notes, emptyList(), 0)

        // Frame -> the notes whose window needs it.  The STFT therefore runs once per
        // needed frame, however many notes share it, and skips the rest of the take.
        val users = HashMap<Int, MutableList<Int>>(targets.size * 8)
        for ((position, target) in targets.withIndex()) {
            for (at in target.firstFrame until target.lastFrame) {
                users.getOrPut(at) { ArrayList(4) }.add(position)
            }
        }

        val peaks = Array(targets.size) { DoubleArray(5) }
        val window = Fft.periodicHann(frameSize)
        val frame = DoubleArray(frameSize)
        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        val magnitude = DoubleArray(nBins)
        val bands = HashMap<Long, IntRange>()

        for (at in users.keys.sorted()) {
            fillFrame(audio, at, hop, frameSize, window, frame)
            frame.copyInto(re, 0, 0, frameSize)
            im.fill(0.0)
            Fft.transform(re, im)
            for (bin in 0 until nBins) {
                magnitude[bin] = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            }
            for (position in users.getValue(at)) {
                val pitch = targets[position].pitchMidi
                val f0 = frequencyOf(pitch)
                val peak = peaks[position]
                peak[0] = max(peak[0], bandMax(magnitude, bands.bandOf(pitch, 1, f0, settings, binHz, nBins)))
                peak[1] = max(peak[1], bandMax(magnitude, bands.bandOf(pitch, 2, f0, settings, binHz, nBins)))
                peak[2] = max(peak[2], bandMax(magnitude, bands.bandOf(pitch, 3, f0, settings, binHz, nBins)))
                if (settings.spectralOwnerRatio > 0.0) {
                    peak[3] = max(peak[3], bandMax(magnitude, bands.bandOf(pitch, 4, f0, settings, binHz, nBins)))
                    peak[4] = max(peak[4], bandMax(magnitude, bands.bandOf(pitch, 5, f0, settings, binHz, nBins)))
                }
            }
        }

        val evidence = arrayOfNulls<Evidence>(notes.size)
        for ((position, target) in targets.withIndex()) {
            val peak = peaks[position]
            evidence[target.index] = Evidence(peak[0], peak[1], peak[2], peak[3], peak[4])
        }

        val dropped = ArrayList<NoteEvent>()
        val droppedEvidence = ArrayList<Evidence>(4)
        val kept = ArrayList<NoteEvent>(notes.size)
        val byPitch = notes.groupBy { it.pitchMidi }
        for ((index, note) in notes.withIndex()) {
            val ev = evidence[index]
            if (ev == null) {
                kept.add(note)
                continue
            }
            if (ev.secondRatio < settings.maxSecondPartialRatio &&
                ev.thirdRatio < settings.maxThirdPartialRatio
            ) {
                val owned = hasOwner(note, byPitch, settings.ownerSpanMs, settings.ownerIntervals) ||
                    hasSpectralOwner(ev, settings)
                if (settings.requireOwnerNote && !owned) {
                    kept.add(note)
                    continue
                }
                dropped.add(note)
                droppedEvidence.add(ev)
            } else {
                kept.add(note)
            }
        }
        kept.forEachIndexed { position, note -> note.sequence = position }
        return Result(kept, dropped, targets.size, droppedEvidence)
    }

    /** True when the spectrum itself carries the note's 2nd/4th-partial owner below it. */
    private fun hasSpectralOwner(evidence: Evidence, settings: Settings): Boolean {
        if (settings.spectralOwnerRatio <= 0.0) return false
        return evidence.halfRatio >= settings.spectralOwnerRatio ||
            evidence.quarterRatio >= settings.spectralOwnerRatio
    }

    /** True when a key at [octaveSpan] octaves below starts with this note. */
    private fun hasOwner(
        note: NoteEvent,
        byPitch: Map<Int, List<NoteEvent>>,
        ownerSpanMs: Double,
        intervals: List<Int>,
    ): Boolean {
        for (span in intervals) {
            val lower = byPitch[note.pitchMidi - span] ?: continue
            for (candidate in lower) {
                if (kotlin.math.abs(candidate.onsetMs - note.onsetMs) <= ownerSpanMs) return true
            }
        }
        return false
    }

    /** Spectrum evidence for one note, in the same units as [apply]. */
    fun evidence(
        pitchMidi: Int,
        onsetS: Double,
        audio: FloatArray,
        settings: Settings = Settings(),
    ): Evidence {
        val frameSize = settings.frameSize
        val hop = settings.hopSize
        val nBins = frameSize / 2 + 1
        val binHz = settings.sampleRate.toDouble() / frameSize
        val columnCount = 1 + audio.size / hop
        val window = Fft.periodicHann(frameSize)
        val frame = DoubleArray(frameSize)
        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        val magnitude = DoubleArray(nBins)
        val f0 = frequencyOf(pitchMidi)
        val first = frameOf(onsetS, settings.sampleRate, hop).coerceIn(0, columnCount - 1)
        val last = max(
            first + 1,
            frameOf(onsetS + settings.windowS, settings.sampleRate, hop),
        ).coerceAtMost(columnCount)
        val peaks = DoubleArray(3)
        for (at in first until last) {
            fillFrame(audio, at, hop, frameSize, window, frame)
            frame.copyInto(re, 0, 0, frameSize)
            im.fill(0.0)
            Fft.transform(re, im)
            for (bin in 0 until nBins) {
                magnitude[bin] = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            }
            peaks[0] = max(peaks[0], bandMax(magnitude, band(f0, settings.bandTolerance, binHz, nBins)))
            peaks[1] = max(peaks[1], bandMax(magnitude, band(2 * f0, settings.bandTolerance, binHz, nBins)))
            peaks[2] = max(peaks[2], bandMax(magnitude, band(3 * f0, settings.bandTolerance, binHz, nBins)))
        }
        return Evidence(peaks[0], peaks[1], peaks[2])
    }

    private class Target(
        val index: Int,
        val pitchMidi: Int,
        val firstFrame: Int,
        val lastFrame: Int,
    )

    private fun frequencyOf(pitchMidi: Int): Double = 440.0 * 2.0.pow((pitchMidi - 69) / 12.0)

    /** Band ranges are cached per (pitch, slot): they do not depend on the frame. */
    private fun HashMap<Long, IntRange>.bandOf(
        pitchMidi: Int,
        slot: Int,
        f0: Double,
        settings: Settings,
        binHz: Double,
        nBins: Int,
    ): IntRange {
        val key = pitchMidi.toLong() * 8L + slot
        return getOrPut(key) { band(partialOf(slot) * f0, settings.bandTolerance, binHz, nBins) }
    }

    /** Slot 1..3 are the note's own partials; 4 and 5 are the candidates below it. */
    private fun partialOf(slot: Int): Double = when (slot) {
        1 -> 1.0
        2 -> 2.0
        3 -> 3.0
        4 -> 0.5
        else -> 0.25
    }
    private fun frameOf(timeS: Double, sampleRate: Int, hop: Int): Int =
        floor(timeS * sampleRate / hop).toInt()

    /** Frame [index] of the centred STFT, windowed, zero-padded at both ends. */
    private fun fillFrame(
        audio: FloatArray,
        index: Int,
        hop: Int,
        frameSize: Int,
        window: DoubleArray,
        out: DoubleArray,
    ) {
        val start = index * hop - frameSize / 2
        for (i in 0 until frameSize) {
            val sample = start + i
            val value = if (sample >= 0 && sample < audio.size) audio[sample].toDouble() else 0.0
            out[i] = value * window[i]
        }
    }

    /** Bins inside [frequency]·(1 ± tolerance); empty when the partial is above Nyquist. */
    private fun band(frequency: Double, tolerance: Double, binHz: Double, nBins: Int): IntRange {
        val low = ceil(frequency * (1.0 - tolerance) / binHz).toInt().coerceIn(0, nBins - 1)
        val high = floor(frequency * (1.0 + tolerance) / binHz).toInt().coerceIn(0, nBins - 1)
        return if (high < low) IntRange.EMPTY else low..high
    }

    private fun bandMax(magnitude: DoubleArray, range: IntRange): Double {
        if (range.isEmpty()) return 0.0
        var peak = 0.0
        for (bin in range) if (magnitude[bin] > peak) peak = magnitude[bin]
        return peak
    }
}
