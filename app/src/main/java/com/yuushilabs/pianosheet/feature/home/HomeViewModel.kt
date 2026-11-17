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

    /** Set once an imported file has a project, so the screen can start the analysis. */
    private val _importedProjectId = MutableStateFlow<String?>(null)
    val importedProjectId: StateFlow<String?> = _importedProjectId.asStateFlow()

    /** SCR-001 "音声ファイルをアップロード": import, then analyse (Phase 2). */
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
                    ImportResult(id, isWav(file))
                }.onFailure { Log.w(TAG, "import failed", it) }.getOrNull()
            }
            when {
                result == null -> _message.value = "音声ファイルを取り込めませんでした"
                !result.wav -> _message.value =
                    "取り込みましたが、解析できるのは WAV のみです（この形式は未対応）。"
                else -> {
                    _message.value = "取り込みました。楽譜を作成します…"
                    _importedProjectId.value = result.projectId
                }
            }
        }
    }

    /**
     * Reads a take back from `Music/PianoSheet` (0.4.1).  The shared folder only
     * holds WAVs, so this path checks the header and goes straight to analysis;
     * anything else falls back to the generic import.
     */
    fun importSharedWav(uri: Uri) {
        viewModelScope.launch {
            val projectId = withContext(Dispatchers.IO) {
                runCatching {
                    val id = UUID.randomUUID().toString()
                    val file = container.sharedAudioStorage
                        .importRecording(uri.toString(), id, container.audioStorage.directory)
                        .getOrThrow()
                    val name = container.audioStorage.displayName(uri) ?: file.name
                    container.projectRepository.createUploadedProject(
                        id = id,
                        title = name.substringBeforeLast('.', name),
                        file = file,
                        originalFileName = name,
                    )
                    id
                }.onFailure { Log.w(TAG, "shared import failed", it) }.getOrNull()
            }
            if (projectId == null) {
                importAudio(uri)
            } else {
                _message.value = "Music/PianoSheet から読み込みました。楽譜を作成します…"
                _importedProjectId.value = projectId
            }
        }
    }

    /** WAV (RIFF/WAVE) is what the final analysis can read on its own. */
    private fun isWav(file: java.io.File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            if (input.read(header) != 12) return@use false
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }.getOrDefault(false)

    private data class ImportResult(val projectId: String, val wav: Boolean)

    fun clearImportedProject() {
        _importedProjectId.value = null
    }

    fun clearMessage() {
        _message.value = null
    }
    companion object {
        private const val TAG = "HomeViewModel"
    }
}
