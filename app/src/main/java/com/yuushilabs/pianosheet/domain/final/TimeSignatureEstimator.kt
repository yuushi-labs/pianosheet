package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.score.TimeSignature
import kotlin.math.abs
import kotlin.math.roundToInt

data class MeterEstimate(
    val timeSignature: TimeSignature,
    val confidence: Double,
    /**
     * Where the first downbeat sits, in quarter-note beats from the first note.
     * 0 = the performance starts on a downbeat; 1.5 = the first note is the last
     * eighth of an anacrusis, and so on.
     */
    val phaseBeats: Double = 0.0,
)

/**
 * Meter (and the bar phase) from the accent pattern of the notes (spec §5.2, §5.6).
 *
 * Two things made the first version pick the wrong bar line on real takes:
 *
 *  - it always assumed the first note is a downbeat, so a performance that starts
 *    with an anacrusis (very common in the piano repertoire) put every accent on
 *    beat 2 or 3 and the meter estimator then saw "no accent structure";
 *  - it compared candidates at that one phase.
 *
 * So each candidate meter is now evaluated at *its own best phase*: the profile
 * is folded to one bar, the slot carrying the most accent weight is taken as the
 * downbeat, and the score is how much that slot stands above the other slots
 * that carry notes.  The winning phase is returned with the meter so the bar
 * lines can be placed on it (and a pickup measure can be written).
 *
 * 6/8 is only chosen over 3/4 when the middle of the measure (the second
 * dotted-quarter) is accented as well, and long measures must explain the
 * accents a little better than short ones before they win.
 */
object TimeSignatureEstimator {

    /** One onset on the beat grid, with its accent weight (loudness × length). */
    data class BeatOnset(val beat: Double, val weight: Double)

    private val CANDIDATES = listOf(
        TimeSignature.COMMON,
        TimeSignature.WALTZ,
        TimeSignature.MARCH,
        TimeSignature.COMPOUND,
    )

    const val GRID = 0.25

    fun estimate(onsets: List<BeatOnset>): MeterEstimate {
        val usable = onsets.filter { it.beat >= -1e-6 && it.weight > 0.0 }
        if (usable.size < 3) return MeterEstimate(TimeSignature.COMMON, 0.0)

        data class Scored(
            val meter: TimeSignature,
            val score: Double,
            val shift: Int,
            val slots: Int,
        )

        val scored = ArrayList<Scored>(CANDIDATES.size)
        for (candidate in CANDIDATES) {
            val barBeats = candidate.beatsPerMeasure
            val slots = (barBeats / GRID).roundToInt()
            if (slots < 2) continue
            val profile = foldedProfile(usable, slots)
            var bestShift = 0
            var bestScore = -1.0
            for (shift in 0 until slots) {
                val score = downbeatScore(profile, shift)
                if (score > bestScore) {
                    bestScore = score
                    bestShift = shift
                }
            }
            // Common-practice weights: when two meters explain the accents about
            // equally well, the one a pianist would write wins.  Without this a
            // 3/4 Gymnopédie came out as 2/4 (its two-beat profile is only 4 %
            // weaker) and every marching 2/4 piece as 4/4.
            val bias = lengthBias(barBeats) * STYLE_PREFERENCE[candidate]!!
            scored.add(Scored(candidate, bestScore * bias, bestShift, slots))
        }
        if (scored.isEmpty()) return MeterEstimate(TimeSignature.COMMON, 0.0)

        var best = scored.first()
        for (row in scored) {
            if (row.score > best.score + 1e-9) best = row
        }
        // Ties keep 4/4 (the list order already prefers it) and 3/4 over 6/8.
        var meter = best.meter
        var shift = best.shift
        var slots = best.slots

        if (meter == TimeSignature.WALTZ) {
            val profile = foldedProfile(usable, slots)
            val middle = profile[(shift + (1.5 / GRID).roundToInt()) % slots]
            val second = profile[(shift + (1.0 / GRID).roundToInt()) % slots]
            val third = profile[(shift + (2.0 / GRID).roundToInt()) % slots]
            val compound = scored.firstOrNull { it.meter == TimeSignature.COMPOUND }
            if (compound != null && middle > (second + third) / 2.0 * 1.3 && middle > 1e-9) {
                meter = TimeSignature.COMPOUND
                shift = compound.shift
                slots = compound.slots
            }
        }
        val confidence = (best.score / 2.0).coerceIn(0.0, 1.0)
        return MeterEstimate(meter, round3(confidence), phaseBeats = shift * GRID)
    }

    /** Longer measures must explain the accents slightly better. */
    private fun lengthBias(barBeats: Double): Double = 1.0 + 0.02 * kotlin.math.log2(barBeats / 4.0)

    /**
     * How readily each meter is written for this kind of music.  4/4 is the
     * default; 2/4 is a march signature, so it has to win clearly to be chosen.
     */
    private val STYLE_PREFERENCE = mapOf(
        TimeSignature.COMMON to 1.0,
        TimeSignature.WALTZ to 0.98,
        TimeSignature.COMPOUND to 0.96,
        TimeSignature.MARCH to 0.94,
    )

    /** Accent weight per 0.25-beat slot of one bar (all bars folded together). */
    private fun foldedProfile(onsets: List<BeatOnset>, slots: Int): DoubleArray {
        val result = DoubleArray(slots)
        for (onset in onsets) {
            val index = (onset.beat / GRID).roundToInt().mod(slots)
            result[index] += onset.weight
        }
        return result
    }

    /**
     * Downbeat dominance at [shift]: weight on the bar line divided by the average
     * weight of the other slots that actually carry notes.  1.0 = no accent.
     */
    private fun downbeatScore(profile: DoubleArray, shift: Int): Double {
        val down = profile[shift]
        if (down <= 1e-9) return 0.0
        var sum = 0.0
        var count = 0
        for (index in profile.indices) {
            if (index == shift || profile[index] <= 1e-9) continue
            sum += profile[index]
            count += 1
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return if (mean <= 1e-12) 0.0 else down / mean
    }

    private fun round3(value: Double): Double = (value * 1000.0).roundToInt() / 1000.0

}
