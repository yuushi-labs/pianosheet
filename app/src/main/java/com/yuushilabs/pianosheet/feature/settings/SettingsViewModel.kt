package com.yuushilabs.pianosheet.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.pianosheet.PianoSheetApplication
import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength
import com.yuushilabs.pianosheet.domain.model.AnalysisSettings
import com.yuushilabs.pianosheet.domain.model.DenoiseMode
import com.yuushilabs.pianosheet.domain.model.HumMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PianoSheetApplication).container

    /** Shared with home: one check/download at a time (0.4.5). */
    val updateController = container.updateController

    val settings: StateFlow<AnalysisSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisSettings())

    fun setDenoiseMode(mode: DenoiseMode) {
        viewModelScope.launch { container.settingsRepository.setDenoiseMode(mode) }
    }

    fun setStrength(strength: DenoiseStrength) {
        viewModelScope.launch { container.settingsRepository.setStrength(strength) }
    }

    fun setApplyToLive(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setApplyToLive(enabled) }
    }

    fun setHumMode(mode: HumMode) {
        viewModelScope.launch { container.settingsRepository.setHumMode(mode) }
    }
}
