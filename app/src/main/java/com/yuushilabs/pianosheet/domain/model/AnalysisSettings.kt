package com.yuushilabs.pianosheet.domain.model

import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength

/**
 * Noise-removal mode for the analysis copy (spec §20.4). `AUTO` means on, with
 * the measured defaults: weak on the live path, the chosen strength for the
 * final analysis.
 */
enum class DenoiseMode(val wireName: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off"),
    ;

    companion object {
        fun fromWire(value: String?): DenoiseMode =
            values().firstOrNull { it.wireName == value } ?: AUTO
    }
}

enum class HumMode(val wireName: String) {
    AUTO("auto"),
    HZ50("50"),
    HZ60("60"),
    NONE("none"),
    ;

    companion object {
        fun fromWire(value: String?): HumMode =
            values().firstOrNull { it.wireName == value } ?: NONE
    }
}

data class AnalysisSettings(
    val denoiseMode: DenoiseMode = DenoiseMode.AUTO,
    val strength: DenoiseStrength = DenoiseStrength.STANDARD,
    val applyToLive: Boolean = true,
    val humMode: HumMode = HumMode.NONE,
)
