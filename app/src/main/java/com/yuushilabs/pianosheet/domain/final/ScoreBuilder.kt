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

        // Metrical accent is heard from the bass and the loudness, not from the
        // number of notes in a chord: a chord's four upper voices used to outweigh
        // the bass note that marks the bar, which put the downbeat on beat 2 (a
        // 3/4 Gymnopédie came out as 2/4).  The lowest note of an attack keeps its
        // weight and the others count for [UPPER_VOICE_WEIGHT] of theirs.
        val attackWeight = HashMap<Double, Double>()
        val lowestOfAttack = HashMap<Double, Int>()
        for (item in working) {
            val slot = Math.round(item.startBeat / TimeSignatureEstimator.GRID) * TimeSignatureEstimator.GRID
            val base = (item.note.amplitude.toDouble().coerceIn(0.05, 1.0)) *
                (0.5 + 0.5 * item.durationBeats.coerceAtMost(4.0))
            attackWeight[slot] = (attackWeight[slot] ?: 0.0) + base
            val lowest = lowestOfAttack[slot]
            if (lowest == null || item.note.pitch < lowest) lowestOfAttack[slot] = item.note.pitch
        }
        val meter = TimeSignatureEstimator.estimate(
            working.map { item ->
                val slot = Math.round(item.startBeat / TimeSignatureEstimator.GRID) * TimeSignatureEstimator.GRID
                val base = (item.note.amplitude.toDouble().coerceIn(0.05, 1.0)) *
                    (0.5 + 0.5 * item.durationBeats.coerceAtMost(4.0))
                val isBass = lowestOfAttack[slot] == item.note.pitch
                TimeSignatureEstimator.BeatOnset(
                    beat = item.startBeat,
                    weight = base * (if (isBass) 1.0 else UPPER_VOICE_WEIGHT),
                )
            },
        )
        val timeSignature = meter.timeSignature
        val beatsPerMeasure = timeSignature.beatsPerMeasure

        val lastBeat = working.maxOf { it.startBeat + it.durationBeats }
        // The first downbeat is where the meter estimator found the accents; the
        // notes before it are a pickup measure (anacrusis), not a shifted bar.
        val measures = buildMeasures(
            timeSignature = timeSignature,
            phaseBeats = meter.phaseBeats,
            lastBeat = lastBeat + BEAT_TAIL,
            maxMeasures = options.maxMeasures,
        )

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
            durationSec = maxOf(durationSec, measures.last().endBeat * beatSec),
            detector = detector,
            createdAt = createdAt,
            liveMatched = liveMatched,
            analysisOnly = analysisOnly,
            liveOnly = liveOnly,
        )
    }

    /**
     * Bars placed on the estimated downbeat phase.
     *
     * A performance that starts mid-bar gets a short first measure ([phaseBeats]
     * long, written with `implicit="yes"` in MusicXML) and full measures after it;
     * starting every take on a downbeat was what put the bar lines on the wrong
     * side of the accents on the PianoVAM takes (bar-accent ratio 1.01 → 1.20).
     */
    /** Share of an upper voice's weight in the metrical accent (bass keeps 1.0). */
    private const val UPPER_VOICE_WEIGHT = 0.3

    private fun buildMeasures(
        timeSignature: TimeSignature,
        phaseBeats: Double,
        lastBeat: Double,
        maxMeasures: Int,
    ): List<ScoreMeasure> {
        val per = timeSignature.beatsPerMeasure
        val out = ArrayList<ScoreMeasure>()
        var start = 0.0
        if (phaseBeats > 1e-6) {
            val pickup = phaseBeats.coerceAtMost(per)
            out.add(ScoreMeasure(index = 0, startBeat = 0.0, beats = pickup))
            start = pickup
        }
        while (start < lastBeat - 1e-9 && out.size < maxMeasures) {
            out.add(ScoreMeasure(index = out.size, startBeat = start, beats = per))
            start += per
        }
        if (out.isEmpty()) out.add(ScoreMeasure(0, 0.0, per))
        return out
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
