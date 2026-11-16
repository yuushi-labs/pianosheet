package com.yuushilabs.pianosheet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yuushilabs.pianosheet.domain.model.Pitch

/**
 * The piano keyboard at the bottom of the live screen (spec §3.3.5, §4.10).
 *
 * Detected keys light up in purple while the note is sounding; the highlight
 * fades after a short hold so it does not flicker with the 250 ms analysis
 * steps.  Key names are drawn on the C keys so the colour is not the only cue.
 */
@Composable
fun PianoKeyboard(
    activePitches: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val whiteKeyCount = (Pitch.MIDI_LOW..Pitch.MIDI_HIGH).count { Pitch.isWhiteKey(it) }
    val whiteKeyWidth = 17.dp
    val widthDp = whiteKeyWidth * whiteKeyCount
    val highlight = Color(0xFF7C5CD6)
    val whiteKey = Color(0xFFFFFDF7)
    val blackKey = Color(0xFF23212A)
    val keyBorder = Color(0xFF5A5663)

    Box(modifier = modifier.height(112.dp).horizontalScroll(scrollState)) {
        Canvas(
            modifier = Modifier
                .width(widthDp)
                .height(112.dp),
        ) {
        val keyWidth = whiteKeyWidth.toPx()
        val blackWidth = keyWidth * 0.62f
        val blackHeight = size.height * 0.62f
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(150, 60, 58, 70)
            textSize = with(density) { 8.dp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        val whiteLeft = HashMap<Int, Float>()
        var cursor = 0f
        for (midi in Pitch.MIDI_LOW..Pitch.MIDI_HIGH) {
            if (!Pitch.isWhiteKey(midi)) continue
            whiteLeft[midi] = cursor
            val active = activePitches.contains(midi)
            drawRect(
                color = if (active) highlight else whiteKey,
                topLeft = Offset(cursor, 0f),
                size = Size(keyWidth, size.height),
            )
            drawRect(
                color = keyBorder,
                topLeft = Offset(cursor, 0f),
                size = Size(keyWidth, size.height),
                style = Stroke(width = with(density) { 0.8.dp.toPx() }),
            )
            if (midi % 12 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    Pitch.midiToName(midi),
                    cursor + keyWidth / 2f,
                    size.height - with(density) { 4.dp.toPx() },
                    labelPaint,
                )
            }
            cursor += keyWidth
        }

        for (midi in Pitch.MIDI_LOW..Pitch.MIDI_HIGH) {
            if (!Pitch.isBlackKey(midi)) continue
            val leftWhite = whiteLeft[midi - 1] ?: continue
            val center = leftWhite + keyWidth
            val active = activePitches.contains(midi)
            drawRoundRect(
                color = if (active) highlight else blackKey,
                topLeft = Offset(center - blackWidth / 2f, 0f),
                size = Size(blackWidth, blackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            )
        }
        }
    }
}
