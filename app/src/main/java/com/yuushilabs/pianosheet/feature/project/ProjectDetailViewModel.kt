package com.yuushilabs.pianosheet.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yuushilabs.pianosheet.app.AppContainer
import com.yuushilabs.pianosheet.domain.model.ProjectDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Project detail state: the saved take, the finished score when it exists, and
 * the raw-WAV export actions (spec §8).
 */
class ProjectDetailViewModel(
    private val container: AppContainer,
    private val projectId: String,
) : ViewModel() {

    private val _detail = MutableStateFlow<ProjectDetail?>(null)
    val detail: StateFlow<ProjectDetail?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Reloads whenever the screen is shown again (e.g. after an analysis). */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _detail.value = withContext(Dispatchers.IO) {
                runCatching { container.projectRepository.loadProject(projectId) }.getOrNull()
            }
            _loading.value = false
        }
    }

    init {
        refresh()
    }

    fun saveWav(uri: android.net.Uri) {
        val path = _detail.value?.recording?.storagePath ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.wavExporter.save(File(path), uri)
            }
            _message.update {
                result.fold(
                    onSuccess = { bytes -> "生録音(WAV)を保存しました（${bytes / 1024} KB）" },
                    onFailure = { error -> "保存できませんでした: ${error.message ?: "原因不明"}" },
                )
            }
        }
    }

    fun shareWavIntent(): android.content.Intent? {
        val path = _detail.value?.recording?.storagePath ?: return null
        val title = _detail.value?.project?.title ?: "PianoSheet"
        return container.wavExporter.shareIntent(File(path), title)
    }

    fun suggestedFileName(): String = container.wavExporter.suggestedFileName(
        title = _detail.value?.project?.title ?: "PianoSheet",
        extension = _detail.value?.recording?.format ?: "wav",
    )

    fun clearMessage() {
        _message.value = null
    }
}

class ProjectDetailViewModelFactory(
    private val container: AppContainer,
    private val projectId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ProjectDetailViewModel(container, projectId) as T
}
