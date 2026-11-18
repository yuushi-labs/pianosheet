package com.yuushilabs.pianosheet.domain.live

import com.yuushilabs.pianosheet.domain.model.NoteEvent
import kotlin.math.max

/**
 * Horizontal placement and auto-follow for the live staff (spec §3.3.1, §4.9).
 *
 * The old live view placed every note at `onset × pixelsPerSecond` and scrolled
 * with an animation towards a constantly moving edge.  Two things went wrong
 * after the first minute: notes 0.1 s apart landed on top of each other, and the
 * animated scroll could not keep up with the growing canvas, so the newest notes
 * were drawn past the right edge and never came into view.
 *
 * This layout fixes both:
 *  - notes are grouped by attack time, and each group is pushed right by
 *    [minGap] when the musical spacing would be narrower than a notehead,
 *  - the content width always includes a trailing margin, and
 *  - [followScroll] computes the *instant* scroll offset that puts the newest
 *    note at a fixed fraction of the viewport, so following never lags behind
 *    and stays valid for hundreds of notes.
 */
data class LiveStaffLayout(
    /** One x per note, in the same order the notes were given (time order). */
    val xs: FloatArray,
    val contentWidth: Float,
    val lastX: Float,
    val viewportWidth: Float,
    val maxScroll: Float,
    val followOffset: Float,
)

object LiveStaffLayoutEngine {

    /** 64 px/s keeps a 16th note at 120 BPM about a notehead apart. */
    const val DEFAULT_PIXELS_PER_SECOND = 64f

    /** Where the newest note should sit horizontally while following (0..1 of the viewport). */
    const val FOLLOW_FRACTION = 0.68f

    /**
     * @param notes notes in performance order; they are re-sorted by onset/pitch
     *   here so a late event cannot invert the x axis.
     */
    fun compute(
        notes: List<NoteEvent>,
        viewportWidth: Float,
        headerWidth: Float,
        leftPad: Float,
        minGap: Float,
        pixelsPerSecond: Float = DEFAULT_PIXELS_PER_SECOND,
        rightMarginFraction: Float = 0.62f,
    ): LiveStaffLayout {
        val count = notes.size
        if (count == 0) {
            val empty = max(viewportWidth, 1f)
            return LiveStaffLayout(
                xs = FloatArray(0),
                contentWidth = empty,
                lastX = leftPad + headerWidth,
                viewportWidth = empty,
                maxScroll = 0f,
                followOffset = 0f,
            )
        }

        val order = notes.indices.sortedWith(
            compareBy({ notes[it].onsetMs }, { notes[it].pitchMidi }, { notes[it].sequence }),
        )
        val xs = FloatArray(count)
        val originX = leftPad + headerWidth
        // The staff starts at the first note, not at the moment the recording
        // started: waiting five seconds before playing used to leave that wait
        // drawn as empty staff at the head of the live score.
        val firstOnsetMs = notes[order.first()].onsetMs
        var previousGroupOnset = -1.0
        var groupX = originX
        var lastX = originX
        for (slot in order.indices) {
            val note = notes[order[slot]]
            val onset = note.onsetMs
            val sinceFirst = ((onset - firstOnsetMs) / 1000.0).toFloat().coerceAtLeast(0f)
            val timeX = originX + sinceFirst * pixelsPerSecond
            if (previousGroupOnset < 0.0 || onset - previousGroupOnset > GROUP_EPSILON_MS) {
                groupX = max(timeX, groupX + if (previousGroupOnset < 0.0) 0f else minGap)
                previousGroupOnset = onset
            }
            xs[order[slot]] = groupX
            lastX = max(lastX, groupX)
        }

        val safeViewport = max(viewportWidth, 1f)
        val contentWidth = max(lastX + safeViewport * rightMarginFraction, safeViewport)
        val maxScroll = max(0f, contentWidth - safeViewport)
        return LiveStaffLayout(
            xs = xs,
            contentWidth = contentWidth,
            lastX = lastX,
            viewportWidth = safeViewport,
            maxScroll = maxScroll,
            followOffset = followScroll(lastX, contentWidth, safeViewport),
        )
    }

    /** Scroll offset that shows the newest note at [FOLLOW_FRACTION] of the viewport. */
    fun followScroll(
        newestX: Float,
        contentWidth: Float,
        viewportWidth: Float,
        fraction: Float = FOLLOW_FRACTION,
    ): Float {
        val safeViewport = max(viewportWidth, 1f)
        val maxScroll = max(0f, contentWidth - safeViewport)
        return (newestX - safeViewport * fraction).coerceIn(0f, maxScroll)
    }

    private const val GROUP_EPSILON_MS = 1.0
}
