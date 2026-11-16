package com.yuushilabs.pianosheet.domain.model

/**
 * Maps a MIDI pitch to its vertical position on a grand staff.
 *
 * The unit is one *diatonic step*: a move from a line to the adjacent space.
 * Step 0 is the bottom line of the staff (E4 in treble clef, G2 in bass clef)
 * and step 8 is the top line.  Positions outside 0..8 need ledger lines.
 */
object StaffNotation {
    /** Semitone offset inside an octave -> letter index C D E F G A B. */
    private val LETTER_FOR_SEMITONE = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)

    private const val TREBLE_BOTTOM_LINE_MIDI = 64 // E4
    private const val BASS_BOTTOM_LINE_MIDI = 43 // G2
    const val TREBLE_TOP_STEP = 8
    const val BASS_TOP_STEP = 8
    const val MIDDLE_C = 60

    /** Absolute diatonic index: C0 = 0, D0 = 1, … B0 = 6, C1 = 7 … */
    fun diatonicIndex(midi: Int): Int {
        val octave = midi / 12 - 1
        return octave * 7 + LETTER_FOR_SEMITONE[((midi % 12) + 12) % 12]
    }

    fun stepOnStaff(midi: Int, clef: Staff): Int =
        when (clef) {
            Staff.TREBLE -> diatonicIndex(midi) - diatonicIndex(TREBLE_BOTTOM_LINE_MIDI)
            Staff.BASS -> diatonicIndex(midi) - diatonicIndex(BASS_BOTTOM_LINE_MIDI)
        }

    /** Provisional clef split: middle C and above on the treble staff. */
    fun clefFor(midi: Int): Staff = if (midi >= MIDDLE_C) Staff.TREBLE else Staff.BASS

    fun isLine(step: Int): Boolean = step % 2 == 0

    /**
     * Ledger-line steps needed for a note at [step]: the lines above / below the
     * staff that the notehead crosses.  A note on a line outside the staff needs
     * that line; a note in the space just outside needs none.
     */
    fun ledgerSteps(step: Int): List<Int> {
        if (step in 0..TREBLE_TOP_STEP) return emptyList()
        val result = ArrayList<Int>()
        if (step < 0) {
            var line = -2
            while (line >= step) {
                result.add(line)
                line -= 2
            }
        } else {
            var line = 10
            while (line <= step) {
                result.add(line)
                line += 2
            }
        }
        return result
    }

    /** Note name without the octave, for labels on the keyboard. */
    fun noteLetter(midi: Int): String = Pitch.NAMES[((midi % 12) + 12) % 12]

    fun octave(midi: Int): Int = midi / 12 - 1
}
