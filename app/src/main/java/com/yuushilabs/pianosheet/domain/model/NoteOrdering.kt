package com.yuushilabs.pianosheet.domain.model

/**
 * Display order for note lists (spec §3.6.3).
 *
 * The provisional list used to be printed in `sequence` order, but the live
 * engine numbers events by the step that emitted them, so a note that a later
 * step refined could appear after a later onset (5.17 s followed by 5.08 s in
 * the Phase 1 screenshot).  Lists are now ordered by onset, then pitch.
 */
object NoteOrdering {

    fun chronological(notes: List<NoteEvent>): List<NoteEvent> =
        notes.sortedWith(compareBy({ it.onsetMs }, { it.pitchMidi }, { it.sequence }))

    /** Onset + pitch, keeping the original index so the UI can show "何番目に鳴った音". */
    fun chronologicalIndexed(notes: List<NoteEvent>): List<Pair<Int, NoteEvent>> =
        chronological(notes).mapIndexed { index, note -> (index + 1) to note }
}
