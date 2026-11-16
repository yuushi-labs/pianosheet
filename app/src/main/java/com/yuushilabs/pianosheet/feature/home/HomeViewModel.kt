package com.yuushilabs.pianosheet.feature.home

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.pianosheet.PianoSheetApplication
import com.yuushilabs.pianosheet.domain.model.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PianoSheetApplication).container

    val projects: StateFlow<List<ProjectSummary>> = container.projectRepository
        .observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** SCR-001 "音声ファイルをアップロード": import now, analysis arrives in Phase 2. */
    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val id = UUID.randomUUID().toString()
                    val name = container.audioStorage.displayName(uri) ?: "取り込んだ音声"
                    val file = container.audioStorage.importFromUri(uri, id)
                        ?: return@runCatching null
                    container.projectRepository.createUploadedProject(
                        id = id,
                        title = name.substringBeforeLast('.', name),
                        file = file,
                        originalFileName = name,
                    )
                    name
                }.onFailure { Log.w(TAG, "import failed", it) }.getOrNull()
            }
            _message.value = if (result == null) {
                "音声ファイルを取り込めませんでした"
            } else {
                "「$result」を取り込みました。最終解析はPhase 2で追加します。"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        private const val TAG = "HomeViewModel"
    }
}
