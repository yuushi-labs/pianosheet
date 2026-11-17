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
    )

    /** What the spectrum says about one note: linear magnitudes, and their ratios. */
    data class Evidence(
        val fundamental: Double,
        val secondPartial: Double,
        val thirdPartial: Double,
    ) {
        val secondRatio: Double get() = if (fundamental > 0.0) secondPartial / fundamental else 0.0
        val thirdRatio: Double get() = if (fundamental > 0.0) thirdPartial / fundamental else 0.0
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

        val peaks = Array(targets.size) { DoubleArray(3) }
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
            }
        }

        val evidence = arrayOfNulls<Evidence>(notes.size)
        for ((position, target) in targets.withIndex()) {
            val peak = peaks[position]
            evidence[target.index] = Evidence(peak[0], peak[1], peak[2])
        }

        val dropped = ArrayList<NoteEvent>()
        val droppedEvidence = ArrayList<Evidence>(4)
        val kept = ArrayList<NoteEvent>(notes.size)
        for ((index, note) in notes.withIndex()) {
            val ev = evidence[index]
            if (ev == null) {
                kept.add(note)
                continue
            }
            if (ev.secondRatio < settings.maxSecondPartialRatio &&
                ev.thirdRatio < settings.maxThirdPartialRatio
            ) {
                dropped.add(note)
                droppedEvidence.add(ev)
            } else {
                kept.add(note)
            }
        }
        kept.forEachIndexed { position, note -> note.sequence = position }
        return Result(kept, dropped, targets.size, droppedEvidence)
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

    /** Band ranges are cached per (pitch, partial): they do not depend on the frame. */
    private fun HashMap<Long, IntRange>.bandOf(
        pitchMidi: Int,
        partial: Int,
        f0: Double,
        settings: Settings,
        binHz: Double,
        nBins: Int,
    ): IntRange {
        val key = pitchMidi.toLong() * 8L + partial
        return getOrPut(key) { band(partial * f0, settings.bandTolerance, binHz, nBins) }
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
