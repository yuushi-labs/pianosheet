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
    fun `the high register is darker than the low register`() {
        // A C7 must not put its upper partials at full level: that is the
        // "キンキン" whistle the user reported.
        val lowFourth = PianoSynth.partialAmplitude(4, midiPitch = 60)
        val highFourth = PianoSynth.partialAmplitude(4, midiPitch = 96)
        assertTrue("C7 4th partial $highFourth vs C4 $lowFourth", highFourth < lowFourth * 0.25)

        val fundamental = PianoSynth.partialAmplitude(1, midiPitch = 96)
        val whistle = PianoSynth.partialAmplitude(5, midiPitch = 96)
        assertTrue("5th partial of C7 must be >= 15 dB below its fundamental", whistle < fundamental * 0.18)

        // The low register keeps its bright spectrum.
        assertTrue(PianoSynth.partialAmplitude(3, midiPitch = 48) > PianoSynth.partialAmplitude(3, midiPitch = 96))
    }

    @Test
    fun `a dense chord is limited instead of clipped`() {
        val buffer = FloatArray(sampleRate * 2)
        for (midi in 60..72) {
            PianoSynth.addNote(buffer, 0, sampleRate, midi, 1.2, velocity = 1f)
        }
        val before = buffer.copyOf()
        PianoSynth.finish(buffer)
        assertTrue(buffer.maxOf { abs(it) } <= PianoSynth.PEAK + 1e-4f)
        // The limiter bends the peaks but must not silence the quiet part.
        val quietIndex = (sampleRate * 0.9).toInt()
        assertEquals(before[quietIndex], buffer[quietIndex], 1e-5f)
        assertTrue(buffer.maxOf { abs(it) } > 0.5f)
    }

    @Test
    fun `the note ends without a step at the end of the release`() {
        // 0.4.1 cut the waveform 55 ms after the key came up, when the exponential
        // still stood at ~6 % of the note: a broadband click at every note end,
        // which is the "tick riding on the right pitch" the user heard.
        val duration = 0.4
        val buffer = renderNote(60, duration)
        val gate = (duration * sampleRate).toInt()
        val end = ((duration + PianoSynth.RELEASE_SEC) * sampleRate).toInt()
        val sounding = (gate - 2000 until gate).maxOf { abs(buffer[it]) }
        val lastSamples = (end - 200 until end).map { abs(buffer[it]) }
        assertTrue("the note sounded while the key was down: $sounding", sounding > 0.05f)
        assertTrue(
            "no step at the end: last 200 samples peak ${lastSamples.max()} against $sounding",
            lastSamples.max() <= sounding * 0.02f,
        )
    }

    @Test
    fun `a released note keeps ringing like a damper`() {
        // With 19 ms of decay the note was effectively silent 40 ms after the key
        // came up; a damper takes ~150 ms.
        val duration = 0.5
        val buffer = renderNote(60, duration)
        val gate = (duration * sampleRate).toInt()
        fun level(from: Int, to: Int) = (from until to).sumOf { abs(buffer[it].toDouble()) } / (to - from)
        val sounding = level(gate - 2000, gate)
        val tail = level(gate + (0.10 * sampleRate).toInt(), gate + (0.14 * sampleRate).toInt())
        assertTrue(
            "the tail must still be audible 100 ms after the key: $tail vs $sounding",
            tail > sounding * 0.02,
        )
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
