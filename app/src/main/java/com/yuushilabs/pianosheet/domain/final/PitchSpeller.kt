package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.model.StaffNotation
import com.yuushilabs.pianosheet.domain.score.Accidental
import com.yuushilabs.pianosheet.domain.score.KeySignature
import com.yuushilabs.pianosheet.domain.score.Mode
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import kotlin.math.abs

/** How one MIDI pitch is written: letter (C=0…B=6), octave and optional accidental. */
data class Spelling(
    val letter: Int,
    val octave: Int,
    val accidental: Accidental?,
) {
    val diatonicIndex: Int get() = octave * 7 + letter

    fun stepOn(staff: Staff): Int =
        diatonicIndex - (if (staff == Staff.TREBLE) StaffNotation.trebleBottomDiatonic else StaffNotation.bassBottomDiatonic)

    fun letterName(): String = StaffNotation.LETTER_NAMES[letter]
}

/**
 * Writes each pitch the way the estimated key spells it (spec §5.2 "臨時記号",
 * §5.6).  A note inside the key's scale gets no accidental; a chromatic note is
 * spelled as a sharp or a flat depending on the signature's direction, so an
 * F# in G major stays F# and a B♭ in F major stays B♭ instead of the other way
 * round.
 */
object PitchSpeller {

    private val SCALE_STEPS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /** Which letter (C=0 … B=6) spells the major tonic of each signature. */
    private val TONIC_LETTER_BY_FIFTHS = intArrayOf(0, 4, 1, 5, 2, 6, 3, 0, 4, 1, 5, 2, 6, 3, 0)

    /**
     * Pitch class of each letter inside the key's (major) scale.
     *
     * The letters are absolute, so the scale has to start from the letter the
     * tonic is written with: F major writes its B as B♭, whereas C major writes a
     * plain B.  Minor keys use their relative major for spelling.
     */
    fun scalePitchClasses(key: KeySignature): IntArray {
        val majorTonic = if (key.mode == Mode.MAJOR) {
            key.tonicPitchClass
        } else {
            (key.tonicPitchClass + 3) % 12 // relative major (natural minor spelling)
        }
        val tonicLetter = TONIC_LETTER_BY_FIFTHS[key.fifths + 7]
        return IntArray(7) { letter ->
            val step = SCALE_STEPS[(letter - tonicLetter + 7) % 7]
            (majorTonic + step) % 12
        }
    }

    fun spell(midiPitch: Int, key: KeySignature): Spelling {
        val pitchClass = ((midiPitch % 12) + 12) % 12
        val scale = scalePitchClasses(key)
        val naturalLetter = (0 until 7).firstOrNull { scale[it] == pitchClass }
        if (naturalLetter != null) {
            return Spelling(naturalLetter, octaveFor(naturalLetter, midiPitch), null)
        }
        val sharpLetter = (0 until 7).firstOrNull { (scale[it] + 1) % 12 == pitchClass }
        val flatLetter = (0 until 7).firstOrNull { (scale[it] + 11) % 12 == pitchClass }
        // Sharp keys spell chromatically raised notes with sharps and flat keys with
        // flats; without a signature the common practice spellings win (C#, Eb, F#, Ab, Bb).
        val useFlatFirst = key.preferFlats ||
            (key.fifths == 0 && pitchClass != 1 && pitchClass != 6)
        val letter = when {
            useFlatFirst && flatLetter != null -> flatLetter
            sharpLetter != null -> sharpLetter
            flatLetter != null -> flatLetter
            else -> StaffNotation.letterForSemitone(pitchClass)
        }
        val accidental = if (scale[letter] == pitchClass) {
            null
        } else if ((scale[letter] + 1) % 12 == pitchClass) {
            Accidental.SHARP
        } else {
            Accidental.FLAT
        }
        return Spelling(letter, octaveFor(letter, midiPitch), accidental)
    }

    /** Octave that puts the written letter closest to the sounding pitch. */
    private fun octaveFor(letter: Int, midiPitch: Int): Int {
        val target = StaffNotation.diatonicIndex(midiPitch)
        val octave = midiPitch / 12 - 1
        var best = octave
        var bestDistance = Int.MAX_VALUE
        for (candidate in octave - 1..octave + 1) {
            val distance = abs(candidate * 7 + letter - target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }

    /**
     * Accidental to print.  The key signature already covers every note inside
     * the key's scale, so only chromatic notes carry a sign — and only the first
     * one per letter and octave in a measure, because the sign carries forward.
     *
     * [inEffect] maps letter+octave to the accidental currently in effect in the
     * measure (null = natural); the caller updates it after each note.
     */
    fun accidentalToPrint(
        spelling: Spelling,
        inEffect: Map<Pair<Int, Int>, Accidental?>,
    ): Accidental? {
        val key = spelling.letter to spelling.octave
        val current = inEffect[key]
        return when {
            spelling.accidental == null -> if (current != null) Accidental.NATURAL else null
            current == spelling.accidental -> null
            else -> spelling.accidental
        }
    }

    /** Staff step of a spelled note: 0 = bottom line, 8 = top line. */
    fun stepOnStaff(spelling: Spelling, staff: Staff): Int = spelling.stepOn(staff)

    /** Convenience used by the builder and the tests. */
    fun makeNote(
        id: String,
        midiPitch: Int,
        startBeat: Double,
        durationBeats: Double,
        staff: Staff,
        key: KeySignature,
        startSec: Double,
        velocity: Float,
        measureIndex: Int,
        noteValue: com.yuushilabs.pianosheet.domain.score.NoteValue,
        dotted: Boolean,
        accidental: Accidental?,
    ): ScoreNote {
        val spelling = spell(midiPitch, key)
        return ScoreNote(
            id = id,
            midiPitch = midiPitch,
            startBeat = startBeat,
            durationBeats = durationBeats,
            staff = staff,
            noteValue = noteValue,
            dotted = dotted,
            accidental = accidental,
            startSec = startSec,
            velocity = velocity,
            measureIndex = measureIndex,
            letter = spelling.letter,
            octave = spelling.octave,
        )
    }
}
