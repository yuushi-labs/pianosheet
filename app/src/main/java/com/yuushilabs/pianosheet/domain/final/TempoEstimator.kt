package com.yuushilabs.pianosheet.domain.final

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.roundToInt

data class TempoEstimate(val bpm: Double, val confidence: Double) {
    val beatSec: Double get() = 60.0 / bpm
}

/**
 * Tempo (BPM) from note onsets (spec §5.2, §5.6).
 *
 * Two problems showed up on a real take: a dense performance plus a few
 * over-detected subdivisions made the estimator settle on 160 BPM for playing
 * that is around 95, and notes the model split a few tens of milliseconds apart
 * acted like extra beats.
 *
 * So the onsets are first collapsed into *attacks* — everything within
 * [ATTACK_CLUSTER_SEC] is one press of a key (a chord, or one note reported
 * twice) — and the circular phase-histogram score of each candidate period is
 * multiplied by a soft preference for the 70–140 BPM region.  A period that only
 * doubles real beats therefore has to explain the notes substantially better
 * than the musical reading before it wins.
 */
object TempoEstimator {

    data class Onset(val timeS: Double, val weight: Float = 1f)

    /** Beats per minute preferred when several candidate periods explain the notes equally well. */
    const val PREFERRED_BPM = 100.0

    const val DEFAULT_BPM = 120.0

    /** Onsets closer than this are the same key press (chord or split note). */
    const val ATTACK_CLUSTER_SEC = 0.09

    /** Width of the tempo preference, in octaves around [PREFERRED_BPM]. */
    const val PREFERENCE_OCTAVES = 0.9

    fun estimate(
        onsets: List<Onset>,
        minBpm: Double = 50.0,
        maxBpm: Double = 200.0,
    ): TempoEstimate {
        val attacks = clusterAttacks(onsets)
        if (attacks.size < 2) return TempoEstimate(DEFAULT_BPM, 0.0)
        val totalWeight = attacks.sumOf { it.weight.toDouble() }
        if (totalWeight <= 0.0) return TempoEstimate(DEFAULT_BPM, 0.0)

        val candidates = candidatePeriods(attacks, minBpm, maxBpm)
        if (candidates.isEmpty()) return TempoEstimate(DEFAULT_BPM, 0.0)

        var bestScore = -1.0
        val scored = ArrayList<Pair<Double, Double>>(candidates.size)
        for (period in candidates) {
            val bpm = 60.0 / period
            val score = gridScore(attacks, period, totalWeight) * preference(bpm)
            scored.add(period to score)
            if (score > bestScore) bestScore = score
        }
        if (bestScore <= 0.0) return TempoEstimate(DEFAULT_BPM, 0.0)

        // All periods that explain the notes (nearly) as well as the best one are
        // tempo-equivalent; take the one nearest the preferred tempo.
        val threshold = bestScore * 0.985
        val equivalent = scored.filter { it.second >= threshold }
        val chosen = equivalent.minByOrNull { abs(60.0 / it.first - PREFERRED_BPM) }?.first
            ?: scored.maxByOrNull { it.second }!!.first
        val bpm = 60.0 / chosen
        return TempoEstimate(bpm = round2(bpm), confidence = round3(bestScore.coerceIn(0.0, 1.0)))
    }

    /**
     * Groups onsets into key presses.  The strongest note of a cluster sets the
     * attack time (a chord's bass is as much the beat as its top) and the weights
     * add up, capped so a ten-note chord does not outvote ten single notes.
     */
    fun clusterAttacks(onsets: List<Onset>, toleranceS: Double = ATTACK_CLUSTER_SEC): List<Onset> {
        val sorted = onsets.filter { it.timeS >= 0.0 && it.weight > 0f }.sortedBy { it.timeS }
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<Onset>(sorted.size)
        var clusterStart = sorted[0].timeS
        var anchor = sorted[0]
        var weight = sorted[0].weight.toDouble()
        for (onset in sorted.drop(1)) {
            if (onset.timeS - clusterStart <= toleranceS) {
                weight += onset.weight
                if (onset.weight > anchor.weight) anchor = onset
            } else {
                out.add(Onset(anchor.timeS, weight.coerceAtMost(MAX_CLUSTER_WEIGHT).toFloat()))
                clusterStart = onset.timeS
                anchor = onset
                weight = onset.weight.toDouble()
            }
        }
        out.add(Onset(anchor.timeS, weight.coerceAtMost(MAX_CLUSTER_WEIGHT).toFloat()))
        return out
    }

    /** Beat period (seconds) candidates from attack intervals and their multiples. */
    private fun candidatePeriods(onsets: List<Onset>, minBpm: Double, maxBpm: Double): List<Double> {
        val minPeriod = 60.0 / maxBpm
        val maxPeriod = 60.0 / minBpm
        val intervals = ArrayList<Double>()
        for (i in onsets.indices) {
            for (j in i + 1 until onsets.size) {
                val delta = onsets[j].timeS - onsets[i].timeS
                if (delta > 2.5) break
                if (delta > 1e-4) intervals.add(delta)
            }
            if (intervals.size > 400) break
        }
        val periods = LinkedHashSet<Double>()
        fun add(value: Double) {
            if (value in minPeriod..maxPeriod) periods.add(round4(value))
        }
        for (interval in intervals) {
            for (divisor in 1..4) add(interval / divisor)
            for (multiplier in 1..2) add(interval * multiplier)
        }
        if (periods.isEmpty()) add(DEFAULT_BPM.let { 60.0 / it })
        return periods.toList()
    }

    /** How much of the note weight lands on a grid of [period] seconds (best phase), 0..1. */
    private fun gridScore(onsets: List<Onset>, period: Double, totalWeight: Double): Double {
        val steps = 48
        val hist = DoubleArray(steps)
        for (onset in onsets) {
            val phase = ((onset.timeS % period) + period) % period
            val index = ((phase / period) * steps).toInt().coerceIn(0, steps - 1)
            hist[index] += onset.weight.toDouble()
        }
        val sigma = steps * 0.09
        var best = 0.0
        for (shift in 0 until steps) {
            var sum = 0.0
            for (index in 0 until steps) {
                var distance = abs(index - shift)
                if (distance > steps / 2) distance = steps - distance
                val weight = exp(-0.5 * (distance / sigma) * (distance / sigma))
                if (weight < 1e-3) continue
                sum += hist[index] * weight
            }
            if (sum > best) best = sum
        }
        return (best / totalWeight).coerceIn(0.0, 1.0)
    }

    /**
     * Soft preference for a walking tempo: 1.0 at [PREFERRED_BPM], 0.5 one
     * [PREFERENCE_OCTAVES] away in log space.  This is what keeps a performance
     * at ~95 BPM from being read as 160–190.
     */
    fun preference(bpm: Double): Double {
        if (bpm <= 0.0) return 0.0
        val octaves = log2(bpm / PREFERRED_BPM) / PREFERENCE_OCTAVES
        return exp(-0.5 * octaves * octaves)
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
    private fun round3(value: Double): Double = (value * 1000.0).roundToInt() / 1000.0
    private fun round4(value: Double): Double = (value * 10000.0).roundToInt() / 10000.0

    private const val MAX_CLUSTER_WEIGHT = 3.0
}
