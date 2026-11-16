package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.score.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSignatureEstimatorTest {

    /**
     * Downbeats get [accent], everything else [weak], for [measures] measures.
     * [extraOffsets] add further onsets inside each measure with their own weight
     * (the 6/8 middle accent).
     */
    private fun pattern(
        meter: TimeSignature,
        measures: Int,
        accent: Double = 3.0,
        weak: Double = 0.8,
        extraOffsets: List<Pair<Double, Double>> = emptyList(),
    ): List<TimeSignatureEstimator.BeatOnset> {
        val per = meter.beatsPerMeasure
        val result = ArrayList<TimeSignatureEstimator.BeatOnset>()
        for (measure in 0 until measures) {
            val start = measure * per
            result.add(TimeSignatureEstimator.BeatOnset(start, accent))
            var beat = 1.0
            while (beat < per - 1e-9) {
                result.add(TimeSignatureEstimator.BeatOnset(start + beat, weak))
                beat += 1.0
            }
            extraOffsets.forEach { (offset, weight) ->
                result.add(TimeSignatureEstimator.BeatOnset(start + offset, weight))
            }
        }
        return result
    }

    @Test
    fun `a march of four-beat accents is 4-4`() {
        val estimate = TimeSignatureEstimator.estimate(pattern(TimeSignature.COMMON, measures = 8))
        assertEquals(TimeSignature.COMMON, estimate.timeSignature)
        assertTrue("confidence ${estimate.confidence}", estimate.confidence > 0.3)
    }

    @Test
    fun `three-beat accents are 3-4`() {
        val estimate = TimeSignatureEstimator.estimate(pattern(TimeSignature.WALTZ, measures = 10))
        assertEquals(TimeSignature.WALTZ, estimate.timeSignature)
    }

    @Test
    fun `a compound 6-8 pattern with a middle accent is recognised`() {
        val estimate = TimeSignatureEstimator.estimate(
            pattern(
                meter = TimeSignature.COMPOUND,
                measures = 10,
                extraOffsets = listOf(1.5 to 3.0),
            ),
        )
        assertEquals(TimeSignature.COMPOUND, estimate.timeSignature)
    }

    @Test
    fun `too little material defaults to common time`() {
        val estimate = TimeSignatureEstimator.estimate(
            listOf(TimeSignatureEstimator.BeatOnset(0.0, 1.0), TimeSignatureEstimator.BeatOnset(1.0, 1.0)),
        )
        assertEquals(TimeSignature.COMMON, estimate.timeSignature)
        assertEquals(0.0, estimate.confidence, 1e-6)
    }

    @Test
    fun `a flat pulse without accents falls back to common time`() {
        val flat = (0 until 24).map { TimeSignatureEstimator.BeatOnset(it.toDouble(), 1.0) }
        assertEquals(TimeSignature.COMMON, TimeSignatureEstimator.estimate(flat).timeSignature)
    }
}
