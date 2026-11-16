package com.yuushilabs.pianosheet.playback

import com.yuushilabs.pianosheet.domain.score.Score
import kotlin.math.roundToInt

/**
 * Renders a finished [Score] to 16-bit PCM that [ScorePlayer] feeds to
 * AudioTrack (spec §7.1).
 *
 * Tempo changes are done by re-rendering with a different [speed], which changes
 * the *time* axis only: pitch stays where the score says, unlike resampling a
 * finished buffer.  The score's own beat grid is the playback timeline, so the
 * playback position can be mapped to a beat (and therefore to the highlighted
 * measure) without any extra bookkeeping.
 */
class ScoreRenderer(private val sampleRate: Int = 44100) {

    fun render(score: Score, speed: Double = 1.0): ShortArray {
        val rate = 1.0 / speed.coerceIn(0.25, 4.0)
        val beatSec = score.beatDurationSec * rate
        if (score.notes.isEmpty()) return ShortArray(0)
        val lastSec = score.notes.maxOfOrNull { it.startBeat * beatSec + it.durationBeats * beatSec } ?: 0.0
        val totalSec = lastSec + TAIL_SEC
        val buffer = FloatArray((totalSec * sampleRate).roundToInt().coerceAtLeast(1))
        val sorted = score.notes.sortedBy { it.startBeat }
        for (note in sorted) {
            val startSample = (note.startBeat * beatSec * sampleRate).roundToInt()
            PianoSynth.addNote(
                out = buffer,
                startSample = startSample,
                sampleRate = sampleRate,
                midiPitch = note.midiPitch,
                durationSec = note.durationBeats * beatSec,
                velocity = note.velocity,
            )
        }
        PianoSynth.finish(buffer)
        return toPcm16(buffer)
    }

    /** Length in seconds of a render, for the seek bar before playback starts. */
    fun durationSec(score: Score, speed: Double = 1.0): Double {
        if (score.notes.isEmpty()) return 0.0
        val rate = 1.0 / speed.coerceIn(0.25, 4.0)
        val beatSec = score.beatDurationSec * rate
        val lastSec = score.notes.maxOfOrNull { it.startBeat * beatSec + it.durationBeats * beatSec } ?: 0.0
        return lastSec + TAIL_SEC
    }

    private fun toPcm16(buffer: FloatArray): ShortArray {
        val pcm = ShortArray(buffer.size)
        for (i in buffer.indices) {
            val value = (buffer[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            pcm[i] = value.coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    companion object {
        /** Let the last notes ring out instead of ending on a click. */
        const val TAIL_SEC = 0.45
    }
}
