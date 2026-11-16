package com.yuushilabs.pianosheet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours the engraved score is drawn with (spec §3.3.1, §3.6.2).
 *
 * The staff used to be drawn with fixed black values, which made the whole sheet
 * disappear on the dark background (`#141218`).  Every colour now comes from the
 * Material colour scheme, so the same drawing code is legible in both themes:
 *
 *  - [ink]      noteheads, stems, beams, clefs, key/time signatures, accidentals
 *  - [staff]    staff lines, bar lines, ledger lines (onSurfaceVariant, dimmed)
 *  - [label]    measure numbers and note-name hints
 *  - [accent]   newest / playing note and the playhead
 *  - [highlight]tint behind the measure being played
 *  - [rest]     rests, slightly softer than the notes
 */
@Immutable
data class ScorePalette(
    val ink: Color,
    val staff: Color,
    val label: Color,
    val accent: Color,
    val highlight: Color,
    val rest: Color,
)

/** Builds the palette from plain colours, so it can be unit tested without a Composable. */
fun scorePalette(
    onSurface: Color,
    onSurfaceVariant: Color,
    primary: Color,
    background: Color,
): ScorePalette {
    val ink = ensureContrast(onSurface, background, MIN_INK_CONTRAST)
    val staff = ensureContrast(onSurfaceVariant.copy(alpha = STAFF_ALPHA), background, MIN_STAFF_CONTRAST)
    return ScorePalette(
        ink = ink,
        staff = staff,
        label = ensureContrast(onSurfaceVariant.copy(alpha = LABEL_ALPHA), background, MIN_STAFF_CONTRAST),
        accent = ensureContrast(primary, background, MIN_ACCENT_CONTRAST),
        highlight = primary.copy(alpha = HIGHLIGHT_ALPHA),
        rest = ensureContrast(onSurface.copy(alpha = REST_ALPHA), background, MIN_INK_CONTRAST * 0.8),
    )
}

@Composable
fun rememberScorePalette(): ScorePalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.background) {
        scorePalette(
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariant,
            primary = scheme.primary,
            background = scheme.background,
        )
    }
}

const val MIN_INK_CONTRAST = 7.0
const val MIN_STAFF_CONTRAST = 3.0
const val MIN_ACCENT_CONTRAST = 4.5
const val STAFF_ALPHA = 0.65f
const val LABEL_ALPHA = 0.80f
const val REST_ALPHA = 0.88f
const val HIGHLIGHT_ALPHA = 0.12f

/** Flattens [foreground] over [background] using the foreground's alpha. */
fun compositeOver(foreground: Color, background: Color): Color {
    val alpha = foreground.alpha
    return Color(
        red = foreground.red * alpha + background.red * (1f - alpha),
        green = foreground.green * alpha + background.green * (1f - alpha),
        blue = foreground.blue * alpha + background.blue * (1f - alpha),
        alpha = 1f,
    )
}

/** WCAG relative luminance of a colour. */
fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble().coerceIn(0.0, 1.0)
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** WCAG contrast ratio of [foreground] (alpha composited) against [background]. */
fun contrastRatio(foreground: Color, background: Color): Double {
    val fg = relativeLuminance(compositeOver(foreground, background))
    val bg = relativeLuminance(background)
    val lighter = max(fg, bg)
    val darker = min(fg, bg)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Raises a colour's contrast against [background] until it reaches [target] by
 * moving it towards white or black — whichever direction the scheme points at.
 * A theme with a low-contrast pair therefore still draws a readable staff.
 */
fun ensureContrast(color: Color, background: Color, target: Double): Color {
    if (contrastRatio(color, background) >= target) return color
    val towardsWhite = relativeLuminance(background) < 0.5
    var best = color
    var amount = 0.08f
    while (amount <= 1f) {
        val candidate = if (towardsWhite) {
            Color(
                red = color.red + (1f - color.red) * amount,
                green = color.green + (1f - color.green) * amount,
                blue = color.blue + (1f - color.blue) * amount,
                alpha = color.alpha,
            )
        } else {
            Color(
                red = color.red * (1f - amount),
                green = color.green * (1f - amount),
                blue = color.blue * (1f - amount),
                alpha = color.alpha,
            )
        }
        best = candidate
        if (contrastRatio(candidate, background) >= target) return candidate
        amount += 0.08f
    }
    return best
}
