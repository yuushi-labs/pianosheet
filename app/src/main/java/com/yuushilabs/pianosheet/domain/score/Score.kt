package com.yuushilabs.pianosheet.domain.score

import com.yuushilabs.pianosheet.domain.model.Staff
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * A finished score (spec §3.6, §5).  Everything here is pure data so the final
 * analysis, the notation canvas, the piano-roll-style highlight and the tests
 * all work from the same model.
 *
 * Time is expressed twice, on purpose:
 *  - `startBeat` / `durationBeats` are *score* time (the quantised beat grid),
 *  - `startSec` is *performance* time on the recording clock (used to line the
 *    score up with the original WAV, spec §7.3).
 */
enum class NoteValue(val beats: Double) {
    WHOLE(4.0),
    HALF(2.0),
    QUARTER(1.0),
    EIGHTH(0.5),
    SIXTEENTH(0.25),
}

enum class Accidental(val glyph: String) {
    SHARP("\u266F"),
    FLAT("\u266D"),
    NATURAL("\u266E"),
}

enum class Mode { MAJOR, MINOR }

/** Whether the rhythm was snapped to the beat grid or kept as played (spec §5.3). */
enum class QuantizeMode(val wireName: String) {
    PERFORMANCE_TIME("performance"),
    READABILITY("readability"),
    ;

    companion object {
        fun fromWire(value: String?): QuantizeMode =
            values().firstOrNull { it.wireName == value } ?: READABILITY
    }
}

data class QuantizationSettings(
    val mode: QuantizeMode = QuantizeMode.READABILITY,
    /** Grid in quarter-note beats: 0.25 = sixteenth note, 0.5 = eighth. */
    val grid: Double = 0.25,
    /** 0 = keep the played time, 1 = snap fully.  Only used by READABILITY. */
    val strength: Double = 0.85,
) {
    init {
        require(grid > 0.0) { "grid must be positive: $grid" }
        require(strength in 0.0..1.0) { "strength must be 0..1: $strength" }
    }

    val label: String
        get() = when (mode) {
            QuantizeMode.READABILITY -> "譜面優先（${gridLabel()}まで量子化 ${(strength * 100).toInt()}%）"
            QuantizeMode.PERFORMANCE_TIME -> "演奏時刻優先（量子化なし）"
        }

    private fun gridLabel(): String = when {
        abs(grid - 0.25) < 1e-9 -> "16分"
        abs(grid - 0.5) < 1e-9 -> "8分"
        abs(grid - 1.0) < 1e-9 -> "4分"
        else -> "%.3f拍".format(Locale.JAPAN, grid)
    }
}

/** Meter as written, e.g. 4/4 or 6/8. */
data class TimeSignature(val numerator: Int, val denominator: Int) {
    init {
        require(numerator > 0) { "numerator must be positive" }
        require(denominator in setOf(2, 4, 8, 16)) { "denominator must be 2/4/8/16: $denominator" }
    }

    /** Length of a measure counted in quarter-note beats (4/4 = 4.0, 6/8 = 3.0). */
    val beatsPerMeasure: Double get() = numerator * 4.0 / denominator

    /** 6/8 and 9/8 group their eighths in threes. */
    val isCompound: Boolean get() = denominator == 8 && numerator % 3 == 0 && numerator > 3

    val label: String get() = "$numerator/$denominator"

    companion object {
        val COMMON = TimeSignature(4, 4)
        val WALTZ = TimeSignature(3, 4)
        val MARCH = TimeSignature(2, 4)
        val COMPOUND = TimeSignature(6, 8)
    }
}

/** Key signature by the number of sharps (positive) or flats (negative). */
data class KeySignature(val fifths: Int, val mode: Mode = Mode.MAJOR) {
    init {
        require(fifths in -7..7) { "fifths must be -7..7: $fifths" }
    }

    val preferFlats: Boolean get() = fifths < 0

    /** Pitch classes (0 = C) that the signature already sharpens / flattens. */
    val alteredPitchClasses: Set<Int>
        get() {
            val result = LinkedHashSet<Int>()
            val order = if (fifths >= 0) SHARP_ORDER else FLAT_ORDER
            for (i in 0 until abs(fifths)) result.add(order[i] % 12)
            return result
        }

    /** The tonic pitch class this signature belongs to. */
    val tonicPitchClass: Int
        get() {
            val majorTonic = MAJOR_TONIC_BY_FIFTHS[fifths + 7]
            return if (mode == Mode.MAJOR) majorTonic else (majorTonic + 9) % 12
        }

    fun nameJa(): String {
        val scale = if (mode == Mode.MAJOR) MAJOR_NAMES_JA else MINOR_NAMES_JA
        return scale[fifths + 7]
    }

    fun describeJa(): String = "${nameJa()}（${if (mode == Mode.MAJOR) "長調" else "短調"}・${signatureLabel()}）"

    fun signatureLabel(): String = when {
        fifths == 0 -> "調号なし"
        fifths > 0 -> "♯×$fifths"
        else -> "♭×${-fifths}"
    }

    companion object {
        val C_MAJOR = KeySignature(0, Mode.MAJOR)
        val A_MINOR = KeySignature(0, Mode.MINOR)

        /** Sharps appear F C G D A E B. */
        val SHARP_ORDER = intArrayOf(5, 0, 7, 2, 9, 4, 11) // F C G D A E B
        /** Flats appear B E A D G C F. */
        val FLAT_ORDER = intArrayOf(11, 4, 9, 2, 7, 0, 5)

        private val MAJOR_TONIC_BY_FIFTHS = intArrayOf(11, 6, 1, 8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1)

        private val MAJOR_NAMES_JA = arrayOf(
            "変ハ長調", "変ト長調", "変ニ長調", "変イ長調", "変ホ長調", "変ロ長調", "ヘ長調",
            "ハ長調", "ト長調", "ニ長調", "イ長調", "ホ長調", "ロ長調", "嬰ヘ長調", "嬰ハ長調",
        )

        private val MINOR_NAMES_JA = arrayOf(
            "変イ短調", "変ホ短調", "変ロ短調", "ヘ短調", "ハ短調", "ト短調", "ニ短調",
            "イ短調", "ホ短調", "ロ短調", "嬰ヘ短調", "嬰ハ短調", "嬰ト短調", "嬰ニ短調", "嬰イ短調",
        )
    }
}

/** A single notehead of the finished score. */
data class ScoreNote(
    val id: String,
    val midiPitch: Int,
    val startBeat: Double,
    val durationBeats: Double,
    val staff: Staff,
    val noteValue: NoteValue,
    val dotted: Boolean,
    val accidental: Accidental?,
    /** Performance clock position of the attack (spec §7.3 sync). */
    val startSec: Double,
    val velocity: Float,
    val measureIndex: Int,
    /** Diatonic letter index (C=0 … B=6) with octave, for staff placement. */
    val letter: Int,
    val octave: Int,
)

data class ScoreRest(
    val startBeat: Double,
    val durationBeats: Double,
    val staff: Staff,
    val noteValue: NoteValue,
    val dotted: Boolean,
    val measureIndex: Int,
)

data class ScoreMeasure(
    val index: Int,
    val startBeat: Double,
    val beats: Double,
) {
    val endBeat: Double get() = startBeat + beats
}

data class Score(
    val projectId: String,
    val title: String,
    val tempoBpm: Double,
    val tempoConfidence: Double,
    val timeSignature: TimeSignature,
    val timeSignatureConfidence: Double,
    val keySignature: KeySignature,
    val keyConfidence: Double,
    val quantization: QuantizationSettings,
    val measures: List<ScoreMeasure>,
    val notes: List<ScoreNote>,
    val rests: List<ScoreRest>,
    /** Performance time where beat 0 sits on the recording clock. */
    val scoreStartSec: Double,
    val durationSec: Double,
    val detector: String,
    val createdAt: Long,
    /** How many live events the final pass confirmed / added / dropped (spec §5.7). */
    val liveMatched: Int = 0,
    val analysisOnly: Int = 0,
    val liveOnly: Int = 0,
) {
    val noteCount: Int get() = notes.size
    val beatDurationSec: Double get() = 60.0 / tempoBpm
    val isEmpty: Boolean get() = notes.isEmpty()

    /** Score time -> performance time (constant tempo, anchored at beat 0). */
    fun beatToSec(beat: Double): Double = scoreStartSec + beat * beatDurationSec

    /** Performance time -> score time. */
    fun secToBeat(sec: Double): Double = (sec - scoreStartSec) / beatDurationSec

    fun measureAtBeat(beat: Double): ScoreMeasure? =
        measures.firstOrNull { beat >= it.startBeat - 1e-9 && beat < it.endBeat - 1e-9 }
            ?: measures.lastOrNull()

    fun measureAtSec(sec: Double): ScoreMeasure? = measureAtBeat(secToBeat(sec))

    /** Notes whose performance interval covers [sec] (for playback highlighting). */
    fun notesAtSec(sec: Double): List<ScoreNote> {
        val beat = secToBeat(sec)
        return notes.filter { beat >= it.startBeat - 0.02 && beat < it.startBeat + it.durationBeats + 0.02 }
    }

    fun notesAtBeat(beat: Double): List<ScoreNote> =
        notes.filter { beat >= it.startBeat - 1e-6 && beat < it.startBeat + it.durationBeats + 1e-6 }

    /** Last beat that carries content, for the layout engine. */
    val lastBeat: Double
        get() = maxOf(
            measures.lastOrNull()?.startBeat ?: 0.0,
            notes.maxOfOrNull { it.startBeat + it.durationBeats } ?: 0.0,
        )

    val measureCount: Int get() = measures.size
}

/** Helpers shared by the notation layout and the canvas. */
object ScoreMath {
    val ALL_VALUES: List<NoteValue> = NoteValue.values().toList()

    /** Note values with their dotted variants, longest first. */
    val VALUES_WITH_DOTS: List<Pair<NoteValue, Boolean>> =
        listOf(
            NoteValue.WHOLE to false,
            NoteValue.WHOLE to true,
            NoteValue.HALF to false,
            NoteValue.HALF to true,
            NoteValue.QUARTER to false,
            NoteValue.QUARTER to true,
            NoteValue.EIGHTH to false,
            NoteValue.EIGHTH to true,
            NoteValue.SIXTEENTH to false,
        )

    fun beatsOf(value: NoteValue, dotted: Boolean): Double = value.beats * if (dotted) 1.5 else 1.0

    /** Nearest writable value for a duration; never shorter than a sixteenth. */
    fun noteValueFor(durationBeats: Double): Pair<NoteValue, Boolean> {
        val clamped = durationBeats.coerceAtLeast(NoteValue.SIXTEENTH.beats)
        var best = VALUES_WITH_DOTS.last()
        var bestError = Double.MAX_VALUE
        for (candidate in VALUES_WITH_DOTS) {
            val error = abs(beatsOf(candidate.first, candidate.second) - clamped)
            // Prefer the simpler value on a tie (no dot, longer value first).
            if (error < bestError - 1e-9) {
                bestError = error
                best = candidate
            }
        }
        return best
    }

    /**
     * Greedy rest decomposition.  Rests never span more than a measure's worth of
     * values, and every value is a standard note length (no 32nd-sized crumbs).
     */
    fun decomposeRest(gapBeats: Double): List<Pair<NoteValue, Boolean>> {
        val candidates = listOf(
            NoteValue.WHOLE to false,
            NoteValue.HALF to true,
            NoteValue.HALF to false,
            NoteValue.QUARTER to true,
            NoteValue.QUARTER to false,
            NoteValue.EIGHTH to true,
            NoteValue.EIGHTH to false,
            NoteValue.SIXTEENTH to false,
        )
        val result = ArrayList<Pair<NoteValue, Boolean>>()
        var remaining = gapBeats
        var guard = 0
        while (remaining >= NoteValue.SIXTEENTH.beats - 1e-9 && guard < 64) {
            guard += 1
            val candidate = candidates.firstOrNull { beatsOf(it.first, it.second) <= remaining + 1e-9 } ?: break
            result.add(candidate)
            remaining -= beatsOf(candidate.first, candidate.second)
        }
        return result
    }

    fun totalBeats(measures: List<ScoreMeasure>): Double =
        measures.sumOf { it.beats }

    fun measureStartBeats(timeSignature: TimeSignature, count: Int): List<ScoreMeasure> {
        val per = timeSignature.beatsPerMeasure
        return List(count) { index ->
            ScoreMeasure(index = index, startBeat = index * per, beats = per)
        }
    }

    /** Measure count needed for [beats], at least one. */
    fun measureCountFor(beats: Double, timeSignature: TimeSignature): Int {
        val per = timeSignature.beatsPerMeasure
        return ceil(beats / per - 1e-9).toInt().coerceAtLeast(1)
    }
}
