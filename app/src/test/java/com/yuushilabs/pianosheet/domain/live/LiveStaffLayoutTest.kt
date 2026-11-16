package com.yuushilabs.pianosheet.domain.live

import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live staff used to run out of the screen: spacing between notes collapsed
 * and the animated auto-scroll lagged behind, so after a minute nothing new was
 * visible.  These cases pin the geometry for long takes.
 */
class LiveStaffLayoutTest {

    private fun note(index: Int, onsetMs: Double, pitch: Int = 60): NoteEvent =
        NoteEvent(
            pitchMidi = pitch,
            onsetMs = onsetMs,
            sequence = index,
            staff = Staff.TREBLE,
        )

    private val viewport = 1000f
    private val minGap = 15f

    @Test
    fun `an empty staff never produces NaN geometry`() {
        val layout = LiveStaffLayoutEngine.compute(
            notes = emptyList(),
            viewportWidth = viewport,
            headerWidth = 0f,
            leftPad = 18f,
            minGap = minGap,
        )
        assertEquals(0, layout.xs.size)
        assertTrue(layout.contentWidth >= viewport)
        assertEquals(0f, layout.followOffset, 1e-6f)
    }

    @Test
    fun `dense notes keep a readable minimum spacing`() {
        // 16th notes at 120 BPM = 125 ms apart, which is less than a notehead at 64 px/s.
        val notes = (0 until 60).map { note(it, it * 125.0, 60 + (it % 5)) }
        val layout = LiveStaffLayoutEngine.compute(
            notes = notes,
            viewportWidth = viewport,
            headerWidth = 0f,
            leftPad = 18f,
            minGap = minGap,
        )
        val quiet = layout.xs.distinct().sorted()
        for (i in 1 until quiet.size) {
            assertTrue("gap between ${quiet[i - 1]} and ${quiet[i]}", quiet[i] - quiet[i - 1] >= minGap - 1e-3f)
        }
    }

    @Test
    fun `chord notes share one horizontal position`() {
        val notes = listOf(
            note(0, 1000.0, 60),
            note(1, 1000.0, 64),
            note(2, 1000.0, 67),
            note(3, 1500.0, 72),
        )
        val layout = LiveStaffLayoutEngine.compute(
            notes = notes,
            viewportWidth = viewport,
            headerWidth = 0f,
            leftPad = 18f,
            minGap = minGap,
        )
        assertEquals(layout.xs[0], layout.xs[1], 1e-4f)
        assertEquals(layout.xs[0], layout.xs[2], 1e-4f)
        assertTrue(layout.xs[3] > layout.xs[2])
    }

    @Test
    fun `three minutes and 260 notes stay inside the viewport while following`() {
        val notes = (0 until 260).map { index ->
            // 0.7 notes per second, mixed registers
            note(index, index * 690.0, if (index % 2 == 0) 67 else 48)
        }
        val layout = LiveStaffLayoutEngine.compute(
            notes = notes,
            viewportWidth = viewport,
            headerWidth = 0f,
            leftPad = 18f,
            minGap = minGap,
        )

        assertEquals(260, layout.xs.size)
        assertTrue("content grows with the take", layout.contentWidth > 260 * minGap)
        assertTrue(layout.maxScroll > 0f)
        assertTrue(layout.followOffset in 0f..layout.maxScroll)

        val visibleLeft = layout.followOffset
        val visibleRight = visibleLeft + viewport
        assertTrue("newest note is inside the viewport", layout.lastX <= visibleRight)
        assertTrue(
            "newest note is not glued to the edge",
            layout.lastX - visibleLeft >= viewport * 0.4f,
        )
    }

    @Test
    fun `the follow offset stops growing once the content fits`() {
        val notes = (0 until 3).map { note(it, it * 250.0) }
        val layout = LiveStaffLayoutEngine.compute(
            notes = notes,
            viewportWidth = viewport,
            headerWidth = 0f,
            leftPad = 18f,
            minGap = minGap,
        )
        assertEquals(0f, layout.followOffset, 1e-6f)
        assertTrue(layout.contentWidth >= viewport)
    }

    @Test
    fun `out of order events are placed in time order`() {
        val notes = listOf(note(0, 2000.0), note(1, 1000.0), note(2, 1500.0))
        val layout = LiveStaffLayoutEngine.compute(
            notes = notes,
            viewportWidth = viewport,
            headerWidth = 0f,
            leftPad = 18f,
            minGap = minGap,
        )
        assertTrue(layout.xs[1] < layout.xs[2])
        assertTrue(layout.xs[2] < layout.xs[0])
    }
}
