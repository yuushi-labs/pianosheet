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

    fun merge(
        windows: List<WindowResult>,
        toleranceMs: Double = 80.0,
        edgeGuardS: Double = 0.02,
    ): List<NoteEvent> {
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
                if (abs(merged[index].onsetMs - onsetMs) <= toleranceMs) {
                    existingIndex = index
                    break
                }
                // Sorted by onset: once the gap is beyond the tolerance, older notes are too.
                if (merged[index].onsetMs < onsetMs - toleranceMs) break
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
        return merged
    }
}
