package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.score.KeySignature
import com.yuushilabs.pianosheet.domain.score.Mode
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class KeyEstimate(val keySignature: KeySignature, val confidence: Double)

/**
 * Key / key-signature estimate from the pitch-class content (spec §5.2, §5.6).
 *
 * Krumhansl–Schmuckler style: the duration-weighted pitch-class histogram is
 * correlated with the major and minor key profiles for all 12 tonics, and the
 * best correlation wins.  When the winner is only weakly better than the runner
 * up the confidence is reported low, because the app shows the key as an
 * estimate the user may change (spec §5.6).
 */
object KeyEstimator {

    /** midi pitch -> weight (usually the sounding duration in beats). */
    data class PitchWeight(val midiPitch: Int, val weight: Double)

    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
    )

    /** fifths of the major key for each tonic pitch class (C, C#, D … B). */
    private val MAJOR_FIFTHS_BY_TONIC = intArrayOf(0, 7, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)

    fun estimate(pitches: List<PitchWeight>): KeyEstimate {
        val histogram = DoubleArray(12)
        var total = 0.0
        for (pitch in pitches) {
            if (pitch.weight <= 0.0) continue
            histogram[((pitch.midiPitch % 12) + 12) % 12] += pitch.weight
            total += pitch.weight
        }
        if (total <= 0.0) return KeyEstimate(KeySignature.C_MAJOR, 0.0)

        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        var bestTonic = 0
        var bestMode = Mode.MAJOR
        for (tonic in 0 until 12) {
            val major = correlation(histogram, MAJOR_PROFILE, tonic)
            if (major > bestScore) {
                secondScore = bestScore
                bestScore = major
                bestTonic = tonic
                bestMode = Mode.MAJOR
            } else if (major > secondScore) {
                secondScore = major
            }
            val minor = correlation(histogram, MINOR_PROFILE, tonic)
            if (minor > bestScore) {
                secondScore = bestScore
                bestScore = minor
                bestTonic = tonic
                bestMode = Mode.MINOR
            } else if (minor > secondScore) {
                secondScore = minor
            }
        }

        val fifths = when (bestMode) {
            Mode.MAJOR -> MAJOR_FIFTHS_BY_TONIC[bestTonic]
            // Relative major is a minor third above the minor tonic.
            Mode.MINOR -> MAJOR_FIFTHS_BY_TONIC[(bestTonic + 3) % 12]
        }.let(::simplifyEnharmonic)
        val margin = if (secondScore.isFinite()) (bestScore - secondScore).coerceAtLeast(0.0) else 0.0
        val confidence = (bestScore.coerceIn(0.0, 1.0) * (1.0 - margin * 2.0).coerceIn(0.35, 1.0))
        return KeyEstimate(
            keySignature = KeySignature(fifths = fifths, mode = bestMode),
            confidence = (confidence * 1000.0).roundToInt() / 1000.0,
        )
    }

    /**
     * Writes the key with fewer accidentals when the enharmonic twin has them.
     *
     * C# major (7 sharps) and D♭ major (5 flats) are the same key; a player reads
     * the second far more easily, and it also removes the double sharps a 7-sharp
     * signature forces on chromatic notes.  The user's own take came out as C#
     * major before this: the score printed F𝄪 and C𝄪 where the music has G and C.
     * Only an actual reduction is applied (F# major 6 → G♭ major 6 is left alone).
     */
    fun simplifyEnharmonic(fifths: Int): Int = when {
        fifths > 5 && 12 - fifths < fifths -> fifths - 12
        fifths < -5 && 12 + fifths < -fifths -> fifths + 12
        else -> fifths
    }

    private fun correlation(histogram: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        var meanHist = 0.0
        var meanProfile = 0.0
        for (i in 0 until 12) {
            meanHist += histogram[i]
            meanProfile += profile[i]
        }
        meanHist /= 12.0
        meanProfile /= 12.0
        var covariance = 0.0
        var varianceHist = 0.0
        var varianceProfile = 0.0
        for (i in 0 until 12) {
            val h = histogram[(tonic + i) % 12] - meanHist
            val p = profile[i] - meanProfile
            covariance += h * p
            varianceHist += h * h
            varianceProfile += p * p
        }
        val denominator = sqrt(varianceHist * varianceProfile)
        return if (denominator < 1e-12) 0.0 else covariance / denominator
    }
}
