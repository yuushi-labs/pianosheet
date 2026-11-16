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
}
