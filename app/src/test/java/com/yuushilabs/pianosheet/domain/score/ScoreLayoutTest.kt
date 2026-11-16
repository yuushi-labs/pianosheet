package com.yuushilabs.pianosheet.domain.score

import com.yuushilabs.pianosheet.domain.final.FinalNote
import com.yuushilabs.pianosheet.domain.final.ScoreBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreLayoutTest {

    private fun measures(count: Int, timeSignature: TimeSignature = TimeSignature.COMMON): List<ScoreMeasure> =
        ScoreMath.measureStartBeats(timeSignature, count)

    private val metrics = LayoutMetrics(
        systemWidth = 320f,
        minMeasureWidth = 84f,
        maxMeasureWidth = 190f,
        systemHeight = 120f,
        systemGap = 6f,
    )

    @Test
    fun `measures wrap at the system width`() {
        val layout = ScoreLayoutEngine.layout(measures(12), metrics)
        assertTrue(layout.systems.size >= 2)
        for (system in layout.systems) {
            val last = system.measures.last()
            assertTrue("system ${system.index} overflows: ${last.endX}", last.endX <= metrics.systemWidth + 0.5f)
            assertEquals(0f, system.measures.first().x, 1e-4f)
        }
        // 12 measures at 84 px in a 320 px system: three per line.
        assertEquals(3, layout.systems.first().measures.size)
    }

    @Test
    fun `systems stack downwards without overlap`() {
        val layout = ScoreLayoutEngine.layout(measures(9), metrics)
        for (i in 1 until layout.systems.size) {
            assertTrue(layout.systems[i].top >= layout.systems[i - 1].bottom)
        }
        assertEquals(layout.systems.last().bottom, layout.totalHeight, 1e-4f)
    }

    @Test
    fun `a six-eight measure is narrower than a common-time measure`() {
        val common = ScoreLayoutEngine.layout(measures(2, TimeSignature.COMMON), metrics)
        val compound = ScoreLayoutEngine.layout(measures(2, TimeSignature.COMPOUND), metrics)
        val commonWidth = common.systems.first().measures.first().width
        val compoundWidth = compound.systems.first().measures.first().width
        assertTrue("$compoundWidth < $commonWidth", compoundWidth < commonWidth)
    }

    @Test
    fun `beat positions stay inside their measure`() {
        val layout = ScoreLayoutEngine.layout(measures(8), metrics)
        val box = layout.boxForMeasure(4)!!
        assertEquals(box.x, box.xForBeat(box.measure.startBeat), 1e-3f)
        assertEquals(box.endX, box.xForBeat(box.measure.endBeat), 1e-3f)
        val middle = box.xForBeat(box.measure.startBeat + box.measure.beats / 2)
        assertTrue(middle > box.x && middle < box.endX)
    }

    @Test
    fun `a long take with hundreds of notes lays out every measure`() {
        // 260 notes over three minutes, 4/4 at 120 BPM.
        val notes = (0 until 260).map { index ->
            FinalNote(
                pitch = if (index % 3 == 0) 48 else 72,
                onsetSec = index * 0.7,
                durationSec = 0.5,
                amplitude = 0.7f,
            )
        }
        val score = ScoreBuilder.build("p1", "long", notes, durationSec = 182.0)
        val layout = ScoreLayoutEngine.layout(score.measures, metrics)

        assertTrue(score.measures.size > 10)
        assertTrue(layout.systems.size > 3)
        for (note in score.notes) {
            assertNotNull("measure ${note.measureIndex} must have a box", layout.boxForMeasure(note.measureIndex))
            val x = layout.xForBeat(note.measureIndex, note.startBeat)
            assertTrue("x is finite: $x", !x.isNaN() && !x.isInfinite())
            assertTrue("x is inside the page: $x (width ${metrics.systemWidth})", x in 0f..metrics.systemWidth + 1f)
        }
        for (system in layout.systems) {
            for (measure in system.measures) {
                assertTrue(measure.width > 0f)
                assertTrue(measure.endX <= metrics.systemWidth + 0.5f)
            }
        }
    }

    @Test
    fun `an empty measure list produces no systems`() {
        val layout = ScoreLayoutEngine.layout(emptyList(), metrics)
        assertTrue(layout.systems.isEmpty())
        assertEquals(0f, layout.totalHeight, 1e-6f)
    }
}
