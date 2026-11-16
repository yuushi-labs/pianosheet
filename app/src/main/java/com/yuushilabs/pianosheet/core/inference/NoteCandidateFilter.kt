package com.yuushilabs.pianosheet.core.inference

import com.yuushilabs.pianosheet.domain.live.Detection
import kotlin.math.abs

/**
 * Drops note candidates that a piano cannot have produced (spec §5.1, §5.4).
 *
 * Basic Pitch reports every semitone it can hear plus everything its melodia
 * pass finds inside a ringing chord, so one take of ordinary playing came back
 * with 287 notes — high octaves nobody pressed, 40 ms blips, and overtones of a
 * strong bass note.  The rules below only remove candidates that fail a physical
 * test, never a musical one:
 *
 *  - **trigger**: a key strike has a high onset activation.  A value under
 *    [minOnsetStrength] is the decay tail of something already counted.
 *  - **length**: shorter than [minDurationS] is a click, not a note.
 *  - **high register** (MIDI >= [highPitchMidi]): the model has one semitone per
 *    key where the harmonics of the low register also land, so above C6 it needs
 *    a clear onset, a real length and some amplitude to be believed.
 *  - **overtone ghost**: an octave / twelfth / double octave above a *stronger*
 *    note that starts within [ghostWindowS] and carries less than
 *    [ghostConfidenceRatio] of its amplitude is the upper partial of that note,
 *    not a second key.
 *
 * Every rule is switchable, and the counts per rule are returned so the analysis
 * screen (and the tests) can say what was removed instead of silently hiding it.
 */
object NoteCandidateFilter {

    data class Settings(
        val minOnsetStrength: Float = 0.20f,
        val minDurationS: Double = 0.08,
        val highPitchMidi: Int = 84,
        val highPitchMinOnset: Float = 0.40f,
        val highPitchMinDurationS: Double = 0.10,
        val highPitchMinConfidence: Float = 0.25f,
        val ghostWindowS: Double = 0.06,
        val ghostConfidenceRatio: Float = 0.55f,
        val ghostFundamentalMinConfidence: Float = 0.35f,
        /** Intervals above the fundamental that are treated as one of its partials. */
        val ghostIntervals: List<Int> = listOf(12, 19, 24, 28, 31, 36),
    )

    data class Result(
        val kept: List<Detection>,
        val droppedWeakOnset: Int = 0,
        val droppedTooShort: Int = 0,
        val droppedHighRegister: Int = 0,
        val droppedOvertone: Int = 0,
    ) {
        val droppedTotal: Int
            get() = droppedWeakOnset + droppedTooShort + droppedHighRegister + droppedOvertone
    }

    fun filter(
        detections: List<Detection>,
        settings: Settings = Settings(),
    ): Result {
        var weak = 0
        var short = 0
        var high = 0
        var overtone = 0
        val kept = ArrayList<Detection>(detections.size)
        for (detection in detections) {
            val duration = detection.endS - detection.onsetS
            val onsetStrength = detection.onsetStrength
            if (onsetStrength != null && onsetStrength < settings.minOnsetStrength) {
                weak += 1
                continue
            }
            if (duration < settings.minDurationS) {
                short += 1
                continue
            }
            if (detection.pitchMidi >= settings.highPitchMidi) {
                val strongEnough = (onsetStrength ?: 1f) >= settings.highPitchMinOnset
                if (!strongEnough ||
                    duration < settings.highPitchMinDurationS ||
                    detection.confidence < settings.highPitchMinConfidence
                ) {
                    high += 1
                    continue
                }
            }
            if (isOvertoneOf(detection, detections, settings) || isOvertoneOf(detection, kept, settings)) {
                overtone += 1
                continue
            }
            kept.add(detection)
        }
        return Result(kept, weak, short, high, overtone)
    }

    /** True when [detection] is a weak partial of a stronger note starting with it. */
    fun isOvertoneOf(
        detection: Detection,
        candidates: List<Detection>,
        settings: Settings = Settings(),
    ): Boolean {
        // Below C4 an octave played together is far more common than an octave
        // ghost, so the check only looks upwards from there.
        if (detection.pitchMidi < settings.highPitchMidi - 24) return false
        var bestLowerConfidence = 0f
        for (other in candidates) {
            if (other === detection) continue
            if (other.pitchMidi >= detection.pitchMidi) continue
            if (abs(other.onsetS - detection.onsetS) > settings.ghostWindowS) continue
            val interval = detection.pitchMidi - other.pitchMidi
            if (!settings.ghostIntervals.contains(interval)) continue
            if (other.confidence < settings.ghostFundamentalMinConfidence) continue
            if (other.confidence > bestLowerConfidence) bestLowerConfidence = other.confidence
        }
        if (bestLowerConfidence <= 0f) return false
        return detection.confidence < settings.ghostConfidenceRatio * bestLowerConfidence
    }
}
