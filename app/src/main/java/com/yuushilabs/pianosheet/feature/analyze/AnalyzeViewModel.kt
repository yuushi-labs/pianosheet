package com.yuushilabs.pianosheet.feature.analyze

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.pianosheet.PianoSheetApplication
import com.yuushilabs.pianosheet.data.denoiseConfigForFinal
import com.yuushilabs.pianosheet.domain.final.AnalysisCancelledException
import com.yuushilabs.pianosheet.domain.final.FinalAnalysisOptions
import com.yuushilabs.pianosheet.domain.final.FinalProgress
import com.yuushilabs.pianosheet.domain.final.FinalStage
import com.yuushilabs.pianosheet.domain.model.HumMode
import com.yuushilabs.pianosheet.domain.model.ProjectStatus
import com.yuushilabs.pianosheet.domain.score.QuantizationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AnalyzeUiState(
    val stage: FinalStage = FinalStage.PREPARING,
    val fraction: Double = 0.0,
    val detail: String = "",
    val running: Boolean = true,
    val cancelled: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    val title: String = "",
    val windowCount: Int = 0,
    val analysisMs: Long = 0L,
    val suppressedStartup: Int = 0,
    val noteCount: Int = 0,
    val denoiseProfile: String? = null,
) {
    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)
}

/**
 * SCR-003 (spec §3.5): runs the final analysis on the saved WAV and stores the
 * finished score.  Progress is driven by the real pipeline (read -> denoise ->
 * window n/N -> score), not by a timer, and the job can be cancelled without
 * touching the recording.
 */
class AnalyzeViewModel(
    application: Application,
    private val projectId: String,
) : AndroidViewModel(application) {

    private val container = (application as PianoSheetApplication).container

    private val _state = MutableStateFlow(AnalyzeUiState())
    val state: StateFlow<AnalyzeUiState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var cancelRequested = false
    private var liveNoteCount = 0

    /** [force] = re-analyse even when a finished score already exists (spec §6.3). */
    fun start(force: Boolean = false) {
        if (job?.isActive == true) return
        cancelRequested = false
        _state.value = AnalyzeUiState(running = true)
        job = viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    container.projectRepository.loadProject(projectId)
                }
                if (detail == null) {
                    fail("このプロジェクトを読み込めませんでした")
                    return@launch
                }
                _state.update { it.copy(title = detail.project.title) }
                liveNoteCount = detail.notes.size
                if (!force && detail.score != null) {
                    _state.update {
                        it.copy(done = true, running = false, fraction = 1.0, noteCount = detail.score.noteCount)
                    }
                    return@launch
                }
                val path = detail.recording?.storagePath
                if (path.isNullOrBlank()) {
                    fail("録音ファイルが見つかりません")
                    return@launch
                }
                val file = File(path)
                if (!file.exists()) {
                    fail("録音ファイルが見つかりません（${file.name}）")
                    return@launch
                }

                val settings = container.settingsRepository.settings.first()
                val options = FinalAnalysisOptions(
                    denoise = settings.denoiseConfigForFinal(),
                    quantization = QuantizationSettings(),
                    humAutoDetect = settings.humMode == HumMode.AUTO,
                )
                withContext(Dispatchers.IO) {
                    container.projectRepository.updateStatus(
                        projectId,
                        ProjectStatus.PROCESSING,
                        detail.notes.size,
                    )
                }

                val result = withContext(Dispatchers.Default) {
                    container.finalTranscriber.transcribe(
                        file = file,
                        projectId = projectId,
                        title = detail.project.title,
                        liveEvents = detail.notes,
                        options = options,
                        shouldCancel = { cancelRequested },
                        onProgress = { progress -> applyProgress(progress) },
                    )
                }
                if (cancelRequested) {
                    cancelAnalysis()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    container.projectRepository.saveScore(
                        score = result.score,
                        denoiseProfile = result.denoiseProfile,
                        analysisMs = result.analysisMs,
                    )
                }
                _state.update {
                    it.copy(
                        running = false,
                        done = true,
                        fraction = 1.0,
                        windowCount = result.windowCount,
                        analysisMs = result.analysisMs,
                        suppressedStartup = result.startupSuppressed,
                        noteCount = result.score.noteCount,
                        denoiseProfile = result.denoiseProfile,
                    )
                }
            } catch (error: AnalysisCancelledException) {
                cancelAnalysis()
            } catch (error: Exception) {
                Log.e(TAG, "final analysis failed", error)
                withContext(Dispatchers.IO) {
                    runCatching {
                        container.projectRepository.updateStatus(projectId, ProjectStatus.DRAFT, liveNoteCount)
                    }
                }
                fail(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun applyProgress(progress: FinalProgress) {
        _state.update {
            it.copy(
                stage = progress.stage,
                fraction = progress.fraction.coerceIn(0.0, 1.0),
                detail = progress.detail,
            )
        }
    }

    private suspend fun fail(message: String) {
        _state.update { it.copy(running = false, error = message) }
    }

    fun retry() {
        start(force = true)
    }

    fun cancel() {
        cancelRequested = true
        job?.cancel()
        _state.update { it.copy(running = false, cancelled = true) }
    }

    private suspend fun cancelAnalysis() {
        withContext(Dispatchers.IO) {
            runCatching {
                container.projectRepository.updateStatus(projectId, ProjectStatus.DRAFT, liveNoteCount)
            }
        }
        _state.update { it.copy(running = false, cancelled = true) }
    }

    override fun onCleared() {
        cancelRequested = true
        super.onCleared()
    }

    companion object {
        private const val TAG = "AnalyzeViewModel"
    }
}

class AnalyzeViewModelFactory(
    private val application: Application,
    private val projectId: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        AnalyzeViewModel(application, projectId) as T
}
