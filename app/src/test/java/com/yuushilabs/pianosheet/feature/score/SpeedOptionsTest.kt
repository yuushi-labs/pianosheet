package com.yuushilabs.pianosheet.feature.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five speed chips have to fit the width of a 720 px phone.  Their labels are
 * what takes the room, so they are pinned here: five characters at most.
 */
class SpeedOptionsTest {

    @Test
    fun `labels stay short and readable`() {
        assertEquals(listOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x"), SpeedOptions.speeds.map(SpeedOptions::label))
        assertTrue(SpeedOptions.speeds.map(SpeedOptions::label).all { it.length <= 5 })
    }

    @Test
    fun `five chips and their spacing fit a 360 dp wide row`() {
        // FilterChip-like minimum width in this layout: label + 14 dp padding.
        val chipWidthDp = SpeedOptions.speeds.sumOf { 7 * 2 + SpeedOptions.label(it).length * 7 }
        val spacingDp = (SpeedOptions.speeds.size - 1) * 4
        val labelDp = 24
        val measureDp = 46
        assertTrue(
            "chips ${chipWidthDp}dp + spacing ${spacingDp}dp overflow 360dp",
            chipWidthDp + spacingDp + labelDp + measureDp <= 360,
        )
    }
}
