package com.yuushilabs.pianosheet.playback

import com.yuushilabs.pianosheet.domain.final.FinalNote
import com.yuushilabs.pianosheet.domain.final.ScoreBuilder
import com.yuushilabs.pianosheet.domain.score.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PianoSynthTest {

    private val sampleRate = 44100

    private fun renderNote(midi: Int, duration: Double = 1.0): FloatArray {
        val buffer = FloatArray((duration + 0.5).times(sampleRate).toInt())
        PianoSynth.addNote(buffer, 0, sampleRate, midi, duration, velocity = 0.8f)
        return PianoSynth.finish(buffer)
    }

    @Test
    fun `A4 renders at 440 Hz`() {
        val buffer = renderNote(69)
        val frequency = PianoSynth.dominantFrequency(buffer, sampleRate, 200.0, 900.0)
        assertEquals(440.0, frequency, 10.0)
    }

    @Test
    fun `C5 renders at 523 Hz`() {
        val buffer = renderNote(72, duration = 0.8)
        val frequency = PianoSynth.dominantFrequency(buffer, sampleRate, 300.0, 900.0)
        assertEquals(523.25, frequency, 10.0)
    }

    @Test
    fun `the attack is silent at sample zero and the note decays`() {
        val buffer = renderNote(60)
        assertEquals(0f, buffer[0], 1e-6f)
        val early = (0 until 2000).sumOf { abs(buffer[it].toDouble()) } / 2000
        val late = (sampleRate until sampleRate + 2000).sumOf { abs(buffer[it].toDouble()) } / 2000
        assertTrue("the envelope decays: early=$early late=$late", early > late)
    }

    @Test
    fun `output never clips`() {
        val buffer = FloatArray(sampleRate)
        for (midi in 48..72 step 2) {
            PianoSynth.addNote(buffer, 0, sampleRate, midi, 0.9, velocity = 1f)
        }
        PianoSynth.finish(buffer)
        val peak = buffer.maxOf { abs(it) }
        assertTrue("peak $peak stays below full scale", peak <= PianoSynth.PEAK + 1e-4f)
    }

    @Test
    fun `a note starts where it is asked to`() {
        val buffer = FloatArray(sampleRate)
        PianoSynth.addNote(buffer, sampleRate / 2, sampleRate, 69, 0.3)
        val before = (0 until sampleRate / 2).maxOf { abs(buffer[it]) }
        assertEquals(0f, before, 1e-6f)
        val after = (sampleRate / 2 until sampleRate).maxOf { abs(buffer[it]) }
        assertTrue(after > 0.01f)
    }

    private fun score(): Score {
        val notes = (0 until 4).map { index ->
            FinalNote(pitch = 60 + index * 2, onsetSec = index * 0.5, durationSec = 0.45, amplitude = 0.8f)
        }
        return ScoreBuilder.build(
            projectId = "p1",
            title = "再生テスト",
            notes = notes,
            durationSec = 2.5,
        )
    }

    @Test
    fun `score rendering follows the score length`() {
        val score = score()
        val renderer = ScoreRenderer(sampleRate)
        val pcm = renderer.render(score, speed = 1.0)
        val expected = renderer.durationSec(score, 1.0)
        assertEquals(expected, pcm.size.toDouble() / sampleRate, 0.02)
        assertTrue(pcm.isNotEmpty())
        assertTrue(pcm.any { it.toInt() != 0 })
    }

    @Test
    fun `double speed halves the musical length and keeps the tail`() {
        val score = score()
        val renderer = ScoreRenderer(sampleRate)
        val normal = renderer.render(score, speed = 1.0).size
        val fast = renderer.render(score, speed = 2.0).size
        val tail = (ScoreRenderer.TAIL_SEC * sampleRate).toInt()
        val expected = (normal - tail) / 2.0 + tail
        assertEquals(expected, fast.toDouble(), normal * 0.03)
    }

    @Test
    fun `an empty score renders nothing`() {
        val empty = ScoreBuilder.build("p1", "empty", emptyList(), 0.0)
        assertTrue(ScoreRenderer(sampleRate).render(empty, 1.0).isEmpty())
    }
}
