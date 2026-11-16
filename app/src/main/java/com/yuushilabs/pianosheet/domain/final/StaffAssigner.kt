package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.model.Staff
import kotlin.math.abs

/**
 * Right hand / left hand split into the treble and bass staff (spec §5.5).
 *
 * A fixed MIDI threshold is exactly what the spec forbids, so this does two
 * things instead:
 *  - it looks for the *gap* in the pitch distribution to choose the split point
 *    (a pianist who never crosses the middle leaves a wide empty band), and
 *  - it keeps melodic continuity: a note near the split point stays on the staff
 *    its neighbouring notes are already using, which is how a melody that dips
 *    below middle C stays in the right hand.
 */
object StaffAssigner {

    data class Note(
        val key: String,
        val pitch: Int,
        val startBeat: Double,
        val durationBeats: Double,
        val velocity: Float = 1f,
    )

    data class Assignment(val staffByKey: Map<String, Staff>, val splitPitch: Int)

    const val DEFAULT_SPLIT = 60

    /** Notes at or above this are normally treble, below it normally bass. */
    fun chooseSplit(pitches: List<Int>): Int {
        val usable = pitches.filter { it in 0..127 }
        if (usable.isEmpty()) return DEFAULT_SPLIT
        val histogram = IntArray(128)
        usable.forEach { histogram[it] += 1 }
        var bestSplit = DEFAULT_SPLIT
        var bestScore = Double.NEGATIVE_INFINITY
        for (split in 48..72) {
            var emptyBand = 0
            for (pitch in split - 4 until split + 4) {
                if (pitch in 0..127 && histogram[pitch] == 0) emptyBand += 1
            }
            var lowCount = 0
            var highCount = 0
            for (pitch in 0 until split) lowCount += histogram[pitch]
            for (pitch in split until 128) highCount += histogram[pitch]
            // A clean gap helps, and both hands should exist; closeness to middle C
            // is the tie breaker when the player uses one register only.
            val balance = if (lowCount == 0 || highCount == 0) 0.0 else 1.0
            val score = emptyBand * 1.0 + balance * 3.0 - abs(split - DEFAULT_SPLIT) * 0.05
            if (score > bestScore + 1e-9) {
                bestScore = score
                bestSplit = split
            }
        }
        return bestSplit
    }

    fun assign(notes: List<Note>, splitPitch: Int = chooseSplit(notes.map { it.pitch })): Assignment {
        val ordered = notes.sortedWith(compareBy({ it.startBeat }, { it.pitch }))
        val result = LinkedHashMap<String, Staff>(ordered.size)
        var lastTreble: Note? = null
        var lastBass: Note? = null

        var index = 0
        while (index < ordered.size) {
            val groupStart = ordered[index].startBeat
            val group = ArrayList<Note>()
            while (index < ordered.size && ordered[index].startBeat - groupStart <= ONSET_GROUP_BEATS) {
                group.add(ordered[index])
                index += 1
            }
            val lowNotes = group.filter { it.pitch < splitPitch }
            val highNotes = group.filter { it.pitch >= splitPitch }

            if (lowNotes.isNotEmpty() && highNotes.isNotEmpty()) {
                // A wide chord really is two hands.
                lowNotes.forEach { result[it.key] = Staff.BASS }
                highNotes.forEach { result[it.key] = Staff.TREBLE }
                lastBass = lowNotes.maxByOrNull { it.pitch }
                lastTreble = highNotes.minByOrNull { it.pitch }
                continue
            }

            val mean = group.map { it.pitch }.average()
            val staff = if (mean >= splitPitch + 2.5) {
                Staff.TREBLE
            } else if (mean <= splitPitch - 2.5) {
                Staff.BASS
            } else {
                // Ambiguous register: follow the hand that was just playing here.
                val trebleCost = cost(mean, Staff.TREBLE, lastTreble, groupStart)
                val bassCost = cost(mean, Staff.BASS, lastBass, groupStart)
                if (trebleCost <= bassCost) Staff.TREBLE else Staff.BASS
            }
            group.forEach { result[it.key] = staff }
            if (staff == Staff.TREBLE) lastTreble = group.last() else lastBass = group.last()
        }
        return Assignment(result, splitPitch)
    }

    private fun cost(mean: Double, staff: Staff, last: Note?, groupStart: Double): Double {
        val center = if (staff == Staff.TREBLE) TREBLE_CENTER else BASS_CENTER
        val register = abs(mean - center) * 0.25
        val continuity = if (last != null && groupStart - last.startBeat <= CONTINUITY_BEATS) {
            abs(mean - last.pitch) * 0.6
        } else {
            CONTINUITY_PENALTY
        }
        return register + continuity
    }

    const val CONTINUITY_BEATS = 3.0
    const val CONTINUITY_PENALTY = 6.0
    const val WIDE_CHORD_SEMITONES = 14
    const val ONSET_GROUP_BEATS = 0.0625
    private const val TREBLE_CENTER = 72.0
    private const val BASS_CENTER = 48.0
}
