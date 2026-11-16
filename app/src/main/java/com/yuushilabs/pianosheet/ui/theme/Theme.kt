package com.yuushilabs.pianosheet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF6A4FB6)
private val PurpleLight = Color(0xFFD6C9F0)
private val Ink = Color(0xFF1C1B1F)

private val LightScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleLight,
    onPrimaryContainer = Color(0xFF26105C),
    secondary = Color(0xFF5F5A6E),
    tertiary = Color(0xFF9A5B8A),
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE8E1EC),
    onSurface = Ink,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC6B4F0),
    onPrimary = Color(0xFF2F1E64),
    primaryContainer = Color(0xFF463183),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFC9C2D6),
    tertiary = Color(0xFFE3B7D4),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
    surfaceVariant = Color(0xFF49454F),
    onSurface = Color(0xFFE7E1E9),
)

@Composable
fun PianoSheetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
