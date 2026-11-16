package com.yuushilabs.pianosheet.domain.final

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TempoEstimatorTest {

    private fun onsets(bpm: Double, count: Int, subdivision: Int = 1, jitterMs: Double = 0.0): List<TempoEstimator.Onset> {
        val step = 60.0 / bpm / subdivision
        val random = Random(7)
        return (0 until count).map { index ->
            val jitter = if (jitterMs > 0) (random.nextDouble() * 2 - 1) * jitterMs / 1000.0 else 0.0
            TempoEstimator.Onset(index * step + jitter, weight = 1f)
        }
    }

    @Test
    fun `quarter notes at 120 bpm are recognised`() {
        val estimate = TempoEstimator.estimate(onsets(bpm = 120.0, count = 16))
        assertEquals(120.0, estimate.bpm, 2.0)
        assertTrue("confidence ${estimate.confidence}", estimate.confidence > 0.9)
    }

    @Test
    fun `eighth notes at 120 bpm do not double the tempo`() {
        val estimate = TempoEstimator.estimate(onsets(bpm = 120.0, count = 32, subdivision = 2))
        assertEquals(120.0, estimate.bpm, 12.0)
        assertTrue("tempo must stay in a musical range: ${estimate.bpm}", estimate.bpm in 60.0..160.0)
    }

    @Test
    fun `a slow 60 bpm pulse is not reported as an absurd value`() {
        val estimate = TempoEstimator.estimate(onsets(bpm = 60.0, count = 10))
        assertTrue("bpm ${estimate.bpm}", estimate.bpm in 50.0..130.0)
    }

    @Test
    fun `human timing jitter does not break the estimate`() {
        val estimate = TempoEstimator.estimate(onsets(bpm = 100.0, count = 20, jitterMs = 18.0))
        assertTrue("bpm ${estimate.bpm}", estimate.bpm in 92.0..108.0)
    }

    @Test
    fun `a single note falls back to the default tempo without confidence`() {
        val estimate = TempoEstimator.estimate(listOf(TempoEstimator.Onset(0.4, 1f)))
        assertEquals(TempoEstimator.DEFAULT_BPM, estimate.bpm, 1e-6)
        assertEquals(0.0, estimate.confidence, 1e-6)
    }

    @Test
    fun `no notes at all is not a crash`() {
        val estimate = TempoEstimator.estimate(emptyList())
        assertEquals(TempoEstimator.DEFAULT_BPM, estimate.bpm, 1e-6)
    }

    @Test
    fun `a chord counts as one attack`() {
        val chord = listOf(0.0f, 0.01f, 0.02f).map { offset ->
            TempoEstimator.Onset(1.0 + offset.toDouble(), weight = 1f)
        }
        val attacks = TempoEstimator.clusterAttacks(chord)
        assertEquals(1, attacks.size)
        assertEquals(1.0, attacks.first().timeS, 1e-9)
    }

    @Test
    fun `a split note does not create a second beat`() {
        // 80 BPM quarters, each reported twice 70 ms apart: reading the second
        // copy as an eighth note is what produced 160 BPM on the real take.
        val base = onsets(bpm = 80.0, count = 12)
        val doubled = base.flatMap { onset ->
            listOf(onset, TempoEstimator.Onset(onset.timeS + 0.07, weight = 0.4f))
        }.sortedBy { it.timeS }
        val estimate = TempoEstimator.estimate(doubled)
        assertTrue("bpm ${estimate.bpm}", estimate.bpm in 70.0..100.0)
    }

    @Test
    fun `off-beat eighths keep the quarter-note beat`() {
        // Quarters at 95 BPM with an off-beat eighth on every other beat: the
        // beat must stay 95 while the off-beats are played.
        val step = 60.0 / 95.0
        val quarters = (0 until 20).map { TempoEstimator.Onset(it * step, 1f) }
        val offBeats = (1 until 20 step 2).map { TempoEstimator.Onset(it * step + step / 2, 0.8f) }
        val estimate = TempoEstimator.estimate(quarters + offBeats)
        assertTrue("bpm ${estimate.bpm}", estimate.bpm in 84.0..106.0)
    }

    @Test
    fun `grace notes on the beat do not double the tempo`() {
        val step = 60.0 / 95.0
        val quarters = (0 until 20).map { TempoEstimator.Onset(it * step, 1f) }
        val ornaments = (0 until 20 step 3).map { TempoEstimator.Onset(it * step + 0.06, 0.4f) }
        val estimate = TempoEstimator.estimate(quarters + ornaments)
        assertTrue("bpm ${estimate.bpm}", estimate.bpm in 84.0..106.0)
    }

    @Test
    fun `the preferred tempo is not penalised and an octave away is`() {
        assertEquals(1.0, TempoEstimator.preference(TempoEstimator.PREFERRED_BPM), 1e-9)
        assertTrue(TempoEstimator.preference(200.0) < 0.6)
        assertTrue(TempoEstimator.preference(50.0) < 0.6)
    }
}
