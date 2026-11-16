package com.yuushilabs.pianosheet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.Pitch
import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.model.StaffNotation
import kotlinx.coroutines.delay

/**
 * The live grand staff (spec §3.3.1, §4.9).
 *
 * Notes are placed by onset time on a continuous, right-growing canvas; the view
 * follows the newest note.  During recording only pitch and onset are meaningful,
 * so noteheads are drawn as provisional (hollow outline + filled head) and no
 * rhythmic value is implied.
 */
@Composable
fun StaffCanvas(
    notes: List<NoteEvent>,
    modifier: Modifier = Modifier,
    pixelsPerSecond: Float = 54f,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val lastOnsetMs = notes.maxOfOrNull { it.onsetMs } ?: 0.0
    val contentWidthDp = 200f + (lastOnsetMs / 1000.0).toFloat() * pixelsPerSecond
    val widthDp = contentWidthDp.dp
    val heightDp = 236.dp

    LaunchedEffect(notes.size) {
        if (notes.isNotEmpty()) {
            // Let the wider canvas be laid out, then follow the newest note.
            delay(60)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val ink = Color(0xFF1C1B1F)
    val provisional = Color(0xFF6A4FB6)

    Box(modifier = modifier.fillMaxWidth().horizontalScroll(scrollState)) {
        Canvas(modifier = Modifier.width(widthDp).height(heightDp)) {
            val lineGap = with(density) { 10.5.dp.toPx() }
            val halfGap = lineGap / 2f
            val leftPad = with(density) { 58.dp.toPx() }
            val trebleTop = with(density) { 34.dp.toPx() }
            val bassTop = trebleTop + 4 * lineGap + with(density) { 44.dp.toPx() }
            val staffStroke = Stroke(width = with(density) { 1.1.dp.toPx() })

            fun lineY(top: Float, index: Int): Float = top + index * lineGap

            // Five lines per staff.
            for (staff in 0..1) {
                val top = if (staff == 0) trebleTop else bassTop
                for (i in 0..4) {
                    drawLine(
                        color = ink,
                        start = Offset(leftPad - 18f, lineY(top, i)),
                        end = Offset(size.width - 12f, lineY(top, i)),
                        strokeWidth = staffStroke.width,
                    )
                }
            }

            // Brace and clefs.
            val braceTop = trebleTop
            val braceBottom = bassTop + 4 * lineGap
            val brace = Path().apply {
                moveTo(leftPad - 22f, braceTop)
                cubicTo(leftPad - 34f, braceTop + 30f, leftPad - 6f, (braceTop + braceBottom) / 2 - 20f, leftPad - 26f, (braceTop + braceBottom) / 2)
                cubicTo(leftPad - 6f, (braceTop + braceBottom) / 2 + 20f, leftPad - 34f, braceBottom - 30f, leftPad - 22f, braceBottom)
            }
            drawPath(brace, color = ink, style = Stroke(width = with(density) { 1.6.dp.toPx() }))

            val clefPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(255, 28, 27, 31)
                textSize = 4.6f * lineGap
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                "\uD834\uDD1E",
                leftPad - 8f,
                lineY(trebleTop, 1) + lineGap * 1.2f,
                clefPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "\uD834\uDD22",
                leftPad - 8f,
                lineY(bassTop, 1) + lineGap * 0.9f,
                clefPaint,
            )

            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(150, 90, 90, 100)
                textSize = with(density) { 9.dp.toPx() }
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }

            notes.forEach { note ->
                val clef = note.staff ?: StaffNotation.clefFor(note.pitchMidi)
                val step = StaffNotation.stepOnStaff(note.pitchMidi, clef)
                val staffTop = if (clef == Staff.TREBLE) trebleTop else bassTop
                val bottomLine = staffTop + 4 * lineGap
                val centerY = bottomLine - step * halfGap
                val x = leftPad + (note.onsetMs / 1000.0).toFloat() * pixelsPerSecond

                // Ledger lines for notes outside the staff.
                for (ledger in StaffNotation.ledgerSteps(step)) {
                    val ledgerY = bottomLine - ledger * halfGap
                    drawLine(
                        color = ink,
                        start = Offset(x - lineGap * 0.85f, ledgerY),
                        end = Offset(x + lineGap * 0.85f, ledgerY),
                        strokeWidth = staffStroke.width,
                    )
                }

                val headWidth = lineGap * 1.15f
                val headHeight = lineGap * 0.82f
                rotate(degrees = -18f, pivot = Offset(x, centerY)) {
                    drawOval(
                        color = if (note.state.wireName == "confirmed") ink else provisional,
                        topLeft = Offset(x - headWidth / 2f, centerY - headHeight / 2f),
                        size = Size(headWidth, headHeight),
                    )
                    if (note.state.wireName != "confirmed") {
                        drawOval(
                            color = provisional,
                            topLeft = Offset(x - headWidth / 2f, centerY - headHeight / 2f),
                            size = Size(headWidth, headHeight),
                            style = Stroke(width = with(density) { 1.0.dp.toPx() }),
                        )
                    }
                }

                Pitch.provisionalAccidental(note.pitchMidi)?.let { accidental ->
                    drawContext.canvas.nativeCanvas.drawText(
                        accidental,
                        x - headWidth,
                        centerY + headHeight * 0.45f,
                        clefPaint.apply { textSize = lineGap * 1.4f; color = android.graphics.Color.argb(220, 106, 79, 182) },
                    )
                    clefPaint.textSize = 4.6f * lineGap
                    clefPaint.color = android.graphics.Color.argb(255, 28, 27, 31)
                }

                if (Pitch.isBlackKey(note.pitchMidi)) {
                    drawContext.canvas.nativeCanvas.drawText(
                        Pitch.midiToName(note.pitchMidi),
                        x,
                        bottomLine + lineGap * 3.0f,
                        labelPaint,
                    )
                }
            }

            if (notes.isEmpty()) {
                drawContext.canvas.nativeCanvas.drawText(
                    "録音を開始すると、ここに音符が並びます",
                    size.width / 2f,
                    (trebleTop + bassTop + 4 * lineGap) / 2f,
                    labelPaint.apply { textSize = with(density) { 12.dp.toPx() } },
                )
            }
        }
    }
}
