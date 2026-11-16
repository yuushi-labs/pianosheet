package com.yuushilabs.pianosheet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuushilabs.pianosheet.core.dsp.DenoiseConfig
import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength
import com.yuushilabs.pianosheet.domain.model.AnalysisSettings
import com.yuushilabs.pianosheet.domain.model.DenoiseMode
import com.yuushilabs.pianosheet.domain.model.HumMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.analysisDataStore: DataStore<Preferences> by preferencesDataStore(name = "analysis_settings")

/** DataStore-backed settings (spec §20.4). Recordings themselves never change. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val denoiseMode = stringPreferencesKey("denoise_mode")
        val strength = stringPreferencesKey("denoise_strength")
        val applyToLive = booleanPreferencesKey("denoise_apply_to_live")
        val humMode = stringPreferencesKey("hum_mode")
    }

    val settings: Flow<AnalysisSettings> = context.analysisDataStore.data.map { preferences ->
        AnalysisSettings(
            denoiseMode = DenoiseMode.fromWire(preferences[Keys.denoiseMode]),
            strength = DenoiseStrength.fromWire(preferences[Keys.strength]),
            applyToLive = preferences[Keys.applyToLive] ?: true,
            humMode = HumMode.fromWire(preferences[Keys.humMode]),
        )
    }

    suspend fun setDenoiseMode(mode: DenoiseMode) {
        context.analysisDataStore.edit { it[Keys.denoiseMode] = mode.wireName }
    }

    suspend fun setStrength(strength: DenoiseStrength) {
        context.analysisDataStore.edit { it[Keys.strength] = strength.wireName }
    }

    suspend fun setApplyToLive(enabled: Boolean) {
        context.analysisDataStore.edit { it[Keys.applyToLive] = enabled }
    }

    suspend fun setHumMode(mode: HumMode) {
        context.analysisDataStore.edit { it[Keys.humMode] = mode.wireName }
    }
}

/** The analysis copy's settings, or null when no cleaning should be applied. */
fun AnalysisSettings.denoiseConfigForLive(): DenoiseConfig? {
    if (denoiseMode == DenoiseMode.OFF || !applyToLive) return null
    return DenoiseConfig(
        strength = DenoiseStrength.WEAK,
        notchHz = humHz(),
    )
}

fun AnalysisSettings.denoiseConfigForFinal(): DenoiseConfig? {
    if (denoiseMode == DenoiseMode.OFF) return null
    return DenoiseConfig(
        strength = strength,
        notchHz = humHz(),
    )
}

private fun AnalysisSettings.humHz(): Double? = when (humMode) {
    HumMode.HZ50 -> 50.0
    HumMode.HZ60 -> 60.0
    HumMode.AUTO, HumMode.NONE -> null
}
