package com.yuushilabs.pianosheet.feature.score

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yuushilabs.pianosheet.PianoSheetApplication
import com.yuushilabs.pianosheet.domain.model.ProjectDetail
import com.yuushilabs.pianosheet.domain.export.MidiExporter
import com.yuushilabs.pianosheet.domain.export.MusicXmlExporter
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.playback.OriginalPlayer
import com.yuushilabs.pianosheet.playback.ScorePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PlaybackMode { SCORE, ORIGINAL }

data class ScoreUiState(
    val loading: Boolean = true,
    val title: String = "",
    val score: Score? = null,
    val recordingPath: String? = null,
    val mode: PlaybackMode = PlaybackMode.SCORE,
    val playing: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val speed: Float = 1.0f,
    val beat: Double = 0.0,
    val measureIndex: Int = -1,
    val activeNoteIds: Set<String> = emptySet(),
    val message: String? = null,
    val errorMessage: String? = null,
) {
    val hasScore: Boolean get() = score != null && score.notes.isNotEmpty()
    val currentMeasure: Int get() = measureIndex + 1
}

/**
 * SCR-004 (spec §3.6, §7): finished score, Score/Original playback and the raw
 * WAV export entry points.
 */
class ScoreViewModel(
    application: Application,
    private val projectId: String,
) : AndroidViewModel(application) {

    private val container = (application as PianoSheetApplication).container
    private val scorePlayer = ScorePlayer()
    private val originalPlayer = OriginalPlayer(application.applicationContext)

    private val _state = MutableStateFlow(ScoreUiState())
    val state: StateFlow<ScoreUiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var detail: ProjectDetail? = null

    fun load() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { container.projectRepository.loadProject(projectId) }.getOrNull()
            }
            detail = loaded
            if (loaded == null) {
                _state.update { it.copy(loading = false, errorMessage = "プロジェクトを読み込めませんでした") }
                return@launch
            }
            val score = loaded.score
            if (score == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        title = loaded.project.title,
                        recordingPath = loaded.recording?.storagePath,
                        errorMessage = "まだ楽譜がありません。解析を実行してください。",
                    )
                }
                return@launch
            }
            withContext(Dispatchers.Default) { scorePlayer.load(score, _state.value.speed.toDouble()) }
            val path = loaded.recording?.storagePath
            if (!path.isNullOrBlank()) {
                originalPlayer.prepare(
                    path = path,
                    onReady = { updateDurations() },
                    onError = { message -> _state.update { it.copy(errorMessage = message) } },
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    title = loaded.project.title,
                    score = score,
                    recordingPath = path,
                    durationSec = scorePlayer.durationSec,
                )
            }
            startTicker()
        }
    }

    fun setMode(mode: PlaybackMode) {
        if (mode == _state.value.mode) return
        pauseAll()
        _state.update { it.copy(mode = mode, positionSec = 0.0, beat = 0.0, measureIndex = -1, activeNoteIds = emptySet()) }
        updateDurations()
    }

    fun togglePlay() {
        val current = _state.value
        if (!current.hasScore) return
        if (current.playing) {
            pauseAll()
        } else {
            if (current.mode == PlaybackMode.SCORE) {
                if (scorePlayer.isFinished) scorePlayer.seekTo(0.0)
                scorePlayer.play()
            } else {
                originalPlayer.play()
            }
            _state.update { it.copy(playing = true) }
            startTicker()
        }
    }

    fun seekTo(seconds: Double) {
        val current = _state.value
        val duration = current.durationSec.coerceAtLeast(0.0)
        val target = seconds.coerceIn(0.0, duration)
        if (current.mode == PlaybackMode.SCORE) {
            scorePlayer.seekTo(target)
        } else {
            originalPlayer.seekTo(target)
        }
        updatePosition(target)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 1.5f)
        _state.update { it.copy(speed = clamped) }
        originalPlayer.setSpeed(clamped)
        // The score is re-rendered for the new tempo; keep that off the UI thread.
        viewModelScope.launch {
            withContext(Dispatchers.Default) { scorePlayer.setSpeed(clamped.toDouble()) }
            updateDurations()
        }
    }

    fun saveWav(uri: Uri) {
        val path = _state.value.recordingPath ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.wavExporter.save(File(path), uri)
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        onSuccess = { bytes -> "生録音(WAV)を保存しました（${bytes / 1024} KB）" },
                        onFailure = { error -> "保存できませんでした: ${error.message ?: "原因不明"}" },
                    ),
                )
            }
        }
    }

    /** Writes the score as MusicXML and copies it where the user chose. */
    fun saveMusicXml(uri: Uri) = saveExport(uri, "MusicXML") { score ->
        container.scoreFileExporter.musicXmlFile(score)
    }

    /** Writes the score as a Standard MIDI File and copies it to [uri]. */
    fun saveMidi(uri: Uri) = saveExport(uri, "MIDI") { score ->
        container.scoreFileExporter.midiFile(score)
    }

    fun shareMusicXmlIntent(): Intent? = shareExport("MusicXML", MusicXmlExporter.MIME_TYPE) { score ->
        container.scoreFileExporter.musicXmlFile(score)
    }

    fun shareMidiIntent(): Intent? = shareExport("MIDI", MidiExporter.MIME_TYPE) { score ->
        container.scoreFileExporter.midiFile(score)
    }

    /** MusicXML straight into `Documents/PianoSheet`, no folder picking (0.4.1). */
    fun saveMusicXmlToSharedFolder() {
        val score = _state.value.score ?: return
        val name = container.scoreFileExporter.suggestedFileName(score, MusicXmlExporter.FILE_EXTENSION)
        saveToSharedFolder("MusicXML", MusicXmlExporter.MIME_TYPE) {
            MusicXmlExporter.toMusicXml(score).toByteArray(Charsets.UTF_8) to name
        }
    }

    /** MIDI straight into `Documents/PianoSheet`, no folder picking (0.4.1). */
    fun saveMidiToSharedFolder() {
        val score = _state.value.score ?: return
        val name = container.scoreFileExporter.suggestedFileName(score, MidiExporter.FILE_EXTENSION)
        saveToSharedFolder("MIDI", MidiExporter.MIME_TYPE) { MidiExporter.toBytes(score) to name }
    }

    /** Copies the untouched take into `Music/PianoSheet` again (0.4.1). */
    fun saveWavToSharedFolder() {
        val path = _state.value.recordingPath ?: return
        val project = detail?.project
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val saved = container.sharedAudioStorage.saveRecording(
                    source = File(path),
                    projectId = project?.id ?: projectId,
                    capturedAt = project?.createdAt ?: System.currentTimeMillis(),
                )
                saved.getOrNull()?.let { file ->
                    runCatching { container.projectRepository.updateSharedPath(projectId, file.displayPath) }
                }
                saved
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        onSuccess = { file -> "生録音を共有フォルダに保存しました: ${file.displayPath}" },
                        onFailure = { error -> error.message ?: "共有フォルダに保存できませんでした" },
                    ),
                )
            }
        }
    }

    private fun saveToSharedFolder(
        label: String,
        mimeType: String,
        build: () -> Pair<ByteArray, String>,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { build() }.mapCatching { (bytes, name) ->
                    container.sharedAudioStorage.saveDocument(bytes, name, mimeType).getOrThrow()
                }
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        onSuccess = { file -> "$label を共有フォルダに保存しました: ${file.displayPath}" },
                        onFailure = { error ->
                            "$label を共有フォルダに保存できませんでした: ${error.message ?: "原因不明"}"
                        },
                    ),
                )
            }
        }
    }

    fun musicXmlSuggestedName(): String {
        val score = _state.value.score ?: return "PianoSheet.musicxml"
        return container.scoreFileExporter.suggestedFileName(score, MusicXmlExporter.FILE_EXTENSION)
    }

    fun midiSuggestedName(): String {
        val score = _state.value.score ?: return "PianoSheet.mid"
        return container.scoreFileExporter.suggestedFileName(score, MidiExporter.FILE_EXTENSION)
    }

    private fun saveExport(uri: Uri, label: String, build: (Score) -> Result<File>) {
        val score = _state.value.score ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                build(score).mapCatching { file -> container.scoreFileExporter.save(file, uri).getOrThrow() }
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        onSuccess = { bytes -> label + " を保存しました（" + (bytes / 1024) + " KB）" },
                        onFailure = { error -> label + " を保存できませんでした: " + (error.message ?: "原因不明") },
                    ),
                )
            }
        }
    }

    private fun shareExport(label: String, mimeType: String, build: (Score) -> Result<File>): Intent? {
        val score = _state.value.score ?: return null
        val file = build(score).getOrNull() ?: run {
            _state.update { it.copy(message = label + " を作成できませんでした") }
            return null
        }
        val title = _state.value.title.ifEmpty { "PianoSheet" }
        val intent = container.scoreFileExporter.shareIntent(file, mimeType, title, label + " を共有")
        if (intent == null) {
            _state.update { it.copy(message = label + " を共有できませんでした") }
        }
        return intent
    }

    fun shareWavIntent(): Intent? {
        val path = _state.value.recordingPath ?: return null
        return container.wavExporter.shareIntent(File(path), _state.value.title.ifEmpty { "PianoSheet" })
    }

    fun suggestedFileName(): String = container.wavExporter.suggestedFileName(
        title = _state.value.title.ifEmpty { "PianoSheet" },
        extension = detail?.recording?.format ?: "wav",
    )

    fun clearMessage() {
        _state.update { it.copy(message = null, errorMessage = null) }
    }

    private fun pauseAll() {
        scorePlayer.pause()
        originalPlayer.pause()
        _state.update { it.copy(playing = false) }
    }

    private fun updateDurations() {
        val current = _state.value
        _state.update {
            it.copy(
                durationSec = when (current.mode) {
                    PlaybackMode.SCORE -> scorePlayer.durationSec
                    PlaybackMode.ORIGINAL -> originalPlayer.durationSec.takeIf { value -> value > 0 } ?: scorePlayer.durationSec
                },
            )
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val current = _state.value
                if (!current.playing) break
                val position = when (current.mode) {
                    PlaybackMode.SCORE -> scorePlayer.positionSec
                    PlaybackMode.ORIGINAL -> originalPlayer.positionSec
                }
                updatePosition(position)
                val finished = when (current.mode) {
                    PlaybackMode.SCORE -> scorePlayer.isFinished
                    PlaybackMode.ORIGINAL -> current.durationSec > 0 && position >= current.durationSec - 0.05
                }
                if (finished) {
                    _state.update { it.copy(playing = false, positionSec = 0.0) }
                    break
                }
                delay(90)
            }
        }
    }

    private fun updatePosition(positionSec: Double) {
        val score = _state.value.score ?: return
        val beat = when (_state.value.mode) {
            PlaybackMode.SCORE -> positionSec / score.beatDurationSec
            PlaybackMode.ORIGINAL -> score.secToBeat(positionSec)
        }
        val measure = score.measureAtBeat(beat.coerceAtLeast(0.0))
        val active = if (beat < 0.0) emptyList() else score.notesAtBeat(beat)
        _state.update {
            it.copy(
                positionSec = positionSec,
                beat = beat,
                measureIndex = measure?.index ?: -1,
                activeNoteIds = active.map { note -> note.id }.toSet(),
            )
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        scorePlayer.release()
        originalPlayer.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ScoreViewModel"
    }
}

class ScoreViewModelFactory(
    private val application: Application,
    private val projectId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        ScoreViewModel(application, projectId) as T
}
