package com.yuushilabs.pianosheet.core.inference

/** Constants of the Basic Pitch ICASSP-2022 ONNX model (measured in Phase 0). */
object BasicPitchConstants {
    const val SAMPLE_RATE = 22050
    const val FFT_HOP = 256

    /** 2 s at 22.05 kHz minus one hop = the model's input length. */
    const val N_SAMPLES = SAMPLE_RATE * 2 - FFT_HOP // 43844

    const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP // 86
    const val N_FRAMES = ANNOTATIONS_FPS * 2 // 172

    const val N_SEMITONES = 88
    const val MIDI_OFFSET = 21 // A0
    const val CONTOURS_BINS_PER_SEMITONE = 3
    const val N_CONTOUR_BINS = N_SEMITONES * CONTOURS_BINS_PER_SEMITONE // 264
    const val MAX_FREQ_IDX = 87

    const val DEFAULT_ONSET_THRESHOLD = 0.5f
    const val DEFAULT_FRAME_THRESHOLD = 0.3f
    const val DEFAULT_MIN_NOTE_LENGTH_FRAMES = 11 // 127.7 ms

    const val ONNX_INPUT = "serving_default_input_2:0"
    const val ONNX_NOTE = "StatefulPartitionedCall:1"
    const val ONNX_ONSET = "StatefulPartitionedCall:2"
    const val ONNX_CONTOUR = "StatefulPartitionedCall:0"

    val frameSeconds: Double get() = FFT_HOP.toDouble() / SAMPLE_RATE
}

/** A note as the raw model output describes it, before streaming de-duplication. */
data class RawNote(
    val startFrame: Int,
    val endFrame: Int,
    val pitchMidi: Int,
    val amplitude: Float,
    val onsetStrength: Float,
)

/**
 * Kotlin port of Basic Pitch's polyphonic note decoding
 * (`basic_pitch/note_creation.py`, Spotify AB, Apache License 2.0), matching the
 * Phase 0 numpy port so the device and the measurements agree.
 *
 * `onsetStrength` is the model's own onset activation at the note start — a real
 * key strike is high there, a note the melodia pass invents inside a decay tail
 * is ~0.  That is what separates a repeated note from a ringing string.
 */
object BasicPitchDecoder {

    fun decode(
        note: Array<FloatArray>,
        onset: Array<FloatArray>,
        onsetThreshold: Float = BasicPitchConstants.DEFAULT_ONSET_THRESHOLD,
        frameThreshold: Float = BasicPitchConstants.DEFAULT_FRAME_THRESHOLD,
        minNoteLength: Int = BasicPitchConstants.DEFAULT_MIN_NOTE_LENGTH_FRAMES,
        inferOnsets: Boolean = true,
        melodiaTrick: Boolean = true,
        energyTolerance: Int = 11,
    ): List<RawNote> {
        require(note.size == onset.size) { "note/onset frame mismatch" }
        val nFrames = note.size
        if (nFrames == 0) return emptyList()
        val nBins = note[0].size
        require(nBins == BasicPitchConstants.N_SEMITONES) { "expected 88 bins, got $nBins" }

        val frames = Array(nFrames) { t -> DoubleArray(nBins) { b -> note[t][b].toDouble() } }
        var onsets = Array(nFrames) { t -> DoubleArray(nBins) { b -> onset[t][b].toDouble() } }

        if (inferOnsets) onsets = getInferredOnsets(onsets, frames)

        val peakThreshold = Array(nFrames) { DoubleArray(nBins) }
        for (t in 0 until nFrames) {
            for (b in 0 until nBins) {
                val value = onsets[t][b]
                if (value <= 0.0) continue
                val previous = if (t > 0) onsets[t - 1][b] else value
                val next = if (t < nFrames - 1) onsets[t + 1][b] else value
                if (value > previous && value > next) peakThreshold[t][b] = value
            }
        }

        val peaks = ArrayList<IntArray>()
        for (t in 0 until nFrames) {
            for (b in 0 until nBins) {
                if (peakThreshold[t][b] >= onsetThreshold) peaks.add(intArrayOf(t, b))
            }
        }
        peaks.reverse()

        val onsetStrengthMatrix = Array(nFrames) { t -> onsets[t].copyOf() }
        val remaining = Array(nFrames) { t -> frames[t].copyOf() }
        val result = ArrayList<RawNote>()

        fun clearBin(start: Int, end: Int, bin: Int) {
            if (start >= end) return
            for (t in start until end) {
                remaining[t][bin] = 0.0
                if (bin + 1 < nBins) remaining[t][bin + 1] = 0.0
                if (bin > 0) remaining[t][bin - 1] = 0.0
            }
        }

        fun meanFrame(start: Int, end: Int, bin: Int): Float {
            if (end <= start) return 0f
            var sum = 0.0
            for (t in start until end) sum += frames[t][bin]
            return (sum / (end - start)).toFloat()
        }

        for (peak in peaks) {
            val start = peak[0]
            val bin = peak[1]
            if (start >= nFrames - 1) continue
            var i = start + 1
            var gap = 0
            while (i < nFrames - 1 && gap < energyTolerance) {
                if (remaining[i][bin] < frameThreshold) gap += 1 else gap = 0
                i += 1
            }
            i -= gap
            if (i - start <= minNoteLength) continue
            clearBin(start, i, bin)
            result.add(
                RawNote(
                    startFrame = start,
                    endFrame = i,
                    pitchMidi = bin + BasicPitchConstants.MIDI_OFFSET,
                    amplitude = meanFrame(start, i, bin),
                    onsetStrength = onsetStrengthMatrix[start][bin].toFloat(),
                ),
            )
        }

        if (melodiaTrick) {
            while (true) {
                var best = 0.0
                var bestFrame = -1
                var bestBin = -1
                for (t in 0 until nFrames) {
                    val row = remaining[t]
                    for (b in 0 until nBins) {
                        if (row[b] > best) {
                            best = row[b]
                            bestFrame = t
                            bestBin = b
                        }
                    }
                }
                if (bestFrame < 0 || best <= frameThreshold) break
                remaining[bestFrame][bestBin] = 0.0
                val bin = bestBin

                var i = bestFrame + 1
                var gap = 0
                while (i < nFrames - 1 && gap < energyTolerance) {
                    if (remaining[i][bin] < frameThreshold) gap += 1 else gap = 0
                    remaining[i][bin] = 0.0
                    if (bin + 1 < nBins) remaining[i][bin + 1] = 0.0
                    if (bin > 0) remaining[i][bin - 1] = 0.0
                    i += 1
                }
                val end = i - 1 - gap

                i = bestFrame - 1
                gap = 0
                while (i > 0 && gap < energyTolerance) {
                    if (remaining[i][bin] < frameThreshold) gap += 1 else gap = 0
                    remaining[i][bin] = 0.0
                    if (bin + 1 < nBins) remaining[i][bin + 1] = 0.0
                    if (bin > 0) remaining[i][bin - 1] = 0.0
                    i -= 1
                }
                val start = i + 1 + gap

                if (start < 0 || end >= nFrames) continue
                if (end - start <= minNoteLength) continue
                result.add(
                    RawNote(
                        startFrame = start,
                        endFrame = end,
                        pitchMidi = bin + BasicPitchConstants.MIDI_OFFSET,
                        amplitude = meanFrame(start, end, bin),
                        onsetStrength = onsetStrengthMatrix[start][bin].toFloat(),
                    ),
                )
            }
        }

        return result
    }

    /**
     * Basic Pitch's "infer onsets" step: where the note activation rises, treat
     * that as an onset so a note whose onset head is weak is still found.
     */
    fun getInferredOnsets(
        onsets: Array<DoubleArray>,
        frames: Array<DoubleArray>,
        nDiff: Int = 2,
    ): Array<DoubleArray> {
        val nFrames = frames.size
        if (nFrames == 0) return onsets
        val nBins = frames[0].size
        val frameDiff = Array(nFrames) { DoubleArray(nBins) }

        for (n in 1..nDiff) {
            for (t in 0 until nFrames) {
                for (b in 0 until nBins) {
                    val current = frames[t][b]
                    val earlier = if (t - n >= 0) frames[t - n][b] else 0.0
                    val diff = current - earlier
                    if (n == 1 || diff < frameDiff[t][b]) frameDiff[t][b] = diff
                }
            }
        }

        var scale = 0.0
        for (t in 0 until nFrames) {
            for (b in 0 until nBins) {
                if (frameDiff[t][b] < 0.0) frameDiff[t][b] = 0.0
                if (frameDiff[t][b] > scale) scale = frameDiff[t][b]
            }
        }
        for (t in 0 until nDiff.coerceAtMost(nFrames)) {
            frameDiff[t].fill(0.0)
        }
        var maxOnset = 0.0
        for (t in 0 until nFrames) {
            for (b in 0 until nBins) if (onsets[t][b] > maxOnset) maxOnset = onsets[t][b]
        }
        if (scale > 0.0) {
            for (t in 0 until nFrames) {
                for (b in 0 until nBins) frameDiff[t][b] = maxOnset * frameDiff[t][b] / scale
            }
        }
        return Array(nFrames) { t ->
            DoubleArray(nBins) { b -> maxOf(onsets[t][b], frameDiff[t][b]) }
        }
    }
}
