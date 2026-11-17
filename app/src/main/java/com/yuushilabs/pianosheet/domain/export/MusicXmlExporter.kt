package com.yuushilabs.pianosheet.domain.export

import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.score.Accidental
import com.yuushilabs.pianosheet.domain.score.Mode
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.ScoreMeasure
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes a finished [Score] as standard MusicXML (`<score-partwise>`, 4.0).
 *
 * The viewer, the MIDI export and every notation app the file goes to read this
 * one document, so the rules it keeps are strict:
 *
 *  - one part (`P1`) with two staves (`<staves>2</staves>`), treble clef on
 *    staff 1 and bass clef on staff 2, as a piano grand staff;
 *  - staff 1 is voice 1, staff 2 is voice 2, separated by `<backup>`, so the
 *    `<duration>` values of each staff add up to the measure length exactly;
 *  - simultaneous notes in the same staff become one `<chord/>` group;
 *  - a note that would run past a bar line is split at the bar line and tied
 *    (`<tie>` plus `<notations><tied>`);
 *  - anything the two voices leave empty is filled with rests, so no measure is
 *    short and no player has to guess.
 *
 * Everything is pure Kotlin: the unit tests build scores by hand and read the
 * XML back with a parser, without Android.
 */
object MusicXmlExporter {

    const val SOFTWARE = "PianoSheet"
    const val VERSION = "4.0"
    const val MIME_TYPE = "application/vnd.recordare.musicxml+xml"
    const val FILE_EXTENSION = "musicxml"

    data class Options(
        /** Ticks per quarter note before the score forces a finer resolution. */
        val baseDivisions: Int = 4,
        /** Write the tempo as a `<direction>` with a metronome mark. */
        val includeTempo: Boolean = true,
        /** Some old readers choke on the DTD line; the tests turn it off. */
        val includeDoctype: Boolean = true,
        /** Fixed value for `<encoding-date>`; null means "today". */
        val encodingDate: String? = null,
        val partName: String = "Piano",
        /**
         * The notation grid, in quarter-note beats (0.25 = sixteenth).  Starts and
         * durations are snapped to it so every `<duration>` is exactly one
         * writable note value; 0 exports the performance timing untouched.
         */
        val gridBeats: Double = 0.25,
    )

    // ---------------------------------------------------------------- public

    fun toMusicXml(score: Score, options: Options = Options()): String {
        val notes = snapNotes(score.notes, options.gridBeats)
        val divisions = divisionsFor(score, notes, options.baseDivisions)
        val measures = measuresOf(score)
        val out = StringBuilder(8192)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        if (options.includeDoctype) {
            out.append("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML ")
                .append(VERSION)
                .append(" Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n")
        }
        out.append("<score-partwise version=\"").append(VERSION).append("\">\n")
        appendWork(out, score)
        appendIdentification(out, options)
        appendPartList(out, options)
        out.append("  <part id=\"P1\">\n")
        appendMeasures(out, score, notes, measures, divisions, options)
        out.append("  </part>\n")
        out.append("</score-partwise>\n")
        return out.toString()
    }

    /** Smallest ticks-per-quarter that keeps every beat position integral. */
    fun divisionsFor(score: Score, options: Options = Options()): Int =
        divisionsFor(score, snapNotes(score.notes, options.gridBeats), options.baseDivisions)

    private fun divisionsFor(score: Score, notes: List<ScoreNote>, base: Int): Int {
        var divisions = max(1, base)
        while (divisions < 64 && !isExact(score, notes, divisions)) divisions *= 2
        return divisions
    }

    /**
     * Snaps every attack and length onto the notation grid.  The score keeps the
     * performance timing next to the notated value on purpose (spec §5.3); the
     * printed page only has room for the notated one.
     */
    fun snapNotes(notes: List<ScoreNote>, grid: Double): List<ScoreNote> {
        if (grid <= 0.0) return notes
        return notes.map { note ->
            val start = (note.startBeat / grid).roundToInt() * grid
            val length = ((note.durationBeats / grid).roundToInt().coerceAtLeast(1)) * grid
            if (abs(start - note.startBeat) < 1e-9 && abs(length - note.durationBeats) < 1e-9) {
                note
            } else {
                note.copy(startBeat = start, durationBeats = length)
            }
        }
    }

    // --------------------------------------------------------------- header

    private fun appendWork(out: StringBuilder, score: Score) {
        val title = score.title.trim()
        if (title.isEmpty()) return
        out.append("  <work>\n")
        out.append("    <work-title>").append(escape(title)).append("</work-title>\n")
        out.append("  </work>\n")
        out.append("  <movement-title>").append(escape(title)).append("</movement-title>\n")
    }

    private fun appendIdentification(out: StringBuilder, options: Options) {
        val date = options.encodingDate ?: today()
        out.append("  <identification>\n")
        out.append("    <creator type=\"software\">").append(SOFTWARE).append("</creator>\n")
        out.append("    <encoding>\n")
        out.append("      <software>").append(SOFTWARE).append("</software>\n")
        out.append("      <encoding-date>").append(escape(date)).append("</encoding-date>\n")
        out.append("    </encoding>\n")
        out.append("  </identification>\n")
    }

    private fun appendPartList(out: StringBuilder, options: Options) {
        out.append("  <part-list>\n")
        out.append("    <score-part id=\"P1\">\n")
        out.append("      <part-name>").append(escape(options.partName)).append("</part-name>\n")
        out.append("      <score-instrument id=\"P1-I1\">\n")
        out.append("        <instrument-name>").append(escape(options.partName)).append("</instrument-name>\n")
        out.append("      </score-instrument>\n")
        out.append("      <midi-instrument id=\"P1-I1\">\n")
        out.append("        <midi-channel>1</midi-channel>\n")
        out.append("        <midi-program>1</midi-program>\n")
        out.append("      </midi-instrument>\n")
        out.append("    </score-part>\n")
        out.append("  </part-list>\n")
    }

    // -------------------------------------------------------------- measures

    private fun appendMeasures(
        out: StringBuilder,
        score: Score,
        notes: List<ScoreNote>,
        measures: List<ScoreMeasure>,
        divisions: Int,
        options: Options,
    ) {
        val measureStart = measures.map { beatToTick(it.startBeat, divisions) }
        val measureEnd = measures.map { beatToTick(it.endBeat, divisions) }
        val right = segmentsFor(notes.filter { it.staff == Staff.TREBLE }, measureStart, measureEnd, divisions)
        val left = segmentsFor(notes.filter { it.staff == Staff.BASS }, measureStart, measureEnd, divisions)
        val rightByMeasure = right.groupBy { it.measure }
        val leftByMeasure = left.groupBy { it.measure }

        for ((index, _) in measures.withIndex()) {
            val startTick = measureStart[index]
            val endTick = measureEnd[index]
            val ticks = max(1, endTick - startTick)
            // A first measure shorter than the bar is a pickup (anacrusis): MusicXML
            // wants it marked so readers do not count a missing beat as an error.
            val pickup = index == 0 && measures.size > 1 && measures[0].beats < score.timeSignature.beatsPerMeasure - 1e-6
            out.append("    <measure number=\"").append(index + 1).append("\"")
            if (pickup) out.append(" implicit=\"yes\"")
            out.append(">\n")
            if (index == 0) {
                appendAttributes(out, score, divisions)
                if (options.includeTempo) appendTempo(out, score)
            }

            val rightElements = elementsOf(rightByMeasure[index].orEmpty(), startTick, endTick)
            val leftElements = elementsOf(leftByMeasure[index].orEmpty(), startTick, endTick)
            for (element in rightElements) appendElement(out, element, voice = 1, staff = 1, divisions = divisions)
            out.append("      <backup>\n")
            out.append("        <duration>").append(ticks).append("</duration>\n")
            out.append("      </backup>\n")
            for (element in leftElements) appendElement(out, element, voice = 2, staff = 2, divisions = divisions)

            if (index == measures.lastIndex) {
                out.append("      <barline location=\"right\">\n")
                out.append("        <bar-style>light-heavy</bar-style>\n")
                out.append("      </barline>\n")
            }
            out.append("    </measure>\n")
        }
    }

    private fun appendAttributes(out: StringBuilder, score: Score, divisions: Int) {
        val signature = score.timeSignature
        val key = score.keySignature
        out.append("      <attributes>\n")
        out.append("        <divisions>").append(divisions).append("</divisions>\n")
        out.append("        <key>\n")
        out.append("          <fifths>").append(key.fifths).append("</fifths>\n")
        out.append("          <mode>").append(if (key.mode == Mode.MAJOR) "major" else "minor").append("</mode>\n")
        out.append("        </key>\n")
        out.append("        <time>\n")
        out.append("          <beats>").append(signature.numerator).append("</beats>\n")
        out.append("          <beat-type>").append(signature.denominator).append("</beat-type>\n")
        out.append("        </time>\n")
        out.append("        <staves>2</staves>\n")
        out.append("        <clef number=\"1\">\n")
        out.append("          <sign>G</sign>\n")
        out.append("          <line>2</line>\n")
        out.append("        </clef>\n")
        out.append("        <clef number=\"2\">\n")
        out.append("          <sign>F</sign>\n")
        out.append("          <line>4</line>\n")
        out.append("        </clef>\n")
        out.append("      </attributes>\n")
    }

    private fun appendTempo(out: StringBuilder, score: Score) {
        val bpm = score.tempoBpm.roundToInt().coerceIn(20, 400)
        out.append("      <direction placement=\"above\">\n")
        out.append("        <direction-type>\n")
        out.append("          <metronome>\n")
        out.append("            <beat-unit>quarter</beat-unit>\n")
        out.append("            <per-minute>").append(bpm).append("</per-minute>\n")
        out.append("          </metronome>\n")
        out.append("        </direction-type>\n")
        out.append("        <sound tempo=\"").append(bpm).append("\"/>\n")
        out.append("      </direction>\n")
    }

    // -------------------------------------------------------------- timeline

    /** One attack (a single note or a chord) cut into the measures it covers. */
    private class Segment(
        val measure: Int,
        val startTick: Int,
        val ticks: Int,
        val notes: List<ScoreNote>,
        val tieStop: Boolean,
        val tieStart: Boolean,
    )

    private class RestSpan(val startTick: Int, val ticks: Int)

    private fun segmentsFor(
        notes: List<ScoreNote>,
        measureStart: List<Int>,
        measureEnd: List<Int>,
        divisions: Int,
    ): List<Segment> {
        if (notes.isEmpty() || measureStart.isEmpty()) return emptyList()
        val sorted = notes.sortedWith(compareBy({ beatToTick(it.startBeat, divisions) }, { it.midiPitch }))

        data class Group(val start: Int, val notes: MutableList<ScoreNote>)

        val groups = ArrayList<Group>()
        for (note in sorted) {
            val tick = beatToTick(note.startBeat, divisions)
            val last = groups.lastOrNull()
            if (last != null && last.start == tick) last.notes.add(note) else groups.add(Group(tick, mutableListOf(note)))
        }

        val segments = ArrayList<Segment>()
        for ((index, group) in groups.withIndex()) {
            val longest = group.notes.maxOf { note ->
                beatToTick(note.startBeat + note.durationBeats, divisions) - group.start
            }.coerceAtLeast(1)
            val nextStart = groups.getOrNull(index + 1)?.start
            // Two attacks cannot share the same voice: the earlier note stops where
            // the next one starts, which is exactly how a pianist would read it.
            var end = group.start + longest
            if (nextStart != null && nextStart in (group.start + 1) until end) end = nextStart
            if (end <= group.start) continue

            var position = group.start
            var firstSegment = true
            var guard = 0
            while (position < end && guard < 128) {
                guard += 1
                val measureIndex = measureIndexAt(position, measureStart)
                val boundary = measureEnd.getOrElse(measureIndex) { end }
                if (boundary <= position) break
                val segmentEnd = minOf(end, boundary)
                segments.add(
                    Segment(
                        measure = measureIndex,
                        startTick = position,
                        ticks = segmentEnd - position,
                        notes = group.notes,
                        tieStop = !firstSegment,
                        tieStart = segmentEnd < end,
                    ),
                )
                firstSegment = false
                position = segmentEnd
            }
        }
        return segments
    }

    private fun measureIndexAt(tick: Int, measureStart: List<Int>): Int {
        var result = 0
        for (index in measureStart.indices) {
            if (measureStart[index] <= tick) result = index else break
        }
        return result
    }

    private fun elementsOf(segments: List<Segment>, startTick: Int, endTick: Int): List<Any> {
        val elements = ArrayList<Any>()
        var cursor = startTick
        for (segment in segments.sortedBy { it.startTick }) {
            if (segment.startTick > cursor) elements.add(RestSpan(cursor, segment.startTick - cursor))
            elements.add(segment)
            cursor = max(cursor, segment.startTick + segment.ticks)
        }
        if (cursor < endTick) elements.add(RestSpan(cursor, endTick - cursor))
        return elements
    }

    // ---------------------------------------------------------------- output

    private fun appendElement(out: StringBuilder, element: Any, voice: Int, staff: Int, divisions: Int) {
        when (element) {
            is Segment -> appendChord(out, element, voice, staff, divisions)
            is RestSpan -> {
                for (unit in valueUnits(element.ticks, divisions)) {
                    appendRest(out, unit, voice, staff, divisions)
                }
            }
        }
    }

    private fun appendChord(out: StringBuilder, segment: Segment, voice: Int, staff: Int, divisions: Int) {
        // A clipped or odd length (say 1.25 beats) has no single note value: it
        // is written as a quarter tied to a sixteenth, which is what a player
        // would read and what keeps <duration> and <type> in step.
        val units = valueUnits(segment.ticks, divisions)
        for ((unitIndex, unitTicks) in units.withIndex()) {
            val type = typeFor(unitTicks, divisions)
            val dotted = isDotted(unitTicks, divisions)
            val tieStop = segment.tieStop || unitIndex > 0
            val tieStart = segment.tieStart || unitIndex < units.lastIndex
            for ((noteIndex, note) in segment.notes.withIndex()) {
                val spelling = spellingOf(note)
                out.append("      <note>\n")
                if (noteIndex > 0) out.append("        <chord/>\n")
                out.append("        <pitch>\n")
                out.append("          <step>").append(spelling.step).append("</step>\n")
                if (spelling.alter != 0) out.append("          <alter>").append(spelling.alter).append("</alter>\n")
                out.append("          <octave>").append(spelling.octave).append("</octave>\n")
                out.append("        </pitch>\n")
                out.append("        <duration>").append(unitTicks).append("</duration>\n")
                if (tieStop) out.append("        <tie type=\"stop\"/>\n")
                if (tieStart) out.append("        <tie type=\"start\"/>\n")
                out.append("        <voice>").append(voice).append("</voice>\n")
                out.append("        <type>").append(type).append("</type>\n")
                if (dotted) out.append("        <dot/>\n")
                // The accidental belongs on the attack only.
                if (unitIndex == 0) {
                    note.accidental?.let { accidental ->
                        out.append("        <accidental>").append(accidentalName(accidental)).append("</accidental>\n")
                    }
                }
                out.append("        <staff>").append(staff).append("</staff>\n")
                if (tieStop || tieStart) {
                    out.append("        <notations>\n")
                    if (tieStop) out.append("          <tied type=\"stop\"/>\n")
                    if (tieStart) out.append("          <tied type=\"start\"/>\n")
                    out.append("        </notations>\n")
                }
                out.append("      </note>\n")
            }
        }
    }

    private fun appendRest(out: StringBuilder, ticks: Int, voice: Int, staff: Int, divisions: Int) {
        val type = typeFor(ticks, divisions)
        out.append("      <note>\n")
        out.append("        <rest/>\n")
        out.append("        <duration>").append(ticks).append("</duration>\n")
        out.append("        <voice>").append(voice).append("</voice>\n")
        out.append("        <type>").append(type).append("</type>\n")
        if (isDotted(ticks, divisions)) out.append("        <dot/>\n")
        out.append("        <staff>").append(staff).append("</staff>\n")
        out.append("      </note>\n")
    }

    // ----------------------------------------------------------------- maths

    /** Writable lengths (beats) longest first: whole … sixteenth with dots. */
    private val UNIT_BEATS = listOf(4.0, 3.0, 2.0, 1.5, 1.0, 0.75, 0.5, 0.25)

    /**
     * Splits a length into writable values, longest first.  The remainder is kept
     * as one final rest/note so a gap is never dropped.
     */
    fun valueUnits(totalTicks: Int, divisions: Int): List<Int> {
        if (totalTicks <= 0) return emptyList()
        val units = ArrayList<Int>()
        var remaining = totalTicks
        for (value in UNIT_BEATS) {
            val ticks = (value * divisions).roundToInt()
            if (ticks <= 0) continue
            while (remaining >= ticks) {
                units.add(ticks)
                remaining -= ticks
            }
        }
        if (remaining > 0) units.add(remaining)
        return units
    }

    data class Spelling(val step: String, val alter: Int, val octave: Int)

    private val NATURAL_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val LETTERS = arrayOf("C", "D", "E", "F", "G", "A", "B")

    /** Pitch spelling from the note's own letter/octave, with a safe fallback. */
    fun spellingOf(note: ScoreNote): Spelling {
        val letter = note.letter.coerceIn(0, 6)
        val octave = note.octave
        val natural = 12 * (octave + 1) + NATURAL_SEMITONES[letter]
        val alter = note.midiPitch - natural
        if (alter in -2..2) return Spelling(LETTERS[letter], alter, octave)
        return spellingFromPitch(note.midiPitch)
    }

    /** Sharp spelling of a MIDI pitch (61 -> C#4). */
    fun spellingFromPitch(midi: Int): Spelling {
        val pitchClass = ((midi % 12) + 12) % 12
        val steps = arrayOf("C", "C", "D", "D", "E", "F", "F", "G", "G", "A", "A", "B")
        val alterations = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        val octave = midi / 12 - 1
        return Spelling(steps[pitchClass], alterations[pitchClass], octave)
    }

    private fun accidentalName(accidental: Accidental): String = when (accidental) {
        Accidental.SHARP -> "sharp"
        Accidental.FLAT -> "flat"
        Accidental.NATURAL -> "natural"
    }

    /** value in quarter-note beats, `<type>` name, dotted */
    private val TYPE_TABLE: List<Triple<Double, String, Boolean>> = listOf(
        Triple(4.0, "whole", false),
        Triple(6.0, "whole", true),
        Triple(3.0, "half", true),
        Triple(2.0, "half", false),
        Triple(1.5, "quarter", true),
        Triple(1.0, "quarter", false),
        Triple(0.75, "eighth", true),
        Triple(0.5, "eighth", false),
        Triple(0.375, "16th", true),
        Triple(0.25, "16th", false),
        Triple(0.1875, "32nd", true),
        Triple(0.125, "32nd", false),
        Triple(0.0625, "64th", false),
    )

    private fun typeFor(ticks: Int, divisions: Int): String = nearestValue(ticks, divisions).first

    private fun isDotted(ticks: Int, divisions: Int): Boolean = nearestValue(ticks, divisions).second

    private fun nearestValue(ticks: Int, divisions: Int): Pair<String, Boolean> {
        val beats = ticks.toDouble() / divisions
        var best = TYPE_TABLE[5]
        var bestError = Double.MAX_VALUE
        for (entry in TYPE_TABLE) {
            val error = abs(entry.first - beats)
            val tieBetter = abs(error - bestError) < 1e-9 && best.third && !entry.third
            if (error < bestError - 1e-9 || tieBetter) {
                best = entry
                bestError = error
            }
        }
        return best.second to best.third
    }

    fun beatToTick(beat: Double, divisions: Int): Int = (beat * divisions).roundToInt()

    private fun isExact(score: Score, notes: List<ScoreNote>, divisions: Int): Boolean {
        fun exact(value: Double): Boolean {
            val scaled = value * divisions
            return abs(scaled - scaled.roundToInt()) < 1e-6 * max(1.0, abs(scaled))
        }
        return notes.all { exact(it.startBeat) && exact(it.durationBeats) } &&
            score.measures.all { exact(it.startBeat) && exact(it.beats) }
    }

    private fun measuresOf(score: Score): List<ScoreMeasure> =
        if (score.measures.isEmpty()) {
            listOf(ScoreMeasure(0, 0.0, score.timeSignature.beatsPerMeasure))
        } else {
            score.measures
        }

    private fun today(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return format.format(java.util.Date())
    }

    /** XML text escaping; the score title comes from the user's own recording name. */
    fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> if (ch == '\n' || ch == '\t' || ch >= ' ') out.append(ch)
            }
        }
        return out.toString()
    }
}
