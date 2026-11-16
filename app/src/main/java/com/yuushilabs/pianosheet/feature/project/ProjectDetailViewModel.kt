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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SCR-003/SCR-004 minimal landing: shows the saved take and the provisional
 * events so the Phase 2 final analysis has a place to arrive.
 */
class ProjectDetailViewModel(
    private val container: AppContainer,
    private val projectId: String,
) : ViewModel() {

    private val _detail = MutableStateFlow<ProjectDetail?>(null)
    val detail: StateFlow<ProjectDetail?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _detail.value = withContext(Dispatchers.IO) {
                runCatching { container.projectRepository.loadProject(projectId) }.getOrNull()
            }
            _loading.value = false
        }
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
