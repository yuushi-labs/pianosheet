package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.score.QuantizationSettings
import com.yuushilabs.pianosheet.domain.score.QuantizeMode
import kotlin.math.roundToInt

/**
 * Rhythm quantisation (spec §5.3).
 *
 * The played time is never thrown away: [Quantized.startBeat] is the beat the
 * score prints and [Quantized.playedStartBeat] is what the recording actually
 * did, so the score view can show the notation while the playback highlight can
 * follow the original performance.
 */
object RhythmQuantizer {

    data class Quantized(
        val startBeat: Double,
        val durationBeats: Double,
        val playedStartBeat: Double,
        val playedDurationBeats: Double,
    )

    fun snap(beat: Double, grid: Double): Double = (beat / grid).roundToInt() * grid

    /**
     * Snaps a start time toward the grid by [QuantizationSettings.strength].
     * PERFORMANCE_TIME keeps the played value; READABILITY moves it (never all
     * the way unless the strength is 1.0, so rubato stays visible).
     */
    fun quantizeStart(beat: Double, settings: QuantizationSettings): Double {
        val played = beat.coerceAtLeast(0.0)
        if (settings.mode == QuantizeMode.PERFORMANCE_TIME) return played
        val target = snap(played, settings.grid)
        val blended = played + (target - played) * settings.strength
        return blended.coerceAtLeast(0.0)
    }

    /** Length snapped to the grid, never shorter than one grid step or 60 ms. */
    fun quantizeDuration(durationBeats: Double, settings: QuantizationSettings, minBeats: Double = 0.0): Double {
        val minimum = maxOf(settings.grid, minBeats)
        val played = durationBeats.coerceAtLeast(minimum)
        if (settings.mode == QuantizeMode.PERFORMANCE_TIME) return played
        val target = snap(played, settings.grid).coerceAtLeast(settings.grid)
        val blended = played + (target - played) * settings.strength
        return blended.coerceAtLeast(minimum)
    }

    fun quantize(
        startBeat: Double,
        durationBeats: Double,
        settings: QuantizationSettings,
        minBeats: Double = 0.0,
    ): Quantized {
        val playedStart = startBeat.coerceAtLeast(0.0)
        val playedDuration = durationBeats.coerceAtLeast(0.0)
        return Quantized(
            startBeat = quantizeStart(playedStart, settings),
            durationBeats = quantizeDuration(playedDuration, settings, minBeats),
            playedStartBeat = playedStart,
            playedDurationBeats = playedDuration,
        )
    }
}
