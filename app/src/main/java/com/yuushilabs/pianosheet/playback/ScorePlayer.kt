package com.yuushilabs.pianosheet.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.yuushilabs.pianosheet.domain.score.Score
import kotlin.math.min

/**
 * Plays the finished score with the built-in piano synth (spec §7.1).
 *
 * The whole score is rendered once per speed into 16-bit PCM and then streamed
 * to AudioTrack, which keeps pause / seek / speed simple and makes the playback
 * position exact enough to highlight the current measure.  Rendering a minute of
 * dense piano takes well under a second on a phone, and a speed change simply
 * renders again from the current position.
 */
class ScorePlayer(private val sampleRate: Int = 22050) {

    private val renderer = ScoreRenderer(sampleRate)
    private val lock = Any()

    private var score: Score? = null
    private var pcm: ShortArray = ShortArray(0)
    private var track: AudioTrack? = null
    private var writer: Thread? = null

    @Volatile private var writeIndex = 0
    @Volatile private var playing = false
    @Volatile private var speed = 1.0
    @Volatile private var prepared = false

    val isReady: Boolean get() = prepared && pcm.isNotEmpty()
    val isPlaying: Boolean get() = playing

    /** Duration in score seconds (the seek bar's range). */
    val durationSec: Double
        get() = if (pcm.isEmpty()) 0.0 else pcm.size.toDouble() / sampleRate * speed

    /** Current position in score seconds. */
    val positionSec: Double
        get() = if (pcm.isEmpty()) 0.0 else (writeIndex.toDouble() / sampleRate * speed).coerceAtMost(durationSec)

    /** True once the last sample has been written. */
    val isFinished: Boolean get() = !playing && pcm.isNotEmpty() && writeIndex >= pcm.size

    fun load(score: Score, speed: Double = 1.0) {
        stopAndRelease()
        this.score = score
        this.speed = speed.coerceIn(0.25, 4.0)
        pcm = renderer.render(score, this.speed)
        writeIndex = 0
        prepared = pcm.isNotEmpty()
    }

    fun play() {
        val data = pcm
        if (data.isEmpty()) return
        if (playing) return
        if (writeIndex >= data.size) writeIndex = 0
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val bufferBytes = minBuffer * 2
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        track = audioTrack
        playing = true
        audioTrack.play()
        val thread = Thread({
            val chunk = ShortArray(1024)
            while (playing) {
                val start = writeIndex
                if (start >= data.size) break
                val count = min(chunk.size, data.size - start)
                System.arraycopy(data, start, chunk, 0, count)
                val written = audioTrack.write(chunk, 0, count)
                if (written <= 0) break
                writeIndex = start + written
            }
            playing = false
        }, "pianosheet-score-player")
        thread.priority = Thread.NORM_PRIORITY
        writer = thread
        thread.start()
    }

    fun pause() {
        playing = false
        writer?.let { thread ->
            try {
                thread.join(300)
            } catch (_: InterruptedException) {
            }
        }
        writer = null
        track?.let { audioTrack ->
            try {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
                audioTrack.flush()
            } catch (_: Exception) {
            }
            audioTrack.release()
        }
        track = null
    }

    fun seekTo(scoreSeconds: Double) {
        val wasPlaying = playing
        pause()
        val clamped = scoreSeconds.coerceIn(0.0, durationSec)
        writeIndex = (clamped / speed * sampleRate).toInt().coerceIn(0, pcm.size)
        if (wasPlaying) play()
    }

    /** Re-renders at the new speed and keeps the musical position. */
    fun setSpeed(newSpeed: Double) {
        val currentScore = score ?: return
        val position = positionSec
        val wasPlaying = playing
        load(currentScore, newSpeed)
        writeIndex = (position / speed * sampleRate).toInt().coerceIn(0, pcm.size)
        if (wasPlaying) play()
    }

    fun stopAndRelease() {
        playing = false
        writer?.let { thread ->
            try {
                thread.join(300)
            } catch (_: InterruptedException) {
            }
        }
        writer = null
        track?.let { audioTrack ->
            try {
                audioTrack.pause()
                audioTrack.flush()
            } catch (_: Exception) {
            }
            audioTrack.release()
        }
        track = null
        writeIndex = 0
    }

    fun release() {
        stopAndRelease()
        pcm = ShortArray(0)
        prepared = false
        score = null
    }

    companion object {
        val STREAM = AudioManager.STREAM_MUSIC
    }
}
