package com.yuushilabs.pianosheet.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score disappeared in dark mode because every colour was fixed black.  These
 * tests pin the palette to the two Material schemes the app ships, so a later
 * "just use Color.Black here" cannot quietly come back.
 */
class ScorePaletteTest {

    // The values of ui/theme/Theme.kt (light).
    private val lightBackground = Color(0xFFFDFBFF)
    private val lightOnSurface = Color(0xFF1C1B1F)
    private val lightOnSurfaceVariant = Color(0xFF4A4656)
    private val lightPrimary = Color(0xFF6A4FB6)

    // The values of ui/theme/Theme.kt (dark, background #141218).
    private val darkBackground = Color(0xFF141218)
    private val darkOnSurface = Color(0xFFE7E1E9)
    private val darkOnSurfaceVariant = Color(0xFFCAC4D0)
    private val darkPrimary = Color(0xFFC6B4F0)

    private val light = scorePalette(lightOnSurface, lightOnSurfaceVariant, lightPrimary, lightBackground)
    private val dark = scorePalette(darkOnSurface, darkOnSurfaceVariant, darkPrimary, darkBackground)

    @Test
    fun `note ink is readable on both themes`() {
        assertTrue(
            "light ink contrast ${contrastRatio(light.ink, lightBackground)}",
            contrastRatio(light.ink, lightBackground) >= MIN_INK_CONTRAST,
        )
        assertTrue(
            "dark ink contrast ${contrastRatio(dark.ink, darkBackground)}",
            contrastRatio(dark.ink, darkBackground) >= MIN_INK_CONTRAST,
        )
    }

    @Test
    fun `staff lines stay visible in both themes`() {
        assertTrue(
            "light staff contrast ${contrastRatio(light.staff, lightBackground)}",
            contrastRatio(light.staff, lightBackground) >= MIN_STAFF_CONTRAST,
        )
        assertTrue(
            "dark staff contrast ${contrastRatio(dark.staff, darkBackground)}",
            contrastRatio(dark.staff, darkBackground) >= MIN_STAFF_CONTRAST,
        )
    }

    @Test
    fun `the playing colour is distinguishable from the paper`() {
        assertTrue(contrastRatio(light.accent, lightBackground) >= MIN_ACCENT_CONTRAST)
        assertTrue(contrastRatio(dark.accent, darkBackground) >= MIN_ACCENT_CONTRAST)
    }

    @Test
    fun `dark mode no longer draws dark ink`() {
        // The regression itself: on #141218 a fixed ink of #1B1A1D has ~1.02:1.
        val oldInk = Color(0xFF1B1A1D)
        assertTrue(contrastRatio(oldInk, darkBackground) < 1.2)
        assertTrue(contrastRatio(dark.ink, darkBackground) > 10.0)
    }

    @Test
    fun `a low contrast scheme is lifted to the target instead of staying dim`() {
        val dim = scorePalette(
            onSurface = Color(0xFF3A3A3A),
            onSurfaceVariant = Color(0xFF3A3A3A),
            primary = Color(0xFF3A3A3A),
            background = Color(0xFF121212),
        )
        assertTrue(contrastRatio(dim.ink, Color(0xFF121212)) >= MIN_INK_CONTRAST)
    }
}
