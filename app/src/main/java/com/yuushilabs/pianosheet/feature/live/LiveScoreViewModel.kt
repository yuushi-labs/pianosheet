package com.yuushilabs.pianosheet.feature.live

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.pianosheet.PianoSheetApplication
import com.yuushilabs.pianosheet.core.audio.AudioCapture
import com.yuushilabs.pianosheet.core.audio.WavFileWriter
import com.yuushilabs.pianosheet.core.dsp.DenoiseConfig
import com.yuushilabs.pianosheet.core.dsp.StreamingDenoiser
import com.yuushilabs.pianosheet.core.dsp.StreamingResampler
import com.yuushilabs.pianosheet.core.inference.BasicPitchDetector
import com.yuushilabs.pianosheet.data.denoiseConfigForLive
import com.yuushilabs.pianosheet.domain.live.LiveConfig
import com.yuushilabs.pianosheet.domain.live.LiveTranscriptionEngine
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.NoteSource
import com.yuushilabs.pianosheet.domain.model.ProjectStatus
import com.yuushilabs.pianosheet.domain.model.ProjectSummary
import com.yuushilabs.pianosheet.domain.model.RecordingInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LivePhase {
    IDLE,
    PREPARING,
    RECORDING,
    SAVING,
    SAVED,
    ERROR,
}

data class LiveUiState(
    val phase: LivePhase = LivePhase.IDLE,
    val notes: List<NoteEvent> = emptyList(),
    val elapsedMs: Long = 0L,
    val activePitches: Set<Int> = emptySet(),
    val inputLevel: Float = 0f,
    val captureSource: String = "",
    val unprocessed: Boolean = true,
    val latencyP95Ms: Double? = null,
    val stepComputeMs: Double? = null,
    val denoiseProfile: String? = null,
    val errorMessage: String? = null,
    val savedProjectId: String? = null,
) {
    val noteCount: Int get() = notes.size
    val isSlow: Boolean get() = (latencyP95Ms ?: 0.0) > 2000.0
}

/**
 * Owns one live recording session: microphone, streaming engine, raw WAV and
 * the DB write when the take ends.  It lives in the ViewModel so a rotation or
 * a trip to the settings screen never restarts the recording (spec §2.3, §9.6).
 */
class LiveScoreViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PianoSheetApplication).container

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val capture = AudioCapture()
    private var engine: LiveTranscriptionEngine? = null
    private var writer: WavFileWriter? = null
    private var wavFile: File? = null
    private var captureRate = 22050
    private var captureSource = ""
    private var captureUnprocessed = true
    private var denoiseProfile: String? = null

    private var analysisExecutor: ExecutorService? = null
    private var ticker: Job? = null
    private var projectId = UUID.randomUUID().toString()
    private var projectCreatedAt = 0L
    private var startedAt = 0L
    private val recentNotes = ArrayDeque<Pair<Long, Int>>()

    @Volatile
    private var liveDenoiser: StreamingDenoiser? = null

    @Volatile
    private var liveResampler: StreamingResampler? = null

    fun startRecording() {
        val phase = _state.value.phase
        if (phase == LivePhase.PREPARING || phase == LivePhase.RECORDING || phase == LivePhase.SAVING) return
        _state.update {
            it.copy(
                phase = LivePhase.PREPARING,
                notes = emptyList(),
                errorMessage = null,
                savedProjectId = null,
                elapsedMs = 0L,
                activePitches = emptySet(),
                latencyP95Ms = null,
                stepComputeMs = null,
            )
        }

        viewModelScope.launch {
            try {
                val settings = container.settingsRepository.settings.first()
                val denoise = settings.denoiseConfigForLive()
                denoiseProfile = denoise?.describeProfile()

                // Loading the ONNX session is the only slow part; it happens once
                // per process and never on the main thread.
                val model = withContext(Dispatchers.Default) { container.basicPitchModel }
                val newEngine = LiveTranscriptionEngine(LiveConfig(), BasicPitchDetector(model))
                newEngine.startSession()
                engine = newEngine
                liveDenoiser = denoise?.let { StreamingDenoiser(it) }

                val id = UUID.randomUUID().toString()
                projectId = id
                projectCreatedAt = System.currentTimeMillis()
                val file = container.audioStorage.newRecordingFile(id)
                wavFile = file
                val executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "pianosheet-analysis").apply { priority = Thread.NORM_PRIORITY }
                }
                analysisExecutor = executor

                val info = capture.start(
                    context = getApplication(),
                    onReady = { captureInfo ->
                        captureRate = captureInfo.sampleRate
                        captureSource = captureInfo.source
                        captureUnprocessed = captureInfo.unprocessed
                        liveResampler = if (captureInfo.sampleRate == 22050) null else StreamingResampler(captureInfo.sampleRate)
                        writer = WavFileWriter(file, captureInfo.sampleRate)
                    },
                    onChunk = { chunk -> onAudioChunk(chunk) },
                    onLevel = { level -> _state.update { it.copy(inputLevel = level) } },
                    onError = { message ->
                        _state.update { it.copy(phase = LivePhase.ERROR, errorMessage = message) }
                    },
                )

                if (info == null) {
                    if (_state.value.phase != LivePhase.ERROR) {
                        _state.update {
                            it.copy(phase = LivePhase.ERROR, errorMessage = "マイクを開始できませんでした")
                        }
                    }
                    cleanupSession()
                    return@launch
                }

                startedAt = SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(
                        phase = LivePhase.RECORDING,
                        captureSource = info.source,
                        unprocessed = info.unprocessed,
                        denoiseProfile = denoiseProfile,
                    )
                }
                startTicker()
            } catch (error: Exception) {
                Log.e(TAG, "startRecording failed", error)
                _state.update {
                    it.copy(
                        phase = LivePhase.ERROR,
                        errorMessage = "録音を開始できませんでした: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
                cleanupSession()
            }
        }
    }

    private fun onAudioChunk(chunk: FloatArray) {
        // The raw take is written exactly as captured (spec §20.1).
        try {
            writer?.write(chunk)
        } catch (error: Exception) {
            Log.w(TAG, "wav write failed", error)
        }
        val executor = analysisExecutor ?: return
        val engineRef = engine ?: return
        try {
            executor.execute {
                try {
                    var analysis = chunk
                    liveResampler?.let { analysis = it.process(analysis) }
                    liveDenoiser?.let { analysis = it.processChunk(analysis) }
                    if (analysis.isEmpty()) return@execute
                    val newEvents = engineRef.pushAudio(analysis)
                    if (newEvents.isNotEmpty()) publish(newEvents)
                } catch (error: Exception) {
                    Log.e(TAG, "analysis step failed", error)
                }
            }
        } catch (error: RuntimeException) {
            // Executor already shut down (take ending); the sample is simply not analysed.
            Log.d(TAG, "analysis queue closed: ${error.javaClass.simpleName}")
        }
    }

    private fun publish(newEvents: List<NoteEvent>) {
        val now = SystemClock.elapsedRealtime()
        synchronized(recentNotes) {
            newEvents.forEach { recentNotes.addLast(now to it.pitchMidi) }
            while (recentNotes.size > 64) recentNotes.removeFirst()
        }
        _state.update { current ->
            current.copy(
                notes = current.notes + newEvents,
                latencyP95Ms = engine?.latencyP95Ms(),
                stepComputeMs = engine?.lastComputeMs(),
            )
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val active = synchronized(recentNotes) {
                    while (recentNotes.isNotEmpty() && now - recentNotes.first().first > KEY_HOLD_MS) {
                        recentNotes.removeFirst()
                    }
                    recentNotes.map { it.second }.toSet()
                }
                val elapsed = if (startedAt == 0L) 0L else now - startedAt
                _state.update {
                    it.copy(
                        elapsedMs = elapsed,
                        activePitches = active,
                        latencyP95Ms = engine?.latencyP95Ms(),
                        stepComputeMs = engine?.lastComputeMs(),
                    )
                }
                delay(120)
            }
        }
    }

    fun stopRecording() {
        if (_state.value.phase != LivePhase.RECORDING) return
        _state.update { it.copy(phase = LivePhase.SAVING) }
        ticker?.cancel()
        ticker = null

        capture.stop()
        analysisExecutor?.let { executor ->
            executor.shutdown()
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        }
        analysisExecutor = null

        val engineRef = engine
        val tail = engineRef?.finishSession().orEmpty()
        if (tail.isNotEmpty()) publish(tail)
        val writerRef = writer
        writer = null
        try {
            writerRef?.close()
        } catch (error: Exception) {
            Log.w(TAG, "wav close failed", error)
        }

        val events = engineRef?.eventsSnapshot().orEmpty()
        val file = wavFile
        val duration = writerRef?.durationMs ?: 0L
        val rate = captureRate
        val source = captureSource
        val unprocessed = captureUnprocessed
        val profile = denoiseProfile

        if (file == null || !file.exists()) {
            _state.update { it.copy(phase = LivePhase.ERROR, errorMessage = "録音ファイルが見つかりません") }
            return
        }

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val summary = ProjectSummary(
                    id = projectId,
                    title = recordingTitle(projectCreatedAt),
                    createdAt = projectCreatedAt,
                    updatedAt = now,
                    status = ProjectStatus.DRAFT,
                    sourceType = NoteSource.MICROPHONE,
                    noteCount = events.size,
                    durationMs = duration,
                )
                val recording = RecordingInfo(
                    storagePath = file.absolutePath,
                    format = "wav",
                    sampleRate = rate,
                    channels = 1,
                    durationMs = duration,
                    fileSize = file.length(),
                    audioSource = source,
                    denoiseProfile = profile,
                    isUnprocessed = unprocessed,
                )
                withContext(Dispatchers.IO) {
                    container.projectRepository.saveSession(summary, recording, events)
                }
                _state.update {
                    it.copy(
                        phase = LivePhase.SAVED,
                        savedProjectId = projectId,
                        notes = events,
                        elapsedMs = duration,
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "saving the take failed", error)
                _state.update {
                    it.copy(
                        phase = LivePhase.ERROR,
                        errorMessage = "保存できませんでした: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun discardRecording() {
        ticker?.cancel()
        ticker = null
        capture.stop()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        container.audioStorage.delete(wavFile)
        resetSession()
    }

    fun dismissError() {
        _state.update { it.copy(phase = LivePhase.IDLE, errorMessage = null) }
    }

    private fun cleanupSession() {
        ticker?.cancel()
        ticker = null
        capture.stop()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        container.audioStorage.delete(wavFile)
    }

    private fun resetSession() {
        engine = null
        writer = null
        wavFile = null
        liveDenoiser = null
        liveResampler = null
        projectId = UUID.randomUUID().toString()
        startedAt = 0L
        synchronized(recentNotes) { recentNotes.clear() }
        _state.value = LiveUiState()
    }

    private fun recordingTitle(createdAt: Long): String {
        val formatter = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
        return "録音 ${formatter.format(Date(if (createdAt == 0L) System.currentTimeMillis() else createdAt))}"
    }

    override fun onCleared() {
        ticker?.cancel()
        capture.stop()
        analysisExecutor?.shutdownNow()
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "LiveScoreViewModel"
        private const val KEY_HOLD_MS = 380L
    }
}
