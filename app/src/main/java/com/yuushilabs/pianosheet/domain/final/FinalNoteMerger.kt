package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.live.Detection
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.NoteState
import kotlin.math.abs

/**
 * Merges the note candidates of overlapping analysis windows (spec §5.1).
 *
 * Each 2 s model window reports every note it can hear, so a note played between
 * two window starts is reported twice with slightly different frame quantisation.
 * Keeping the best of the two (highest onset strength, longest end) makes the
 * final note list as long as the performance, not as long as the window plan.
 */
object FinalNoteMerger {

    data class WindowResult(val windowStartS: Double, val windowEndS: Double, val detections: List<Detection>)

    data class MergeResult(
        val notes: List<NoteEvent>,
        /** Notes removed because they were a weak octave/twelfth above a stronger note. */
        val overtoneDropped: Int,
        /** Notes removed because they lasted less than [MIN_DURATION_MS]. */
        val shortDropped: Int,
    )

    /** A note shorter than this is a click of the model, not a key. */
    const val MIN_DURATION_MS = 80.0

    /**
     * A short note also has to arrive with a real attack.  The decoder's minimum
     * length was lowered to 6 frames (70 ms) so 32nd notes and trills survive, but
     * that also let noise blips through: on the noisy fixtures (T-014 chords,
     * T-019 quiet notes, T-020 pedal) notes of 80–120 ms with a weak onset were
     * counted as keys and cost up to 0.12 F1.  Requiring onset strength ≥ 0.45
     * for anything shorter than 120 ms brought all three back to (or above) their
     * previous scores while leaving the PianoVAM notes untouched.
     */
    const val SHORT_NOTE_MS = 120.0
    const val SHORT_NOTE_MIN_ONSET_STRENGTH = 0.45f

    /** Same intervals as the detector-side filter: one note and its upper partials. */
    val OVERTONE_INTERVALS = listOf(12, 19, 24, 28, 31, 36)
    const val OVERTONE_WINDOW_MS = 60.0
    const val OVERTONE_CONFIDENCE_RATIO = 0.55

    fun merge(
        windows: List<WindowResult>,
        toleranceMs: Double = 80.0,
        edgeGuardS: Double = 0.02,
    ): List<NoteEvent> = mergeDetailed(windows, toleranceMs, edgeGuardS).notes

    fun mergeDetailed(
        windows: List<WindowResult>,
        toleranceMs: Double = 80.0,
        edgeGuardS: Double = 0.02,
    ): MergeResult {
        val candidates = ArrayList<Detection>(windows.sumOf { it.detections.size })
        for ((windowIndex, window) in windows.withIndex()) {
            for (detection in window.detections) {
                if (detection.onsetS < 0.0) continue
                // A note that starts at the very left edge of a window that is not
                // the first one was probably cut by the window: the previous window
                // already reported it, so this copy only adds a duplicate.
                if (windowIndex > 0 && detection.onsetS < window.windowStartS + edgeGuardS) continue
                candidates.add(detection)
            }
        }
        val ordered = candidates.sortedWith(compareBy({ it.onsetS }, { it.pitchMidi }))
        val merged = ArrayList<NoteEvent>(ordered.size)
        val indicesByPitch = HashMap<Int, MutableList<Int>>()
        for (detection in ordered) {
            val onsetMs = detection.onsetS * 1000.0
            val recent = indicesByPitch.getOrPut(detection.pitchMidi) { ArrayList(4) }
            var existingIndex = -1
            for (position in recent.indices.reversed()) {
                val index = recent[position]
                val existing = merged[index]
                val existingEnd = existing.onsetMs + (existing.durationMs ?: 0.0)
                // Same detection seen from two windows, quantised a frame or two apart.
                if (abs(existing.onsetMs - onsetMs) <= toleranceMs) {
                    existingIndex = index
                    break
                }
                // A copy that starts inside the first half of the note is the same
                // note too: neighbouring windows often place its start tens of
                // milliseconds apart and the old rule counted it twice.
                if (onsetMs > existing.onsetMs && onsetMs < existing.onsetMs + 0.5 * (existingEnd - existing.onsetMs)) {
                    existingIndex = index
                    break
                }
                // Sorted by onset: once this note ends before the new one starts,
                // every older note does too.
                if (existingEnd <= onsetMs) break
            }
            if (existingIndex < 0) {
                merged.add(
                    NoteEvent(
                        pitchMidi = detection.pitchMidi,
                        onsetMs = onsetMs,
                        durationMs = ((detection.endS - detection.onsetS) * 1000.0).coerceAtLeast(0.0),
                        confidence = detection.confidence,
                        onsetStrength = detection.onsetStrength,
                        state = NoteState.CONFIRMED,
                        detector = "final_analysis",
                    ),
                )
                recent.add(merged.lastIndex)
                while (recent.size > 8) recent.removeAt(0)
            } else {
                val existing = merged[existingIndex]
                val existingEnd = existing.onsetMs + (existing.durationMs ?: 0.0)
                val newEnd = detection.endS * 1000.0
                if (newEnd > existingEnd) {
                    existing.durationMs = newEnd - existing.onsetMs
                }
                if (detection.confidence > existing.confidence) {
                    existing.confidence = detection.confidence
                }
            }
        }
        merged.sortWith(compareBy({ it.onsetMs }, { it.pitchMidi }))
        merged.forEachIndexed { index, note -> note.sequence = index }
        return suppressGhosts(merged)
    }

    /**
     * After the windows have been merged, remove the two kinds of note that are
     * left: blips the model produced once, and notes that are an upper partial of
     * a stronger note starting at the same instant.  Octaves are common in real
     * music, so the overtone case needs *both* a simultaneous stronger lower note
     * and a clearly weaker upper one.
     */
    fun suppressGhosts(notes: List<NoteEvent>): MergeResult {
        var shortDropped = 0
        val longEnough = ArrayList<NoteEvent>(notes.size)
        for (note in notes) {
            val duration = note.durationMs ?: 0.0
            val weakShort = duration < SHORT_NOTE_MS &&
                (note.onsetStrength ?: 1f) < SHORT_NOTE_MIN_ONSET_STRENGTH
            if (duration < MIN_DURATION_MS || weakShort) {
                shortDropped += 1
                continue
            }
            longEnough.add(note)
        }

        var overtoneDropped = 0
        val kept = ArrayList<NoteEvent>(longEnough.size)
        for (note in longEnough) {
            val ghost = longEnough.any { lower ->
                lower !== note &&
                    lower.pitchMidi < note.pitchMidi &&
                    abs(lower.onsetMs - note.onsetMs) <= OVERTONE_WINDOW_MS &&
                    (note.pitchMidi - lower.pitchMidi) in OVERTONE_INTERVALS &&
                    lower.confidence >= 0.35f &&
                    note.confidence < OVERTONE_CONFIDENCE_RATIO * lower.confidence
            }
            if (ghost) {
                overtoneDropped += 1
            } else {
                kept.add(note)
            }
        }
        kept.forEachIndexed { index, note -> note.sequence = index }
        return MergeResult(kept, overtoneDropped, shortDropped)
    }
}
