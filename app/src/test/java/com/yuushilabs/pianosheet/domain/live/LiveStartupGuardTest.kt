package com.yuushilabs.pianosheet.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live-path regression for the "mysterious note 0.1 s after record" report. */
class LiveStartupGuardTest {

    private val chunk = FloatArray(2205) // 100 ms at 22.05 kHz

    private fun engine(detections: List<Detection>) = LiveTranscriptionEngine(
        LiveConfig(),
        object : ChunkDetector {
            override val name = "fake"
            override fun detect(window: FloatArray, windowStartS: Double): List<Detection> = detections
        },
    )

    @Test
    fun `a weak note at 0_17 s after the start is dropped`() {
        val engine = engine(listOf(Detection(61, 0.17, 0.35, 0.33f, 0.55f)))
        engine.startSession()
        repeat(30) { engine.pushAudio(chunk) }

        assertTrue("no note may be reported for the microphone settling", engine.events.isEmpty())
        assertTrue("the suppression is counted", engine.startupSuppressed > 0)
    }

    @Test
    fun `the very first hard strike still appears`() {
        val engine = engine(listOf(Detection(60, 0.10, 0.60, 0.85f, 0.95f)))
        engine.startSession()
        repeat(30) { engine.pushAudio(chunk) }

        assertEquals(1, engine.events.size)
        assertEquals(60, engine.events.first().pitchMidi)
        assertEquals(0, engine.startupSuppressed)
    }

    @Test
    fun `notes later in the take are unaffected`() {
        val engine = engine(listOf(Detection(67, 1.5, 1.9, 0.2f, 0.4f)))
        engine.startSession()
        repeat(30) { engine.pushAudio(chunk) }

        assertEquals(1, engine.events.size)
        assertEquals(1500.0, engine.events.first().onsetMs, 1e-6)
        assertEquals(0, engine.startupSuppressed)
    }

    @Test
    fun `the audio fed to the detector is ramped in, not stepped`() {
        val captured = ArrayList<FloatArray>()
        val engine = LiveTranscriptionEngine(
            LiveConfig(),
            object : ChunkDetector {
                override val name = "fake"
                override fun detect(window: FloatArray, windowStartS: Double): List<Detection> {
                    captured.add(window.copyOf())
                    return emptyList()
                }
            },
        )
        engine.startSession()
        val loud = FloatArray(2205) { 0.9f }
        repeat(10) { engine.pushAudio(loud) }

        val window = captured.first()
        // The left part of the first window is digital silence (zero padding), then
        // the ramp climbs instead of jumping to full scale.
        assertTrue(window.isNotEmpty())
        val firstRampSample = window.firstOrNull { it > 0f } ?: 0f
        assertTrue("ramp starts quiet: $firstRampSample", firstRampSample < 0.2f)
    }
}
