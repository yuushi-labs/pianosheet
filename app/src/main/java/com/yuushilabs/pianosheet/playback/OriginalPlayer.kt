package com.yuushilabs.pianosheet.playback

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import java.io.File

/**
 * Plays the saved, unprocessed WAV (spec §7.2 "Original").
 *
 * MediaPlayer handles WAV natively, so the original take keeps its exact
 * sound — this is the reference the user compares the transcription against.
 */
class OriginalPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var prepared = false
    private var speed = 1f
    private var filePath: String? = null

    val isPrepared: Boolean get() = prepared
    val path: String? get() = filePath

    fun prepare(
        path: String,
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        release()
        val file = File(path)
        if (!file.exists()) {
            onError("録音ファイルが見つかりません")
            return
        }
        filePath = path
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener {
                prepared = true
                applySpeed()
                onReady()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra")
                prepared = false
                onError("元音声を再生できません（$what/$extra）")
                true
            }
            mediaPlayer.setOnCompletionListener { }
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "prepare failed", error)
            prepared = false
            release()
            onError("元音声を読み込めませんでした: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun play() {
        val mediaPlayer = player ?: return
        if (!prepared) return
        try {
            mediaPlayer.start()
        } catch (error: Exception) {
            Log.w(TAG, "play failed", error)
        }
    }

    fun pause() {
        val mediaPlayer = player ?: return
        if (!prepared) return
        try {
            if (mediaPlayer.isPlaying) mediaPlayer.pause()
        } catch (error: Exception) {
            Log.w(TAG, "pause failed", error)
        }
    }

    val isPlaying: Boolean
        get() = try {
            prepared && player?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    val positionSec: Double
        get() = try {
            if (!prepared) 0.0 else (player?.currentPosition ?: 0) / 1000.0
        } catch (_: Exception) {
            0.0
        }

    val durationSec: Double
        get() = try {
            if (!prepared) 0.0 else (player?.duration ?: 0) / 1000.0
        } catch (_: Exception) {
            0.0
        }

    fun seekTo(seconds: Double) {
        val mediaPlayer = player ?: return
        if (!prepared) return
        try {
            val target = (seconds.coerceAtLeast(0.0) * 1000).toInt()
            mediaPlayer.seekTo(target)
        } catch (error: Exception) {
            Log.w(TAG, "seek failed", error)
        }
    }

    /**
     * Playback speed.  MediaPlayer's speed resamples, so the pitch moves with it;
     * for the original take that is acceptable (the score player is the one that
     * must keep pitch).
     */
    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.25f, 2f)
        applySpeed()
    }

    private fun applySpeed() {
        val mediaPlayer = player ?: return
        if (!prepared) return
        try {
            mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (error: Exception) {
            // Some devices refuse before the first frame; keep playing at 1x.
            Log.d(TAG, "playbackParams unsupported: ${error.javaClass.simpleName}")
        }
    }

    fun release() {
        prepared = false
        player?.let { mediaPlayer ->
            try {
                mediaPlayer.reset()
                mediaPlayer.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }

    companion object {
        private const val TAG = "OriginalPlayer"
    }
}
