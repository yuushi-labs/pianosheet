package com.yuushilabs.pianosheet.core.dsp

/**
 * Noise-removal strength presets (spec v1.3 §20.3.2), measured in Phase 0 and
 * re-tuned in 0.4.4 on the user's own takes.
 *
 * Only the analysis copy is ever cleaned; the saved WAV stays untouched, so the
 * strength can be changed and the take re-analysed at any time.
 *
 * 0.4.4 moved the whole table one step milder: the user compared the three
 * settings on his phone and reported that 0.4.3's *weak* caught the playing most
 * faithfully ("弱の方が1番音を正確に捉えてる"), so that setting is the new
 * [STANDARD] and what used to be standard is the new [STRONG].  The high band is
 * taken back out of that gentleness by [DenoiseConfig.highBandBoost]: the piano
 * body keeps the gentle setting, while the 1.5–4 kHz hiss that the model turns
 * into extra high notes is still subtracted harder.
 */
enum class DenoiseStrength(
    val wireName: String,
    val oversubtraction: Double,
    val floorDb: Double,
    val gainSmoothing: Double,
) {
    OFF("off", 0.0, 0.0, 0.0),

    /** Only the clearly stationary part of the floor (0.4.4: even gentler). */
    WEAK("weak", 0.45, -5.0, 0.65),

    /** Default.  This is what 0.4.3 called "弱". */
    STANDARD("standard", 0.70, -8.0, 0.60),

    /** 0.4.3's standard: noticeably stronger, still keeps decays. */
    STRONG("strong", 1.40, -14.0, 0.50),
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
 *
 * The high band ([highBandFromHz] .. [highBandFullHz], fully from the second)
 * gets [highBandBoost] times the preset's subtraction factor and a floor
 * [highBandFloorDb] dB deeper.  Measured on the user's four takes: the extra high
 * notes the model reported sat at 1.5–3 kHz and were mostly one spectral line of
 * a lower note's 4th/8th partial sitting on the take's own hiss; taking 3–4 dB
 * more of that hiss away removed them (7 of 12 notes at C6 and above in the loud
 * take, with the same notes under C5 untouched) without touching the body of the
 * piano, and PianoVAM's six excerpts kept their F1 (pv29 0.987 → 0.989,
 * pv74 0.996 → 0.995).
 */
data class DenoiseConfig(
    val strength: DenoiseStrength = DenoiseStrength.STANDARD,
    val highpassHz: Double = 25.0,
    val highpassQ: Double = BiquadDesign.BUTTERWORTH_Q,
    val notchHz: Double? = null,
    val notchQ: Double = 30.0,
    val minSubtractHz: Double = 150.0,
    /**
     * Narrow notches for stationary tones the device or the room leaves in the
     * take (a high whistle; see [ToneNoiseDetector]).  Detected per recording, so
     * this is a switch, not a frequency.
     */
    val toneNotchEnabled: Boolean = true,
    /**
     * Depth of the narrow suppression: enough to silence a whistle, not a note.
     * 0.4.4 takes it from -18 to -26 dB: a 1.25 kHz resonance of the room or the
     * speaker (the 40 s take carried one) left enough of a line behind at -18 dB
     * for the model to keep reporting it as D#6.
     */
    val toneNotchGainDb: Double = -26.0,
    /** Bins either side of the tone that are suppressed (±3 ≈ ±65 Hz). */
    val toneNotchHalfWidthBins: Int = 3,
    val toneNotchMaxCount: Int = ToneNoiseDetector.MAX_NOTCHES,
    /** Frequencies already measured by the caller; null means "detect inside". */
    val toneNotchesHz: List<Double>? = null,
    /** The high band starts here and reaches full weight at [highBandFullHz]. */
    val highBandFromHz: Double = 1200.0,
    val highBandFullHz: Double = 1800.0,
    /** 1.0 leaves the preset alone; 2.0 doubles its subtraction factor up there. */
    val highBandBoost: Double = 2.0,
    /** Extra depth of the gain floor in the high band, in dB. */
    val highBandFloorDb: Double = 4.0,
    val frameSize: Int = 1024,
    val hopSize: Int = 256,
    val sampleRate: Int = 22050,
) {
    init {
        require(hopSize in 1..frameSize) { "frameSize must be >= hopSize > 0" }
        require(notchHz == null || notchHz in 20.0..120.0) {
            "notchHz is only meaningful for mains hum (20..120 Hz): $notchHz"
        }
        require(highBandFullHz > highBandFromHz) {
            "highBandFullHz must be above highBandFromHz: $highBandFromHz..$highBandFullHz"
        }
        require(highBandBoost >= 1.0) { "highBandBoost must not soften the high band" }
        require(highBandFloorDb >= 0.0) { "highBandFloorDb must not raise the floor" }
    }

    val enabled: Boolean get() = strength.enabled
    val nBins: Int get() = frameSize / 2 + 1
    val binHz: Double get() = sampleRate.toDouble() / frameSize

    fun subtractMask(): BooleanArray {
        val binHz = sampleRate.toDouble() / frameSize
        return BooleanArray(nBins) { bin -> bin * binHz >= minSubtractHz }
    }

    /** How much of the high-band weighting applies at [bin]: 0 below the band, 1 inside it. */
    fun highBandRamp(bin: Int): Double {
        if (highBandBoost <= 1.0 && highBandFloorDb <= 0.0) return 0.0
        val hz = bin * binHz
        return ((hz - highBandFromHz) / (highBandFullHz - highBandFromHz)).coerceIn(0.0, 1.0)
    }

    /** Per-bin oversubtraction factor: the preset's, lifted in the high band. */
    fun oversubtractionByBin(): DoubleArray =
        DoubleArray(nBins) { bin ->
            strength.oversubtraction * (1.0 + (highBandBoost - 1.0) * highBandRamp(bin))
        }

    /** Per-bin gain floor (linear), deeper in the high band. */
    fun floorByBin(): DoubleArray =
        DoubleArray(nBins) { bin ->
            Math.pow(10.0, (strength.floorDb - highBandFloorDb * highBandRamp(bin)) / 20.0)
        }

    fun describeProfile(toneNotchesHz: List<Double> = this.toneNotchesHz ?: emptyList()): String {
        if (!enabled) return "off"
        val parts = ArrayList<String>(4)
        parts.add(strength.wireName)
        notchHz?.let { parts.add("notch${it.toInt()}") }
        if (toneNotchEnabled && toneNotchesHz.isNotEmpty()) {
            parts.add("tone" + toneNotchesHz.joinToString(",") { it.toInt().toString() })
        }
        if (highBandBoost > 1.0) parts.add("highx" + highBandBoost.toString())
        return parts.joinToString("+")
    }
}
