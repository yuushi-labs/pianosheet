package com.yuushilabs.pianosheet.domain.score

import com.yuushilabs.pianosheet.domain.model.Staff

/**
 * Text encoding of a finished [Score] for the database.
 *
 * A compact, versioned line format is used instead of a JSON library so the
 * score round-trip is testable on the JVM without Android or extra
 * dependencies, and so a future schema change can be detected by the header
 * line rather than by a crash.
 */
object ScoreCodec {

    private const val HEADER = "PianoSheetScore/1"

    fun encode(score: Score): String {
        val out = StringBuilder(4096)
        out.append(HEADER).append('\n')
        line(out, "project", score.projectId)
        line(out, "title", escape(score.title))
        line(out, "tempo", score.tempoBpm.toString(), score.tempoConfidence.toString())
        line(
            out,
            "meter",
            score.timeSignature.numerator.toString(),
            score.timeSignature.denominator.toString(),
            score.timeSignatureConfidence.toString(),
        )
        line(out, "key", score.keySignature.fifths.toString(), score.keySignature.mode.name, score.keyConfidence.toString())
        line(
            out,
            "quant",
            score.quantization.mode.wireName,
            score.quantization.grid.toString(),
            score.quantization.strength.toString(),
        )
        line(out, "startSec", score.scoreStartSec.toString())
        line(out, "duration", score.durationSec.toString())
        line(out, "detector", escape(score.detector))
        line(out, "createdAt", score.createdAt.toString())
        line(out, "correlation", score.liveMatched.toString(), score.analysisOnly.toString(), score.liveOnly.toString())
        for (measure in score.measures) {
            line(out, "measure", measure.index.toString(), measure.startBeat.toString(), measure.beats.toString())
        }
        for (note in score.notes) {
            line(
                out,
                "note",
                escape(note.id),
                note.midiPitch.toString(),
                note.startBeat.toString(),
                note.durationBeats.toString(),
                note.staff.wireName,
                note.noteValue.name,
                if (note.dotted) "1" else "0",
                note.accidental?.name ?: "-",
                note.startSec.toString(),
                note.velocity.toString(),
                note.measureIndex.toString(),
                note.letter.toString(),
                note.octave.toString(),
            )
        }
        for (rest in score.rests) {
            line(
                out,
                "rest",
                rest.startBeat.toString(),
                rest.durationBeats.toString(),
                rest.staff.wireName,
                rest.noteValue.name,
                if (rest.dotted) "1" else "0",
                rest.measureIndex.toString(),
            )
        }
        return out.toString()
    }

    fun decode(text: String): Score? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || !lines.first().startsWith("PianoSheetScore/")) return null

        var projectId = ""
        var title = ""
        var tempoBpm = 120.0
        var tempoConfidence = 0.0
        var timeSignature = TimeSignature.COMMON
        var timeSigConfidence = 0.0
        var keySignature = KeySignature.C_MAJOR
        var keyConfidence = 0.0
        var quantization = QuantizationSettings()
        var startSec = 0.0
        var durationSec = 0.0
        var detector = "basic_pitch_onnx"
        var createdAt = 0L
        var liveMatched = 0
        var analysisOnly = 0
        var liveOnly = 0
        val measures = ArrayList<ScoreMeasure>()
        val notes = ArrayList<ScoreNote>()
        val rests = ArrayList<ScoreRest>()

        for (raw in lines.drop(1)) {
            val parts = raw.split('|')
            when (parts.firstOrNull()) {
                "project" -> projectId = parts.getOrNull(1).orEmpty()
                "title" -> title = unescape(parts.getOrNull(1).orEmpty())
                "tempo" -> {
                    tempoBpm = parts.getOrNull(1)?.toDoubleOrNull() ?: tempoBpm
                    tempoConfidence = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                }
                "meter" -> {
                    val numerator = parts.getOrNull(1)?.toIntOrNull() ?: 4
                    val denominator = parts.getOrNull(2)?.toIntOrNull() ?: 4
                    timeSignature = runCatching { TimeSignature(numerator, denominator) }
                        .getOrDefault(TimeSignature.COMMON)
                    timeSigConfidence = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                }
                "key" -> {
                    val fifths = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val mode = runCatching { Mode.valueOf(parts.getOrNull(2).orEmpty()) }.getOrDefault(Mode.MAJOR)
                    keySignature = KeySignature(fifths.coerceIn(-7, 7), mode)
                    keyConfidence = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                }
                "quant" -> {
                    val mode = QuantizeMode.fromWire(parts.getOrNull(1))
                    val grid = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.25
                    val strength = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.85
                    quantization = runCatching { QuantizationSettings(mode, grid, strength) }
                        .getOrDefault(QuantizationSettings())
                }
                "startSec" -> startSec = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                "duration" -> durationSec = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                "detector" -> detector = unescape(parts.getOrNull(1).orEmpty())
                "createdAt" -> createdAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                "correlation" -> {
                    liveMatched = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    analysisOnly = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    liveOnly = parts.getOrNull(3)?.toIntOrNull() ?: 0
                }
                "measure" -> {
                    val index = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val start = parts.getOrNull(2)?.toDoubleOrNull() ?: continue
                    val beats = parts.getOrNull(3)?.toDoubleOrNull() ?: continue
                    measures.add(ScoreMeasure(index, start, beats))
                }
                "note" -> {
                    val id = unescape(parts.getOrNull(1).orEmpty())
                    val midi = parts.getOrNull(2)?.toIntOrNull() ?: continue
                    val start = parts.getOrNull(3)?.toDoubleOrNull() ?: continue
                    val duration = parts.getOrNull(4)?.toDoubleOrNull() ?: continue
                    val staff = Staff.fromWire(parts.getOrNull(5)) ?: Staff.TREBLE
                    val value = runCatching { NoteValue.valueOf(parts.getOrNull(6).orEmpty()) }
                        .getOrDefault(NoteValue.QUARTER)
                    val dotted = parts.getOrNull(7) == "1"
                    val accidental = parts.getOrNull(8)?.takeIf { it != "-" && it.isNotBlank() }
                        ?.let { runCatching { Accidental.valueOf(it) }.getOrNull() }
                    val noteStartSec = parts.getOrNull(9)?.toDoubleOrNull() ?: startSec
                    val velocity = parts.getOrNull(10)?.toFloatOrNull() ?: 1f
                    val measure = parts.getOrNull(11)?.toIntOrNull() ?: 0
                    val letter = parts.getOrNull(12)?.toIntOrNull() ?: 0
                    val octave = parts.getOrNull(13)?.toIntOrNull() ?: 4
                    notes.add(
                        ScoreNote(
                            id = id,
                            midiPitch = midi,
                            startBeat = start,
                            durationBeats = duration,
                            staff = staff,
                            noteValue = value,
                            dotted = dotted,
                            accidental = accidental,
                            startSec = noteStartSec,
                            velocity = velocity,
                            measureIndex = measure,
                            letter = letter,
                            octave = octave,
                        ),
                    )
                }
                "rest" -> {
                    val start = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
                    val duration = parts.getOrNull(2)?.toDoubleOrNull() ?: continue
                    val staff = Staff.fromWire(parts.getOrNull(3)) ?: Staff.TREBLE
                    val value = runCatching { NoteValue.valueOf(parts.getOrNull(4).orEmpty()) }
                        .getOrDefault(NoteValue.QUARTER)
                    val dotted = parts.getOrNull(5) == "1"
                    val measure = parts.getOrNull(6)?.toIntOrNull() ?: 0
                    rests.add(ScoreRest(start, duration, staff, value, dotted, measure))
                }
            }
        }

        if (measures.isEmpty()) {
            measures.add(ScoreMeasure(0, 0.0, timeSignature.beatsPerMeasure))
        }
        return Score(
            projectId = projectId,
            title = title,
            tempoBpm = tempoBpm,
            tempoConfidence = tempoConfidence,
            timeSignature = timeSignature,
            timeSignatureConfidence = timeSigConfidence,
            keySignature = keySignature,
            keyConfidence = keyConfidence,
            quantization = quantization,
            measures = measures,
            notes = notes,
            rests = rests,
            scoreStartSec = startSec,
            durationSec = durationSec,
            detector = detector,
            createdAt = createdAt,
            liveMatched = liveMatched,
            analysisOnly = analysisOnly,
            liveOnly = liveOnly,
        )
    }

    private fun line(out: StringBuilder, key: String, vararg values: String) {
        out.append(key)
        for (value in values) out.append('|').append(value)
        out.append('\n')
    }

    private fun escape(value: String): String = value
        .replace("|", "/")
        .replace("\n", " ")
        .replace("\r", " ")

    private fun unescape(value: String): String = value.trim()
}
