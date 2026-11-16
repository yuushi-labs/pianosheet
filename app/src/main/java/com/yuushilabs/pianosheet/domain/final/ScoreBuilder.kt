package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.score.Accidental
import com.yuushilabs.pianosheet.domain.score.KeySignature
import com.yuushilabs.pianosheet.domain.score.NoteValue
import com.yuushilabs.pianosheet.domain.score.QuantizationSettings
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.ScoreMath
import com.yuushilabs.pianosheet.domain.score.ScoreMeasure
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import com.yuushilabs.pianosheet.domain.score.ScoreRest
import com.yuushilabs.pianosheet.domain.score.TimeSignature
import kotlin.math.abs
import kotlin.math.roundToInt

/** One note as the final analysis knows it, before it becomes notation. */
data class FinalNote(
    val pitch: Int,
    val onsetSec: Double,
    val durationSec: Double,
    val amplitude: Float = 1f,
) {
    val endSec: Double get() = onsetSec + durationSec
}

data class ScoreBuildOptions(
    val quantization: QuantizationSettings = QuantizationSettings(),
    val minTempoBpm: Double = 50.0,
    val maxTempoBpm: Double = 200.0,
    /** Ignore blips shorter than this (the user called out anything under 80 ms). */
    val minNoteSec: Double = 0.08,
    val maxMeasures: Int = 400,
)

/**
 * Turns the final transcriptions into a finished, printable score (spec §5).
 *
 * Order of work: tempo -> beat grid -> rhythm quantisation -> meter -> measures
 * -> key -> hand split -> spelling / accidentals -> rests.  Every step is a
 * separate pure function, and every estimate keeps its confidence so the UI can
 * present it as a proposal rather than a fact (spec §5.6).
 */
object ScoreBuilder {

    fun build(
        projectId: String,
        title: String,
        notes: List<FinalNote>,
        durationSec: Double,
        options: ScoreBuildOptions = ScoreBuildOptions(),
        detector: String = "basic_pitch_onnx",
        createdAt: Long = System.currentTimeMillis(),
        liveMatched: Int = 0,
        analysisOnly: Int = 0,
        liveOnly: Int = 0,
    ): Score {
        val cleaned = cleanNotes(notes, options)
        if (cleaned.isEmpty()) {
            return emptyScore(
                projectId = projectId,
                title = title,
                durationSec = durationSec,
                options = options,
                detector = detector,
                createdAt = createdAt,
            )
        }

        val tempo = TempoEstimator.estimate(
            onsets = cleaned.map { TempoEstimator.Onset(it.onsetSec, weight = weightOf(it)) },
            minBpm = options.minTempoBpm,
            maxBpm = options.maxTempoBpm,
        )
        val beatSec = 60.0 / tempo.bpm
        val originSec = cleaned.minOf { it.onsetSec }

        data class Working(
            val note: FinalNote,
            val playedStartBeat: Double,
            val startBeat: Double,
            val durationBeats: Double,
        )

        val working = cleaned.map { note ->
            val playedStart = (note.onsetSec - originSec) / beatSec
            val quantized = RhythmQuantizer.quantize(
                startBeat = playedStart,
                durationBeats = note.durationSec / beatSec,
                settings = options.quantization,
                minBeats = options.quantization.grid,
            )
            Working(
                note = note,
                playedStartBeat = playedStart,
                startBeat = quantized.startBeat,
                durationBeats = maxOf(quantized.durationBeats, options.quantization.grid),
            )
        }

        val meter = TimeSignatureEstimator.estimate(
            working.map {
                TimeSignatureEstimator.BeatOnset(
                    beat = it.startBeat,
                    weight = (it.note.amplitude.toDouble().coerceIn(0.05, 1.0)) *
                        (0.5 + 0.5 * it.durationBeats.coerceAtMost(4.0)),
                )
            },
        )
        val timeSignature = meter.timeSignature
        val beatsPerMeasure = timeSignature.beatsPerMeasure

        val lastBeat = working.maxOf { it.startBeat + it.durationBeats }
        val measureCount = ScoreMath
            .measureCountFor(lastBeat + BEAT_TAIL, timeSignature)
            .coerceAtMost(options.maxMeasures)
        val measures = ScoreMath.measureStartBeats(timeSignature, measureCount)

        val key = KeyEstimator.estimate(
            working.map { KeyEstimator.PitchWeight(it.note.pitch, it.durationBeats) },
        )
        val keySignature = key.keySignature

        val assignable = working.mapIndexed { index, item ->
            StaffAssigner.Note(
                key = "n$index",
                pitch = item.note.pitch,
                startBeat = item.startBeat,
                durationBeats = item.durationBeats,
                velocity = item.note.amplitude,
            )
        }
        val split = StaffAssigner.chooseSplit(assignable.map { it.pitch })
        val assignment = StaffAssigner.assign(assignable, split)

        val ordered = working.mapIndexed { index, item -> index to item }
            .sortedWith(compareBy({ it.second.startBeat }, { it.second.note.pitch }))

        // Same pitch on the same staff cannot overlap: trim the earlier note.
        val durationAfterTrim = HashMap<Int, Double>()
        val lastIndexByPitchStaff = HashMap<Pair<Int, Staff>, Int>()
        for ((index, item) in ordered) {
            val staffOfNote = assignment.staffByKey["n$index"] ?: Staff.TREBLE
            val pitchStaff = item.note.pitch to staffOfNote
            val previousIndex = lastIndexByPitchStaff[pitchStaff]
            if (previousIndex != null) {
                val previous = working[previousIndex]
                val previousDuration = durationAfterTrim[previousIndex] ?: previous.durationBeats
                if (previous.startBeat + previousDuration > item.startBeat + 1e-6) {
                    durationAfterTrim[previousIndex] =
                        (item.startBeat - previous.startBeat).coerceAtLeast(options.quantization.grid / 2.0)
                }
            }
            lastIndexByPitchStaff[pitchStaff] = index
        }

        val notesOut = ArrayList<ScoreNote>(working.size)
        val accidentalsInEffect = HashMap<Pair<Int, Int>, Accidental?>()
        var currentMeasure = -1
        for ((index, item) in ordered) {
            val staff = assignment.staffByKey["n$index"] ?: Staff.TREBLE
            val measure = measures.lastOrNull { item.startBeat >= it.startBeat - 1e-9 } ?: measures.first()
            if (measure.index != currentMeasure) {
                accidentalsInEffect.clear()
                currentMeasure = measure.index
            }
            val duration = durationAfterTrim[index] ?: item.durationBeats
            val spelling = PitchSpeller.spell(item.note.pitch, keySignature)
            val accidental = PitchSpeller.accidentalToPrint(spelling, accidentalsInEffect)
            accidentalsInEffect[spelling.letter to spelling.octave] = spelling.accidental

            val value = ScoreMath.noteValueFor(duration)
            notesOut.add(
                ScoreNote(
                    id = "s%04d".format(index),
                    midiPitch = item.note.pitch,
                    startBeat = item.startBeat,
                    durationBeats = duration,
                    staff = staff,
                    noteValue = value.first,
                    dotted = value.second,
                    accidental = accidental,
                    startSec = item.note.onsetSec,
                    velocity = item.note.amplitude.coerceIn(0.05f, 1f),
                    measureIndex = measure.index,
                    letter = spelling.letter,
                    octave = spelling.octave,
                ),
            )
        }

        val rests = buildRests(notesOut, measures, timeSignature)

        return Score(
            projectId = projectId,
            title = title,
            tempoBpm = tempo.bpm,
            tempoConfidence = tempo.confidence,
            timeSignature = timeSignature,
            timeSignatureConfidence = meter.confidence,
            keySignature = keySignature,
            keyConfidence = key.confidence,
            quantization = options.quantization,
            measures = measures,
            notes = notesOut,
            rests = rests,
            scoreStartSec = originSec,
            durationSec = maxOf(durationSec, measureCount * beatsPerMeasure * beatSec),
            detector = detector,
            createdAt = createdAt,
            liveMatched = liveMatched,
            analysisOnly = analysisOnly,
            liveOnly = liveOnly,
        )
    }

    private fun weightOf(note: FinalNote): Float =
        (note.amplitude.coerceIn(0.05f, 1.0f)) * (0.5f + 0.5f * note.durationSec.toFloat().coerceAtMost(2f))

    /** Drops blips and exact re-strikes the model reported twice. */
    private fun cleanNotes(notes: List<FinalNote>, options: ScoreBuildOptions): List<FinalNote> {
        val usable = notes
            .filter { it.pitch in 21..108 && it.onsetSec >= 0.0 && it.durationSec >= options.minNoteSec }
            .sortedWith(compareBy({ it.onsetSec }, { it.pitch }))
        val result = ArrayList<FinalNote>(usable.size)
        for (note in usable) {
            val duplicate = result.lastOrNull {
                it.pitch == note.pitch && abs(it.onsetSec - note.onsetSec) < DUPLICATE_ONSET_SEC
            }
            if (duplicate != null) {
                // Keep the longer of the two, so a sustained note is not lost.
                if (note.endSec > duplicate.endSec) {
                    result[result.indexOf(duplicate)] = duplicate.copy(
                        durationSec = note.endSec - duplicate.onsetSec,
                        amplitude = maxOf(duplicate.amplitude, note.amplitude),
                    )
                }
                continue
            }
            result.add(note)
        }
        return result
    }

    private fun buildRests(
        notes: List<ScoreNote>,
        measures: List<ScoreMeasure>,
        timeSignature: TimeSignature,
    ): List<ScoreRest> {
        val rests = ArrayList<ScoreRest>()
        for (staff in listOf(Staff.TREBLE, Staff.BASS)) {
            val staffNotes = notes.filter { it.staff == staff }
            if (staffNotes.isEmpty()) continue
            for (measure in measures) {
                val inMeasure = staffNotes
                    .filter { it.startBeat < measure.endBeat - 1e-9 && it.startBeat + it.durationBeats > measure.startBeat + 1e-9 }
                    .sortedBy { it.startBeat }
                var cursor = measure.startBeat
                for (note in inMeasure) {
                    if (note.startBeat > cursor + 1e-6) addRest(rests, cursor, note.startBeat, staff, measure.index)
                    cursor = maxOf(cursor, note.startBeat + note.durationBeats)
                }
                if (cursor < measure.endBeat - 1e-6) addRest(rests, cursor, measure.endBeat, staff, measure.index)
            }
        }
        return rests
    }

    private fun addRest(
        target: MutableList<ScoreRest>,
        from: Double,
        to: Double,
        staff: Staff,
        measureIndex: Int,
    ) {
        var cursor = from
        for (value in ScoreMath.decomposeRest(to - from)) {
            val beats = ScoreMath.beatsOf(value.first, value.second)
            if (beats <= 1e-9) continue
            target.add(
                ScoreRest(
                    startBeat = cursor,
                    durationBeats = beats,
                    staff = staff,
                    noteValue = value.first,
                    dotted = value.second,
                    measureIndex = measureIndex,
                ),
            )
            cursor += beats
        }
    }

    private fun emptyScore(
        projectId: String,
        title: String,
        durationSec: Double,
        options: ScoreBuildOptions,
        detector: String,
        createdAt: Long,
    ): Score = Score(
        projectId = projectId,
        title = title,
        tempoBpm = TempoEstimator.DEFAULT_BPM,
        tempoConfidence = 0.0,
        timeSignature = TimeSignature.COMMON,
        timeSignatureConfidence = 0.0,
        keySignature = KeySignature.C_MAJOR,
        keyConfidence = 0.0,
        quantization = options.quantization,
        measures = ScoreMath.measureStartBeats(TimeSignature.COMMON, 1),
        notes = emptyList(),
        rests = emptyList(),
        scoreStartSec = 0.0,
        durationSec = maxOf(durationSec, 0.0),
        detector = detector,
        createdAt = createdAt,
    )

    private const val BEAT_TAIL = 0.5
    private const val DUPLICATE_ONSET_SEC = 0.035
}
