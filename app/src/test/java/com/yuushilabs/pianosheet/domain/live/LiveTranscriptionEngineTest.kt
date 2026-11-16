package com.yuushilabs.pianosheet.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptionEngineTest {

    private val chunk = FloatArray(2205) // 100 ms at 22.05 kHz

    private fun engine(detections: List<Detection>) = LiveTranscriptionEngine(
        LiveConfig(),
        object : ChunkDetector {
            override val name = "fake"
            override fun detect(window: FloatArray, windowStartS: Double): List<Detection> = detections
        },
    )

    private fun LiveTranscriptionEngine.pushChunks(count: Int) {
        repeat(count) { pushAudio(chunk) }
    }

    @Test
    fun `a note reported by every overlapping window is added once`() {
        val engine = engine(listOf(Detection(60, 0.5, 0.9, 0.9f, 0.9f)))
        engine.startSession()
        engine.pushChunks(30) // 3 s

        assertEquals(1, engine.events.size)
        assertEquals(500.0, engine.events.first().onsetMs, 1e-6)
        assertTrue("later windows must be recognised as duplicates", engine.duplicatesDropped > 0)
    }

    @Test
    fun `a chord keeps all of its notes`() {
        val engine = engine(
            listOf(
                Detection(60, 1.0, 1.5, 0.8f, 0.9f),
                Detection(64, 1.0, 1.5, 0.8f, 0.9f),
                Detection(67, 1.0, 1.5, 0.8f, 0.9f),
            ),
        )
        engine.startSession()
        engine.pushChunks(25)

        assertEquals(3, engine.events.size)
        assertEquals(setOf(60, 64, 67), engine.events.map { it.pitchMidi }.toSet())
    }

    @Test
    fun `a decay tail with a weak onset is not counted as a new note`() {
        val engine = engine(listOf(Detection(60, 1.0, 1.6, 0.5f, 0.1f)))
        engine.startSession()
        engine.pushChunks(25)

        assertTrue(engine.events.isEmpty())
        assertTrue("weak onsets are filtered", engine.weakDropped > 0)
    }

    @Test
    fun `re-striking the same key after a pause makes a second event`() {
        val engine = engine(
            listOf(
                Detection(60, 1.0, 1.2, 0.9f, 0.9f),
                Detection(60, 1.6, 1.8, 0.9f, 0.9f),
            ),
        )
        engine.startSession()
        engine.pushChunks(30)

        assertEquals(2, engine.events.size)
        assertEquals(listOf(1000.0, 1600.0), engine.events.map { it.onsetMs }.sorted())
    }

    @Test
    fun `near-simultaneous duplicates from one window are merged`() {
        val engine = engine(
            listOf(
                Detection(60, 1.0, 1.4, 0.9f, 0.9f),
                Detection(60, 1.05, 1.4, 0.85f, 0.9f),
            ),
        )
        engine.startSession()
        engine.pushChunks(25)

        assertEquals(1, engine.events.size)
        assertTrue(engine.duplicatesDropped >= 1)
    }

    @Test
    fun `the newest guard region is left for the next step instead of being lost`() {
        // A 0.5 s onset is still inside the 260 ms guard while the step ends at 0.6 s.
        val engine = engine(listOf(Detection(72, 0.5, 0.8, 0.9f, 0.9f)))
        engine.startSession()
        engine.pushChunks(6) // 0.6 s
        assertTrue("nothing committed while the onset is inside the guard", engine.events.isEmpty())

        engine.pushChunks(4) // 1.0 s: audio time is now well past onset + guard
        assertEquals(1, engine.events.size)
        assertEquals(500.0, engine.events.first().onsetMs, 1e-6)
    }

    @Test
    fun `finish session runs the pending step without throwing`() {
        val engine = engine(listOf(Detection(60, 0.1, 0.3, 0.9f, 0.9f)))
        engine.startSession()
        engine.pushAudio(FloatArray(1102)) // 50 ms, no complete step yet
        engine.finishSession()
        assertTrue(engine.steps.isNotEmpty())
    }
}
