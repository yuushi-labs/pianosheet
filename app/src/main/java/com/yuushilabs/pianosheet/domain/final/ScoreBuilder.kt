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
    /**
     * When the take first becomes audible, on the recording clock
     * ([com.yuushilabs.pianosheet.core.dsp.SilenceHead]).  Notes before it are the
     * model listening to the room, not playing, so they are dropped before the
     * score is laid out; null keeps every note.
     */
    val soundStartSec: Double? = null,
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

        // A take usually opens with the microphone settling and sometimes with a
        // chair, a breath or a tap the model reports as a note.  Whatever comes
        // first becomes beat 0, so one such blip used to print the wait before the
        // playing as leading empty bars (the user: "録音開始してから最初の音がなる
        // までの間も楽譜に出てる").  Notes the model reported while the audio was
        // still silent go first, then an isolated blip before the playing; the
        // tempo is needed to know what "a long silence" is, so it is estimated
        // between the two and again on what is left.
        val audible = dropBeforeSound(cleaned, options.soundStartSec)
        val firstTempo = TempoEstimator.estimate(
            onsets = audible.map { TempoEstimator.Onset(it.onsetSec, weight = weightOf(it)) },
            minBpm = options.minTempoBpm,
            maxBpm = options.maxTempoBpm,
        )
        val played = trimLeadingNoise(audible, beatSec = 60.0 / firstTempo.bpm)
        val tempo = if (played.size == audible.size) {
            firstTempo
        } else {
            TempoEstimator.estimate(
                onsets = played.map { TempoEstimator.Onset(it.onsetSec, weight = weightOf(it)) },
                minBpm = options.minTempoBpm,
                maxBpm = options.maxTempoBpm,
            )
        }
        val beatSec = 60.0 / tempo.bpm
        val originSec = played.minOf { it.onsetSec }

        data class Working(
            val note: FinalNote,
            val playedStartBeat: Double,
            val startBeat: Double,
            val durationBeats: Double,
        )

        val working = played.map { note ->
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

    /**
     * Forgets the notes the model heard while the take was still silent.
     *
     * A model that is handed a second of room tone reports a chord of low notes
     * somewhere in it ("Gymnopédie" fixture: two made-up notes 23 ms in, 0.8 s
     * before the piano).  Those notes would become beat 0 and push the music a bar
     * or two into the score.  The 0.15 s allowance keeps a real first attack whose
     * onset the model placed a window boundary early.
     */
    private fun dropBeforeSound(notes: List<FinalNote>, soundStartSec: Double?): List<FinalNote> {
        if (soundStartSec == null || soundStartSec <= HEAD_SILENCE_MARGIN) return notes
        val kept = notes.filter { it.onsetSec >= soundStartSec - HEAD_SILENCE_MARGIN }
        // Never empty the take because a level reading went wrong on a quiet day.
        return if (kept.size >= 3) kept else notes
    }

    /**
     * Drops whatever the model reported before the playing really began.
     *
     * The takes are grouped into attacks (everything within [ATTACK_CLUSTER_SEC]
     * is one key press) and the attacks into runs of playing, where a run
     * continues while the next attack is no more than [SILENCE_BEATS] beats
     * away.  A run that is cut off from the rest of the take by a longer silence
     * and holds only a handful of notes is room noise — a tap, a chair, the
     * microphone settling — so it is skipped, and the run after it becomes the
     * start of the score.
     *
     * A take that genuinely opens with a lone chord keeps it, because that run
     * carries straight on into the music instead of being cut off, and a take
     * short enough to be only a few notes is never trimmed at all.
     */
    private fun trimLeadingNoise(notes: List<FinalNote>, beatSec: Double): List<FinalNote> {
        if (notes.size <= MIN_TRIMMABLE_NOTES) return notes
        val gapSec = (SILENCE_BEATS * beatSec).coerceIn(MIN_SILENCE_SEC, MAX_SILENCE_SEC)
        val attacks = ArrayList<Int>()
        for (index in notes.indices) {
            val previous = attacks.lastOrNull()
            if (previous == null || notes[index].onsetSec - notes[previous].onsetSec > ATTACK_CLUSTER_SEC) {
                attacks.add(index)
            }
        }
        if (attacks.size < 3) return notes

        /** Notes in the run that starts at attack [from] and ends before attack [to]. */
        fun notesInRun(from: Int, to: Int): Int =
            (if (to < attacks.size) attacks[to] else notes.size) - attacks[from]

        var start = 0
        while (start < attacks.size) {
            var end = start + 1
            while (end < attacks.size &&
                notes[attacks[end]].onsetSec - notes[attacks[end - 1]].onsetSec <= gapSec
            ) {
                end += 1
            }
            val runNotes = notesInRun(start, end)
            val blip = runNotes <= maxOf(2, (notes.size * BLIP_SHARE).toInt()) && end - start <= MAX_BLIP_ATTACKS
            if (!blip || end >= attacks.size) break
            start = end
        }
        val first = attacks[start]
        return if (first == 0) notes else notes.subList(first, notes.size).toList()
    }

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

    /** Onsets closer than this are the same key press (a chord, or a split note). */
    private const val ATTACK_CLUSTER_SEC = 0.09

    /** A gap longer than this many beats ends a run of playing. */
    private const val SILENCE_BEATS = 4.0
    private const val MIN_SILENCE_SEC = 1.2
    private const val MAX_SILENCE_SEC = 4.0

    /** Before the music, a run of at most this many attacks / share of the notes is noise. */
    private const val MAX_BLIP_ATTACKS = 4
    private const val BLIP_SHARE = 0.05

    /** Below this many notes the take is too short to tell noise from an intro. */
    private const val MIN_TRIMMABLE_NOTES = 6

    /** A note may sit this much before the first audible frame and still be real. */
    private const val HEAD_SILENCE_MARGIN = 0.15
}
