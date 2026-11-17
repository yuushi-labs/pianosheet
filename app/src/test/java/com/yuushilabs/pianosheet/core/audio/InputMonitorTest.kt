package com.yuushilabs.pianosheet.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The microphone monitor: peak/RMS in dBFS, clipping and dead input.
 *
 * The take that made this necessary was recorded 40 dB too quietly and nothing
 * on the screen said so; the transcriber simply returned almost no notes.
 */
class InputMonitorTest {

    private val sampleRate = 22050

    private fun sine(amplitude: Float, seconds: Double = 1.0): FloatArray =
        FloatArray((seconds * sampleRate).toInt()) { i ->
            (amplitude * sin(2.0 * PI * 440.0 * i / sampleRate)).toFloat()
        }

    @Test
    fun `a healthy take reports its level and no warning`() {
        val monitor = InputMonitor(sampleRate)
        val state = monitor.update(sine(0.5f))
        // RMS of a 0.5 amplitude sine is 0.354 = -9 dBFS.
        assertTrue("rms ${state.rmsDbfs}", state.rmsDbfs in -10.0f..-8.0f)
        assertTrue("peak ${state.peakDbfs}", state.peakDbfs in -7.0f..-5.0f)
        assertNull(state.warning)
    }

    @Test
    fun `a clipped take is flagged`() {
        val monitor = InputMonitor(sampleRate)
        var state = monitor.update(FloatArray(sampleRate) { 1.0f })
        state = monitor.update(FloatArray(sampleRate) { -1.0f })
        assertEquals(InputMonitor.Warning.CLIPPING, state.warning)
        assertTrue("clipped samples ${state.clippedSamples}", state.clippedSamples > 0)
    }

    @Test
    fun `a take that is 40 dB too quiet is flagged`() {
        val monitor = InputMonitor(sampleRate)
        var state = monitor.update(sine(0.01f)) // ≈ -43 dBFS RMS
        state = monitor.update(sine(0.01f))
        assertEquals(InputMonitor.Warning.TOO_QUIET, state.warning)
        assertTrue("rms ${state.rmsDbfs}", state.rmsDbfs < -40.0f)
    }

    @Test
    fun `silence is flagged after three seconds`() {
        val monitor = InputMonitor(sampleRate)
        var state = monitor.update(sine(0.4f, seconds = 0.5))
        assertNull(state.warning)
        repeat(3) { state = monitor.update(FloatArray(sampleRate)) }
        assertEquals(InputMonitor.Warning.SILENCE, state.warning)
        assertEquals(3000.0, state.silentMs, 250.0)
    }

    @Test
    fun `every warning has a Japanese sentence`() {
        for (warning in InputMonitor.Warning.entries) {
            assertNotNull(InputMonitor.message(warning))
            assertTrue(InputMonitor.message(warning).contains("ください"))
        }
    }

    @Test
    fun `reset clears the counters`() {
        val monitor = InputMonitor(sampleRate)
        monitor.update(FloatArray(sampleRate) { 1.0f })
        monitor.reset()
        val state = monitor.update(sine(0.5f, seconds = 0.2))
        assertEquals(0L, state.clippedSamples)
    }
}
