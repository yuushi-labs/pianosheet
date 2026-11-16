package com.yuushilabs.pianosheet.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Microphone capture with the source selection from spec §20.2.
 *
 * `UNPROCESSED` is preferred so the OS speech processing (NS / AEC / AGC) stays
 * out of the piano signal; `MIC` is the fallback on devices that do not expose
 * it.  Samples are delivered on a dedicated thread as float mono, and each
 * buffer is also announced with its RMS level for the input meter.
 */
class AudioCapture {

    data class CaptureInfo(
        val sampleRate: Int,
        val source: String,
        val unprocessed: Boolean,
    )

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val effects = ArrayList<Any>()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts capture.  [onChunk] is called on the capture thread with 100 ms of
     * audio; [onLevel] receives a 0..1 meter value.
     */
    fun start(
        context: Context,
        onReady: (CaptureInfo) -> Unit,
        onChunk: (FloatArray) -> Unit,
        onLevel: (Float) -> Unit,
        onError: (String) -> Unit,
    ): CaptureInfo? {
        if (running) return null
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedSupported =
            manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val candidates = if (unprocessedSupported) {
            listOf(
                CaptureCandidate(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", true),
                CaptureCandidate(MediaRecorder.AudioSource.MIC, "MIC", false),
            )
        } else {
            listOf(CaptureCandidate(MediaRecorder.AudioSource.MIC, "MIC", false))
        }

        for (rate in intArrayOf(22050, 44100, 48000)) {
            for (candidate in candidates) {
                val started = tryStart(rate, candidate, onReady, onChunk, onLevel)
                if (started != null) {
                    return CaptureInfo(rate, candidate.label, candidate.unprocessed)
                }
            }
        }
        onError("マイクを開始できませんでした（他アプリが使用中の可能性があります）")
        return null
    }

    private fun tryStart(
        rate: Int,
        candidate: CaptureCandidate,
        onReady: (CaptureInfo) -> Unit,
        onChunk: (FloatArray) -> Unit,
        onLevel: (Float) -> Unit,
    ): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val bufferBytes = max(minBuffer * 2, rate / 2 * 2)
        val recorder = try {
            AudioRecord(
                candidate.source,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (error: Exception) {
            Log.w(TAG, "AudioRecord($rate, ${candidate.label}) failed", error)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        try {
            disablePlatformEffects(recorder.audioSessionId)
        } catch (error: Exception) {
            Log.w(TAG, "could not disable platform effects", error)
        }
        try {
            recorder.startRecording()
        } catch (error: Exception) {
            Log.w(TAG, "startRecording failed", error)
            recorder.release()
            return null
        }

        record = recorder
        running = true
        // Give the caller the real format before any audio arrives, so the WAV
        // writer is ready when the first chunk comes.
        try {
            onReady(CaptureInfo(rate, candidate.label, candidate.unprocessed))
        } catch (error: Exception) {
            Log.w(TAG, "onReady failed", error)
            running = false
            record = null
            recorder.release()
            return null
        }
        val chunkSamples = max(1, rate / 10)
        val thread = Thread({
            val shorts = ShortArray(chunkSamples)
            while (running) {
                val read = try {
                    recorder.read(shorts, 0, shorts.size)
                } catch (error: Exception) {
                    Log.w(TAG, "read failed", error)
                    -1
                }
                if (read <= 0) {
                    if (!running) break
                    continue
                }
                val floats = FloatArray(read)
                var sum = 0.0
                for (i in 0 until read) {
                    val value = shorts[i] / 32768f
                    floats[i] = value
                    sum += value.toDouble() * value.toDouble()
                }
                onLevel((sqrt(sum / read) * 4.0).toFloat().coerceIn(0f, 1f))
                onChunk(floats)
            }
        }, "pianosheet-audio-capture")
        thread.priority = Thread.MAX_PRIORITY
        thread.start()
        this.thread = thread
        return recorder
    }

    private fun disablePlatformEffects(sessionId: Int) {
        // These are the speech-oriented effects the spec forbids: they treat a
        // piano's decay tails and upper partials as noise.
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.let {
                it.enabled = false
                effects.add(it)
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let {
                it.enabled = false
                effects.add(it)
            }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let {
                it.enabled = false
                effects.add(it)
            }
        }
    }

    fun stop() {
        running = false
        // Stop first: a blocking read() returns as soon as the recorder stops, so the
        // capture thread can finish before the buffers are released.
        record?.let { recorder ->
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                Unit
            } catch (error: Exception) {
                Log.w(TAG, "stop failed", error)
            }
        }
        thread?.let { worker ->
            try {
                worker.join(600)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
        record?.release()
        record = null
        for (effect in effects) {
            try {
                when (effect) {
                    is AutomaticGainControl -> effect.release()
                    is NoiseSuppressor -> effect.release()
                    is AcousticEchoCanceler -> effect.release()
                }
            } catch (_: Exception) {
            }
        }
        effects.clear()
    }

    val isRunning: Boolean get() = running

    private data class CaptureCandidate(
        val source: Int,
        val label: String,
        val unprocessed: Boolean,
    )

    companion object {
        private const val TAG = "AudioCapture"
    }
}
