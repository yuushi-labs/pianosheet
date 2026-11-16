package com.yuushilabs.pianosheet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.yuushilabs.pianosheet.domain.live.LiveStaffLayoutEngine
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.Pitch
import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.model.StaffNotation

/**
 * The live grand staff (spec §3.3.1, §4.9).
 *
 * The clef/brace header is fixed on the left and only the staff area scrolls.
 * Horizontal positions come from [LiveStaffLayoutEngine], which enforces a
 * minimum spacing between attacks and computes an instant follow offset, so a
 * long take stays readable and the newest note is always inside the viewport.
 */
@Composable
fun StaffCanvas(
    notes: List<NoteEvent>,
    modifier: Modifier = Modifier,
    followNewest: Boolean = true,
    onFollowConsumed: () -> Unit = {},
) {
    val density = LocalDensity.current
    val headerWidth = 46.dp
    val staffHeight = 236.dp
    val lineGap = 10.5f.dp
    val minGap = with(density) { 15f.dp.toPx() }
    val leftPad = with(density) { 18f.dp.toPx() }
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(staffHeight),
    ) {
        val viewportPx = with(density) { (maxWidth - headerWidth).toPx().coerceAtLeast(1f) }
        val layout = remember(notes, viewportPx, minGap) {
            LiveStaffLayoutEngine.compute(
                notes = notes,
                viewportWidth = viewportPx,
                headerWidth = 0f,
                leftPad = leftPad,
                minGap = minGap,
            )
        }

        LaunchedEffect(notes.size, followNewest, layout.followOffset) {
            if (followNewest && notes.isNotEmpty()) {
                // Instant: an animation towards a moving target is what used to
                // leave the newest notes outside the screen.
                scrollState.scrollTo(layout.followOffset.toInt())
                onFollowConsumed()
            }
        }

        val ink = Color(0xFF1C1B1F)
        val provisional = Color(0xFF6A4FB6)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = headerWidth)
                .horizontalScroll(scrollState),
        ) {
            val contentWidth = with(density) { layout.contentWidth.toDp() }
            Canvas(
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxHeight(),
            ) {
                val gapPx = with(density) { lineGap.toPx() }
                val halfGap = gapPx / 2f
                val trebleTop = with(density) { 34.dp.toPx() }
                val bassTop = trebleTop + 4 * gapPx + with(density) { 44.dp.toPx() }
                val stroke = Stroke(width = with(density) { 1.1.dp.toPx() })

                fun lineY(top: Float, index: Int): Float = top + index * gapPx

                for (staff in 0..1) {
                    val top = if (staff == 0) trebleTop else bassTop
                    for (index in 0..4) {
                        drawLine(
                            color = ink,
                            start = Offset(0f, lineY(top, index)),
                            end = Offset(size.width, lineY(top, index)),
                            strokeWidth = stroke.width,
                        )
                    }
                }

                val clefPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 28, 27, 31)
                    textSize = 4.6f * gapPx
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                    isAntiAlias = true
                }
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(150, 90, 90, 100)
                    textSize = with(density) { 9.dp.toPx() }
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }

                notes.forEachIndexed { index, note ->
                    val clef = note.staff ?: StaffNotation.clefFor(note.pitchMidi)
                    val step = StaffNotation.stepOnStaff(note.pitchMidi, clef)
                    val staffTop = if (clef == Staff.TREBLE) trebleTop else bassTop
                    val bottomLine = staffTop + 4 * gapPx
                    val centerY = bottomLine - step * halfGap
                    val x = layout.xs.getOrElse(index) { leftPad }

                    for (ledger in StaffNotation.ledgerSteps(step)) {
                        val ledgerY = bottomLine - ledger * halfGap
                        drawLine(
                            color = ink,
                            start = Offset(x - gapPx * 0.85f, ledgerY),
                            end = Offset(x + gapPx * 0.85f, ledgerY),
                            strokeWidth = stroke.width,
                        )
                    }

                    val headWidth = gapPx * 1.15f
                    val headHeight = gapPx * 0.82f
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
                        clefPaint.textSize = gapPx * 1.4f
                        clefPaint.color = android.graphics.Color.argb(220, 106, 79, 182)
                        drawContext.canvas.nativeCanvas.drawText(
                            accidental,
                            x - headWidth,
                            centerY + headHeight * 0.45f,
                            clefPaint,
                        )
                        clefPaint.textSize = 4.6f * gapPx
                        clefPaint.color = android.graphics.Color.argb(255, 28, 27, 31)
                    }

                    if (Pitch.isBlackKey(note.pitchMidi)) {
                        drawContext.canvas.nativeCanvas.drawText(
                            Pitch.midiToName(note.pitchMidi),
                            x,
                            bottomLine + gapPx * 3.0f,
                            labelPaint,
                        )
                    }
                }

                if (notes.isEmpty()) {
                    labelPaint.textSize = with(density) { 12.dp.toPx() }
                    drawContext.canvas.nativeCanvas.drawText(
                        "録音を開始すると、ここに音符が並びます",
                        size.width / 2f,
                        (trebleTop + bassTop + 4 * gapPx) / 2f,
                        labelPaint,
                    )
                }
            }
        }

        // Fixed header on top of the scrolling staff: only this part never moves.
        Canvas(
            modifier = Modifier
                .width(headerWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val gapPx = with(density) { lineGap.toPx() }
            val trebleTop = with(density) { 34.dp.toPx() }
            val bassTop = trebleTop + 4 * gapPx + with(density) { 44.dp.toPx() }
            val stroke = Stroke(width = with(density) { 1.1.dp.toPx() })

            for (staff in 0..1) {
                val top = if (staff == 0) trebleTop else bassTop
                for (index in 0..4) {
                    drawLine(
                        color = ink,
                        start = Offset(size.width - 6f, top + index * gapPx),
                        end = Offset(size.width, top + index * gapPx),
                        strokeWidth = stroke.width,
                    )
                }
            }

            val brace = Path().apply {
                moveTo(4f, trebleTop)
                cubicTo(16f, trebleTop + 30f, -2f, (trebleTop + bassTop + 4 * gapPx) / 2 - 20f, 8f, (trebleTop + bassTop + 4 * gapPx) / 2)
                cubicTo(-2f, (trebleTop + bassTop + 4 * gapPx) / 2 + 20f, 16f, bassTop + 4 * gapPx - 30f, 4f, bassTop + 4 * gapPx)
            }
            drawPath(brace, color = ink, style = Stroke(width = with(density) { 1.6.dp.toPx() }))

            val clefPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(255, 28, 27, 31)
                textSize = 4.2f * gapPx
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                "\uD834\uDD1E",
                size.width - 18f,
                trebleTop + gapPx * 3.2f,
                clefPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "\uD834\uDD22",
                size.width - 18f,
                bassTop + gapPx * 2.9f,
                clefPaint,
            )
        }
    }
}
