package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.score.TimeSignature
import kotlin.math.abs
import kotlin.math.roundToInt

data class MeterEstimate(val timeSignature: TimeSignature, val confidence: Double)

/**
 * Meter from the accent pattern of the notes (spec §5.2, §5.6).
 *
 * Every candidate meter is scored by how clearly its downbeat carries more note
 * weight than the other beats of the measure.  4/4 is the default when nothing
 * stands out, and 6/8 is only chosen over 3/4 when the middle of the measure
 * (the second dotted-quarter) is accented too.
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

    fun estimate(onsets: List<BeatOnset>): MeterEstimate {
        val usable = onsets.filter { it.beat >= -1e-6 && it.weight > 0.0 }
        if (usable.size < 3) return MeterEstimate(TimeSignature.COMMON, 0.0)

        val perBeat = profile(usable, grid = 0.25, length = 12)
        var bestMeter = TimeSignature.COMMON
        var bestScore = 0.0
        for (candidate in CANDIDATES) {
            val length = candidate.beatsPerMeasure
            val score = downbeatScore(perBeat, length)
            if (score > bestScore + 1e-9) {
                bestScore = score
                bestMeter = candidate
            } else if (abs(score - bestScore) <= 1e-9 && candidate == TimeSignature.COMMON) {
                bestMeter = TimeSignature.COMMON
            }
        }

        // Tell 6/8 from 3/4: compound meter accents beat 0 and beat 1.5.
        if (bestMeter == TimeSignature.WALTZ && compoundAccent(perBeat)) {
            bestMeter = TimeSignature.COMPOUND
        }
        val confidence = (bestScore / 2.0).coerceIn(0.0, 1.0)
        return MeterEstimate(bestMeter, round3(confidence))
    }

    /** Total weight per 0.25-beat slot, folded into [length] beats. */
    private fun profile(onsets: List<BeatOnset>, grid: Double, length: Int): DoubleArray {
        val slots = (length / grid).roundToInt()
        val result = DoubleArray(slots)
        for (onset in onsets) {
            val index = ((onset.beat / grid).roundToInt().mod(slots))
            result[index] += onset.weight
        }
        return result
    }

    /**
     * Downbeat dominance: weight on the first beat of each measure divided by the
     * average weight of the other beats that actually carry notes.  1.0 means no
     * accent at all, larger values mean the downbeat stands out.
     *
     * Only positions with notes count: averaging over empty slots would favour the
     * shortest candidate meter (an evenly played pulse would "become" 2/4).
     */
    private fun downbeatScore(perBeat: DoubleArray, measureBeats: Double): Double {
        val slots = perBeat.size
        val measureSlots = (measureBeats / 0.25).roundToInt()
        if (measureSlots < 2 || measureSlots > slots) return 0.0
        val active = (0 until slots).filter { perBeat[it] > 1e-9 }
        if (active.isEmpty()) return 0.0
        val downbeats = active.filter { it % measureSlots == 0 }
        val others = active.filter { it % measureSlots != 0 }
        if (downbeats.isEmpty() || others.isEmpty()) return 0.0
        val downbeatWeight = downbeats.sumOf { perBeat[it] } / downbeats.size
        val otherWeight = others.sumOf { perBeat[it] } / others.size
        if (otherWeight <= 1e-9) return 0.0
        return downbeatWeight / otherWeight
    }

    /** In a 6/8 measure the second dotted-quarter (beat 1.5) is accented. */
    private fun compoundAccent(perBeat: DoubleArray): Boolean {
        val middle = perBeat[(1.5 / 0.25).roundToInt()]
        val second = perBeat[(1.0 / 0.25).roundToInt()]
        val third = perBeat[(2.0 / 0.25).roundToInt()]
        return middle > (second + third) / 2.0 * 1.15 && middle > 1e-9
    }

    private fun round3(value: Double): Double = (value * 1000.0).roundToInt() / 1000.0
}
