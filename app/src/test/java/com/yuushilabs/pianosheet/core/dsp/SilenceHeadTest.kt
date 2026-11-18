package com.yuushilabs.pianosheet.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Where a take becomes audible.
 *
 * The score's beat 0 is the first note of the analysis, so a model that reports
 * the microphone settling as notes needs to know which part of the recording was
 * still silence.  These cases pin that the level test follows the take's own
 * loudness, ignores a lone click, and never trims a take that starts with music.
 */
class SilenceHeadTest {

    private val rate = 16000

    /** A 220 Hz tone at [amplitude] for [seconds]. */
    private fun tone(seconds: Double, amplitude: Float = 0.05f): FloatArray {
        val samples = (seconds * rate).toInt()
        return FloatArray(samples) { index ->
            (amplitude * sin(2 * PI * 220.0 * index / rate)).toFloat()
        }
    }

    private fun silence(seconds: Double): FloatArray = FloatArray((seconds * rate).toInt())

    private fun cat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var cursor = 0
        for (part in parts) {
            part.copyInto(out, cursor)
            cursor += part.size
        }
        return out
    }

    @Test
    fun `a take that opens with silence is measured to its first sound`() {
        val audio = cat(silence(0.8), tone(1.0))
        val start = SilenceHead.firstSoundSec(audio, rate)
        assertTrue("first sound at $start should be 0.8 s", start in 0.78..0.86)
    }

    @Test
    fun `a quiet performance is not mistaken for silence`() {
        // A phone across the room: 0.004 peak-to-peak against a silent lead.
        val audio = cat(silence(0.5), tone(1.0, amplitude = 0.004f))
        val start = SilenceHead.firstSoundSec(audio, rate)
        assertTrue("first sound at $start should be 0.5 s", start in 0.48..0.56)
    }

    @Test
    fun `a lone click before the playing does not mark the beginning`() {
        val click = FloatArray((0.005 * rate).toInt()) { 0.4f }
        val audio = cat(silence(0.30), click, silence(0.55), tone(1.0))
        val start = SilenceHead.firstSoundSec(audio, rate)
        assertTrue("a click at 0.30 s must be skipped, got $start", start > 0.8)
    }

    @Test
    fun `a take that starts with music has no silent lead`() {
        val audio = tone(1.0)
        assertEquals(0.0, SilenceHead.firstSoundSec(audio, rate), 1e-9)
    }

    @Test
    fun `a steady room tone leaves the take alone`() {
        // Noise at 1 % of the music is exactly the threshold: the safe answer is
        // to keep the take untouched rather than cut the opening.
        val noise = FloatArray((1.5 * rate).toInt()) { index -> (((index * 7919) % 100) - 50) / 100f * 0.008f }
        val audio = cat(noise, tone(1.0, amplitude = 0.2f))
        assertTrue(SilenceHead.firstSoundSec(audio, rate) < 0.2)
    }

    @Test
    fun `a silent or empty take reports zero`() {
        assertEquals(0.0, SilenceHead.firstSoundSec(FloatArray(0), rate), 1e-9)
        assertEquals(0.0, SilenceHead.firstSoundSec(silence(2.0), rate), 1e-9)
    }
}
