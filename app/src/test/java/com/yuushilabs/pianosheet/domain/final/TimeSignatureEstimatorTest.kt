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

    @Test
    fun `an anacrusis moves the downbeat instead of confusing the meter`() {
        // A 3/4 waltz that starts on beat 3: one beat of pickup, then full bars.
        val onsets = ArrayList<TimeSignatureEstimator.BeatOnset>()
        onsets.add(TimeSignatureEstimator.BeatOnset(0.0, 0.8))
        for (measure in 0 until 10) {
            val start = 1.0 + measure * 3.0
            onsets.add(TimeSignatureEstimator.BeatOnset(start, 3.0))
            onsets.add(TimeSignatureEstimator.BeatOnset(start + 1.0, 0.8))
            onsets.add(TimeSignatureEstimator.BeatOnset(start + 2.0, 0.8))
        }
        val estimate = TimeSignatureEstimator.estimate(onsets)
        assertEquals(com.yuushilabs.pianosheet.domain.score.TimeSignature.WALTZ, estimate.timeSignature)
        assertEquals("downbeat sits one beat after the first note", 1.0, estimate.phaseBeats, 1e-9)
    }

    @Test
    fun `a plain duple pulse stays 4-4 rather than becoming 2-4`() {
        val again = TimeSignatureEstimator.estimate(pattern(TimeSignature.COMMON, measures = 10, weak = 1.0))
        assertEquals(com.yuushilabs.pianosheet.domain.score.TimeSignature.COMMON, again.timeSignature)
    }

    @Test
    fun `a genuine march signature with accent on 1 and 2 wins 2-4`() {
        // 2/4 march: every bar has two beats, both accented, bars of 4 beats have
        // no stronger first-beat accent to explain.
        val onsets = ArrayList<TimeSignatureEstimator.BeatOnset>()
        for (beat in 0 until 40) {
            val inBar = beat % 2
            val weight = if (inBar == 0) 2.5 else 1.4
            onsets.add(TimeSignatureEstimator.BeatOnset(beat.toDouble(), weight))
        }
        val estimate = TimeSignatureEstimator.estimate(onsets)
        assertTrue("meter ${estimate.timeSignature}", estimate.timeSignature.beatsPerMeasure in 2.0..4.0)
        assertEquals(0.0, estimate.phaseBeats, 1e-9)
    }

    @Test
    fun `the phase of a 4-4 performance is reported`() {
        val onsets = ArrayList<TimeSignatureEstimator.BeatOnset>()
        onsets.add(TimeSignatureEstimator.BeatOnset(0.0, 0.5))
        onsets.add(TimeSignatureEstimator.BeatOnset(0.5, 0.5))
        onsets.add(TimeSignatureEstimator.BeatOnset(1.0, 0.5))
        for (measure in 0 until 8) {
            val start = 1.5 + measure * 4.0
            for (beat in 0 until 4) {
                onsets.add(TimeSignatureEstimator.BeatOnset(start + beat, if (beat == 0) 3.0 else 0.7))
            }
        }
        val estimate = TimeSignatureEstimator.estimate(onsets)
        assertEquals(com.yuushilabs.pianosheet.domain.score.TimeSignature.COMMON, estimate.timeSignature)
        assertEquals(1.5, estimate.phaseBeats, 1e-9)
    }
}
