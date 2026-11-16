package com.yuushilabs.pianosheet.core.dsp

/**
 * Noise-removal strength presets (spec v1.3 §20.3.2), measured in Phase 0.
 *
 * Only the analysis copy is ever cleaned; the saved WAV stays untouched, so the
 * strength can be changed and the take re-analysed at any time.
 */
enum class DenoiseStrength(
    val wireName: String,
    val oversubtraction: Double,
    val floorDb: Double,
    val gainSmoothing: Double,
) {
    OFF("off", 0.0, 0.0, 0.0),
    WEAK("weak", 0.7, -8.0, 0.60),
    STANDARD("standard", 1.4, -14.0, 0.50),
    STRONG("strong", 3.0, -24.0, 0.45),
    ;

    val enabled: Boolean get() = this != OFF

    companion object {
        fun fromWire(value: String?): DenoiseStrength =
            values().firstOrNull { it.wireName == value } ?: STANDARD
    }
}

/**
 * Settings of the analysis-time denoiser (spec §20.3).
 *
 * Frame geometry: 1024 samples at 22.05 kHz (46 ms, 21.5 Hz per bin) with a
 * 256-sample hop.  Sample numbering is preserved; the live path holds the newest
 * `frameSize + hopSize` samples back because the windows covering them are not
 * complete yet.
 */
data class DenoiseConfig(
    val strength: DenoiseStrength = DenoiseStrength.STANDARD,
    val highpassHz: Double = 25.0,
    val highpassQ: Double = BiquadDesign.BUTTERWORTH_Q,
    val notchHz: Double? = null,
    val notchQ: Double = 30.0,
    val minSubtractHz: Double = 150.0,
    val frameSize: Int = 1024,
    val hopSize: Int = 256,
    val sampleRate: Int = 22050,
) {
    init {
        require(hopSize in 1..frameSize) { "frameSize must be >= hopSize > 0" }
        require(notchHz == null || notchHz in 20.0..120.0) {
            "notchHz is only meaningful for mains hum (20..120 Hz): $notchHz"
        }
    }

    val enabled: Boolean get() = strength.enabled
    val nBins: Int get() = frameSize / 2 + 1

    fun subtractMask(): BooleanArray {
        val binHz = sampleRate.toDouble() / frameSize
        return BooleanArray(nBins) { bin -> bin * binHz >= minSubtractHz }
    }

    fun describeProfile(): String {
        if (!enabled) return "off"
        val base = strength.wireName
        val notch = notchHz?.let { "notch${it.toInt()}" }
        return if (notch == null) base else "$base+$notch"
    }
}
