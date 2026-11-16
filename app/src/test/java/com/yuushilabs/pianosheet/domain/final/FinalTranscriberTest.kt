package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.core.audio.WavFileReader
import com.yuushilabs.pianosheet.core.audio.WavFileWriter
import com.yuushilabs.pianosheet.core.dsp.DenoiseConfig
import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength
import com.yuushilabs.pianosheet.domain.live.ChunkDetector
import com.yuushilabs.pianosheet.domain.live.Detection
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end final analysis on the JVM: a real WAV file goes in, a score comes
 * out, with a deterministic detector standing in for Basic Pitch.
 */
class FinalTranscriberTest {

    private val sampleRate = 22050

    private fun sineWav(seconds: Double, frequency: Double = 440.0): File {
        val file = File.createTempFile("pianosheet-test", ".wav")
        val writer = WavFileWriter(file, sampleRate)
        val samples = FloatArray((seconds * sampleRate).toInt()) { index ->
            (0.4 * sin(2.0 * PI * frequency * index / sampleRate)).toFloat()
        }
        writer.write(samples)
        writer.close()
        return file
    }

    private class FakeDetector(private val notes: List<Detection>) : ChunkDetector {
        override val name: String = "fake_detector"
        var calls: Int = 0
            private set

        override fun detect(window: FloatArray, windowStartS: Double): List<Detection> {
            calls += 1
            val windowEnd = windowStartS + window.size.toDouble() / 22050.0
            return notes.filter { it.onsetS >= windowStartS - 1e-9 && it.onsetS < windowEnd }
        }
    }

    private fun detection(pitch: Int, onset: Double, length: Double = 0.4, confidence: Float = 0.8f) =
        Detection(pitchMidi = pitch, onsetS = onset, endS = onset + length, confidence = confidence, onsetStrength = 0.8f)

    @Test
    fun `a saved take is read, analysed and turned into a score`() {
        val file = sineWav(seconds = 4.0)
        val detector = FakeDetector(
            listOf(
                detection(60, 0.5),
                detection(62, 1.0),
                detection(64, 1.5),
                detection(65, 2.0),
            ),
        )
        val transcriber = FinalTranscriber(detector)
        val result = transcriber.transcribe(
            file = file,
            projectId = "p1",
            title = "テスト録音",
            options = FinalAnalysisOptions(denoise = DenoiseConfig(strength = DenoiseStrength.STANDARD)),
        )

        assertEquals(4, result.notes.size)
        assertEquals(4, result.score.noteCount)
        assertTrue("tempo is estimated near 120: ${result.score.tempoBpm}", result.score.tempoBpm in 100.0..140.0)
        assertNotNull(result.denoiseProfile)
        assertTrue(result.analysisMs >= 0)
        assertTrue("windows overlap the take", result.windowCount >= 2)
        file.delete()
    }

    @Test
    fun `a note heard by several overlapping windows becomes one note`() {
        val file = sineWav(seconds = 4.0)
        // The detector reports the same two notes from every window that covers them.
        val detector = FakeDetector(listOf(detection(60, 1.0), detection(67, 2.5)))
        val result = FinalTranscriber(detector).transcribe(file, "p1", "test")

        assertEquals(2, result.notes.size)
        assertEquals(setOf(60, 67), result.notes.map { it.pitchMidi }.toSet())
        assertTrue("the same note was offered more than once", detector.calls > 2)
        file.delete()
    }

    @Test
    fun `a microphone artefact at the start does not become a note`() {
        val file = sineWav(seconds = 3.0)
        val detector = FakeDetector(
            listOf(
                detection(61, 0.05, length = 0.1, confidence = 0.2f),
                detection(64, 1.0),
            ),
        )
        val result = FinalTranscriber(detector).transcribe(file, "p1", "test")

        assertEquals(1, result.notes.size)
        assertEquals(64, result.notes.first().pitchMidi)
        assertTrue("the artefact is counted", result.startupSuppressed >= 1)
        file.delete()
    }

    @Test
    fun `progress runs through the three stages and ends at 100 percent`() {
        val file = sineWav(seconds = 3.0)
        val progress = ArrayList<FinalProgress>()
        FinalTranscriber(FakeDetector(listOf(detection(60, 1.0)))).transcribe(
            file = file,
            projectId = "p1",
            title = "test",
            onProgress = { progress.add(it) },
        )

        assertTrue(progress.size >= 3)
        assertEquals(FinalStage.PREPARING, progress.first().stage)
        assertEquals(FinalStage.FINISHING, progress.last().stage)
        assertEquals(1.0, progress.last().fraction, 1e-6)
        for (i in 1 until progress.size) {
            assertTrue("fraction never goes backwards", progress[i].fraction >= progress[i - 1].fraction - 1e-9)
        }
        assertTrue(progress.any { it.stage == FinalStage.TRANSCRIBING })
        file.delete()
    }

    @Test
    fun `an empty take fails with a clear message`() {
        val file = File.createTempFile("pianosheet-empty", ".wav")
        val writer = WavFileWriter(file, sampleRate)
        writer.close()
        var failed = false
        try {
            FinalTranscriber(FakeDetector(emptyList())).transcribe(file, "p1", "empty")
        } catch (error: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        file.delete()
    }

    @Test
    fun `the live events are correlated with the final notes`() {
        val final = listOf(
            NoteEvent(pitchMidi = 60, onsetMs = 500.0),
            NoteEvent(pitchMidi = 64, onsetMs = 1000.0),
            NoteEvent(pitchMidi = 67, onsetMs = 1500.0),
        )
        val live = listOf(
            NoteEvent(pitchMidi = 60, onsetMs = 520.0),
            NoteEvent(pitchMidi = 67, onsetMs = 1510.0),
            NoteEvent(pitchMidi = 72, onsetMs = 2000.0),
        )
        val correlation = FinalTranscriber(FakeDetector(emptyList())).correlate(final, live)
        assertEquals(2, correlation.matched)
        assertEquals(1, correlation.analysisOnly)
        assertEquals(1, correlation.liveOnly)
    }

    @Test
    fun `window starts cover the whole file`() {
        val transcriber = FinalTranscriber(FakeDetector(emptyList()))
        val starts = transcriber.windowStarts(totalSamples = 22050 * 10)
        assertTrue(starts.first() == 0)
        assertEquals(22050 * 10 - FinalTranscriber.WINDOW_SAMPLES, starts.last())
        for (i in 1 until starts.size) {
            assertTrue(starts[i] > starts[i - 1])
        }
    }

    @Test
    fun `a short file is analysed in one window`() {
        val starts = FinalTranscriber(FakeDetector(emptyList())).windowStarts(totalSamples = 10000)
        assertEquals(listOf(0), starts)
    }

    @Test
    fun `wav reading round-trips the writer`() {
        val file = sineWav(seconds = 0.5)
        val data = WavFileReader.read(file)
        assertEquals(sampleRate, data.sampleRate)
        assertEquals(1, data.channels)
        assertEquals(11025, data.samples.size)
        assertEquals(0.5, data.durationSec, 0.01)
        val peak = data.samples.maxOf { kotlin.math.abs(it) }
        assertTrue("signal level preserved: $peak", peak in 0.3f..0.5f)
        file.delete()
    }
}
