package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.core.audio.WavFileWriter
import com.yuushilabs.pianosheet.core.dsp.DenoiseConfig
import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength
import com.yuushilabs.pianosheet.domain.live.ChunkDetector
import com.yuushilabs.pianosheet.domain.live.Detection
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.NoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The hybrid final analysis: PTI-ONNX when it runs, Basic Pitch when it cannot.
 *
 * The engine is faked here — ONNX Runtime is Android-only — so what is checked
 * is the wiring the user sees: which engine's notes reach the score, the
 * progress text during the pass, and the fallback path with its report.
 */
class FinalTranscriberEngineTest {

    private val sampleRate = 22050

    private fun sineWav(seconds: Double): File {
        val file = File.createTempFile("pianosheet-pti", ".wav")
        WavFileWriter(file, sampleRate).use { writer ->
            writer.write(
                FloatArray((seconds * sampleRate).toInt()) { index ->
                    (0.4 * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
                },
            )
        }
        return file
    }

    private fun note(pitch: Int, onset: Double, length: Double) = NoteEvent(
        pitchMidi = pitch,
        onsetMs = onset * 1000.0,
        durationMs = length * 1000.0,
        confidence = 0.7f,
        state = NoteState.CONFIRMED,
        detector = "pti_onnx",
    )

    private class FakeEngine(
        private val notes: List<NoteEvent>,
        private val failure: Throwable? = null,
        private val chunks: Int = 4,
    ) : FinalNoteEngine {
        var calls = 0
            private set

        override val id: String = "pti_onnx"
        override val displayName: String = "PTI-ONNX（ピアノ専用）"

        override fun notes(
            audio: FloatArray,
            sampleRate: Int,
            shouldCancel: () -> Boolean,
            onProgress: (done: Int, total: Int) -> Unit,
        ): EngineResult {
            calls += 1
            failure?.let { throw it }
            repeat(chunks) { index -> onProgress(index + 1, chunks) }
            return EngineResult(notes = notes, chunkCount = chunks)
        }
    }

    private class FakeDetector(private val notes: List<Detection> = emptyList()) : ChunkDetector {
        override val name: String = "basic_pitch_onnx"
        var calls = 0
            private set

        override fun detect(window: FloatArray, windowStartS: Double): List<Detection> {
            calls += 1
            val end = windowStartS + window.size.toDouble() / 22050.0
            return notes.filter { it.onsetS >= windowStartS - 1e-9 && it.onsetS < end }
        }
    }

    @Test
    fun `the PTI engine provides the score and the progress counts its windows`() {
        val file = sineWav(6.0)
        val engine = FakeEngine(
            listOf(note(60, 0.5, 0.4), note(64, 1.0, 0.4), note(67, 1.5, 0.4), note(72, 2.0, 0.4)),
        )
        val detector = FakeDetector()
        val progress = ArrayList<String>()
        val result = FinalTranscriber(detector, finalEngine = engine).transcribe(
            file = file,
            projectId = "p-pti",
            title = "PTI",
            options = FinalAnalysisOptions(denoise = DenoiseConfig(strength = DenoiseStrength.WEAK)),
            onProgress = { progress += it.detail },
        )

        assertEquals(1, engine.calls)
        assertEquals(4, result.notes.size)
        assertEquals(4, result.score.noteCount)
        assertEquals("pti_onnx", result.engineId)
        assertEquals("pti_onnx", result.score.detector)
        assertNull(result.engineFallback)
        assertEquals(4, result.windowCount)
        assertEquals(4, result.ptiChunks)
        assertTrue(
            "progress should count PTI windows: $progress",
            progress.any { it.contains("/4") },
        )
        // Basic Pitch must not run over every 2 s window when PTI already did.
        assertEquals(0, detector.calls)
        file.delete()
    }

    @Test
    fun `running out of memory falls back to Basic Pitch and says so`() {
        val file = sineWav(4.0)
        val engine = FakeEngine(emptyList(), failure = OutOfMemoryError("session"))
        val detector = FakeDetector(
            listOf(
                Detection(60, 0.5, 0.9, 0.8f, 0.8f),
                Detection(64, 1.2, 1.6, 0.8f, 0.8f),
            ),
        )
        val result = FinalTranscriber(detector, finalEngine = engine).transcribe(
            file = file,
            projectId = "p-fallback",
            title = "fallback",
            options = FinalAnalysisOptions(denoise = DenoiseConfig(strength = DenoiseStrength.WEAK)),
        )

        assertEquals(1, engine.calls)
        assertTrue("Basic Pitch should have covered the take", detector.calls > 1)
        assertEquals(2, result.notes.size)
        assertEquals("basic_pitch_onnx", result.engineId)
        assertEquals("basic_pitch_onnx", result.score.detector)
        assertNotNull(result.engineFallback)
        assertTrue(
            "the fallback must say why: ${result.engineFallback}",
            result.engineFallback!!.contains("メモリ"),
        )
        file.delete()
    }

    @Test
    fun `a failed session build also falls back`() {
        val file = sineWav(3.0)
        val engine = FakeEngine(emptyList(), failure = EngineUnavailableException("モデルなし"))
        val detector = FakeDetector(listOf(Detection(62, 0.4, 0.8, 0.8f, 0.8f)))
        val result = FinalTranscriber(detector, finalEngine = engine).transcribe(
            file, "p2", "x",
            options = FinalAnalysisOptions(denoise = DenoiseConfig(strength = DenoiseStrength.WEAK)),
        )
        assertEquals("basic_pitch_onnx", result.engineId)
        assertTrue(result.engineFallback!!.contains("モデルなし"))
        file.delete()
    }

    @Test
    fun `the engine can be switched off for a Basic Pitch only pass`() {
        val file = sineWav(3.0)
        val engine = FakeEngine(listOf(note(60, 0.5, 0.4)))
        val detector = FakeDetector(listOf(Detection(65, 0.6, 1.0, 0.8f, 0.8f)))
        val result = FinalTranscriber(detector, finalEngine = engine).transcribe(
            file, "p3", "y",
            options = FinalAnalysisOptions(
                denoise = DenoiseConfig(strength = DenoiseStrength.WEAK),
                useFinalEngine = false,
            ),
        )
        assertEquals(0, engine.calls)
        assertEquals(1, result.notes.size)
        assertEquals(65, result.notes.first().pitchMidi)
        assertEquals("basic_pitch_onnx", result.engineId)
        assertNull(result.engineFallback)
        file.delete()
    }
}
