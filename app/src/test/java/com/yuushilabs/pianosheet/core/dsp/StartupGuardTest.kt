package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The stray note that used to appear ~0.1 s into a take (Phase 1 feedback):
 * the microphone settling must not read as an attack, but a real first note
 * played hard must survive.
 */
class StartupGuardTest {

    @Test
    fun `the ramp is silent at the start and fully open after the guard`() {
        assertEquals(0f, StartupGuard.gainAt(0.0), 1e-6f)
        assertEquals(0.5f, StartupGuard.gainAt(0.125), 1e-3f)
        assertEquals(1f, StartupGuard.gainAt(0.25), 1e-6f)
        assertEquals(1f, StartupGuard.gainAt(2.0), 1e-6f)
    }

    @Test
    fun `the first chunk is faded and later chunks are untouched`() {
        val sampleRate = 22050
        val chunk = FloatArray(2205) { 1f } // 100 ms of full-scale signal
        val first = StartupGuard.applyRamp(chunk, startSample = 0, sampleRate = sampleRate)
        assertEquals(0f, first[0], 1e-6f)
        // 1102 samples = 50 ms into a 250 ms ramp -> gain 0.2
        assertTrue("50 ms is a fifth of the way in: ${first[1102]}", first[1102] in 0.1f..0.35f)
        // 2204 samples = 100 ms, still inside the 250 ms ramp.
        assertEquals(0.4f, first[2204], 0.01f)

        val later = StartupGuard.applyRamp(chunk, startSample = 22050, sampleRate = sampleRate)
        assertEquals(1f, later[0], 1e-6f)
    }

    @Test
    fun `the zero-padded window edge is faded in`() {
        val sampleRate = 22050
        val audio = FloatArray(4410) { 0.8f }
        val faded = StartupGuard.applyEdgeFade(audio, sampleRate)
        assertEquals(0f, faded[0], 1e-6f)
        assertTrue(faded[100] < 0.8f)
        assertEquals(0.8f, faded[4409], 1e-6f)
    }

    @Test
    fun `a low confidence onset in the first quarter second is an artefact`() {
        // The Phase 1 screenshot: #1 C#4 at 0.17 s with confidence 0.33.
        assertTrue(StartupGuard.isStartupArtifact(onsetS = 0.17, confidence = 0.33f, onsetStrength = 0.55f))
        assertTrue(StartupGuard.isStartupArtifact(onsetS = 0.02, confidence = 0.20f, onsetStrength = null))
    }

    @Test
    fun `a strong first strike is kept`() {
        assertFalse(StartupGuard.isStartupArtifact(onsetS = 0.10, confidence = 0.82f, onsetStrength = 0.91f))
    }

    @Test
    fun `onsets after the guard window are never suppressed`() {
        assertFalse(StartupGuard.isStartupArtifact(onsetS = 0.40, confidence = 0.10f, onsetStrength = 0.05f))
        assertFalse(StartupGuard.isStartupArtifact(onsetS = 5.17, confidence = 0.10f, onsetStrength = 0.05f))
    }

    @Test
    fun `a settling step is removed and one-sample clicks are smoothed`() {
        val sampleRate = 22050
        val samples = FloatArray(11025) { 0.4f } // DC step at the start
        val cleaned = StartupGuard.applyStartupTreatment(samples, sampleRate = sampleRate)
        val earlyMean = (0 until 500).sumOf { cleaned[it].toDouble() } / 500.0
        assertTrue("DC offset removed: $earlyMean", abs(earlyMean) < 0.02)

        val withClick = FloatArray(11025) { 0.01f }
        withClick[300] = 0.9f
        val fixed = StartupGuard.suppressSpikes(withClick, sampleRate)
        assertTrue(fixed[300] < 0.1f)
        assertEquals(0.01f, fixed[600], 1e-4f)
    }
}
