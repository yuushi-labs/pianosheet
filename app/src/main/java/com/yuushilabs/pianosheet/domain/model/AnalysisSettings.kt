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
    /**
     * `standard` stays the default: on PianoVAM's clean, −34 dBFS, 20 dB SNR and
     * 12 dB SNR versions of four excerpts the mean F1 was 0.817 (standard) vs
     * 0.819 (weak) — a wash — while the synthetic fixture suite prefers standard
     * (macro 0.949 vs 0.925).  `strong` is worse everywhere (0.78–0.85).
     */
    val strength: DenoiseStrength = DenoiseStrength.STANDARD,
    val applyToLive: Boolean = true,
    val humMode: HumMode = HumMode.NONE,
)
