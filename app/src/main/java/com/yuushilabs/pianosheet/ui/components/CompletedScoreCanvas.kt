package com.yuushilabs.pianosheet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yuushilabs.pianosheet.ui.theme.ScorePalette
import com.yuushilabs.pianosheet.ui.theme.rememberScorePalette
import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.model.StaffNotation
import com.yuushilabs.pianosheet.domain.score.Accidental
import com.yuushilabs.pianosheet.domain.score.KeySignature
import com.yuushilabs.pianosheet.domain.score.LayoutMetrics
import com.yuushilabs.pianosheet.domain.score.MeasureBox
import com.yuushilabs.pianosheet.domain.score.NoteValue
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.ScoreLayoutEngine
import com.yuushilabs.pianosheet.domain.score.ScoreLayoutResult
import com.yuushilabs.pianosheet.domain.score.ScoreNote
import com.yuushilabs.pianosheet.domain.score.ScoreRest
import com.yuushilabs.pianosheet.domain.score.SystemBox
import com.yuushilabs.pianosheet.domain.score.TimeSignature
import kotlin.math.max

/**
 * SCR-004: the finished score (spec §3.6.2).
 *
 * A real grand staff, wrapped into systems at the page width: five lines per
 * staff, brace, treble/bass clefs, key signature, time signature, bar lines,
 * measure numbers, noteheads with stems/flags for whole … sixteenth notes,
 * dots, ledger lines, accidentals and rests.  The measure being played is
 * tinted and its sounding notes are drawn in the accent colour.
 */
@Composable
fun CompletedScoreCanvas(
    score: Score,
    currentMeasureIndex: Int,
    activeNoteIds: Set<String>,
    modifier: Modifier = Modifier,
    playheadBeat: Double? = null,
) {
    val density = LocalDensity.current
    val palette = rememberScorePalette()
    val lineGap = with(density) { 9f.dp.toPx() }
    val halfGap = lineGap / 2f
    // The staff header (clef, key signature, time signature) is measured from the
    // key signature: with seven sharps it needs half a centimetre more room than an
    // empty one, and a fixed width is what used to push the 6/8 into the sharps.
    val keyFifths = score.keySignature.fifths
    val headerWidth = StaffHeader.widthPx(lineGap, keyFifths)
    val systemTopPad = with(density) { 16f.dp.toPx() }
    val staffHeight = 4 * lineGap
    val staffGap = 5.6f * lineGap
    val systemBottomPad = with(density) { 10f.dp.toPx() }
    val systemHeight = systemTopPad + staffHeight + staffGap + staffHeight + systemBottomPad

    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val horizontalPad = with(density) { 10f.dp.toPx() }
        val metrics = remember(widthPx, lineGap, keyFifths) {
            LayoutMetrics(
                systemWidth = max(120f, widthPx - headerWidth - horizontalPad * 2),
                minMeasureWidth = with(density) { 84f.dp.toPx() },
                maxMeasureWidth = with(density) { 190f.dp.toPx() },
                systemHeight = systemHeight,
                systemGap = with(density) { 6f.dp.toPx() },
            )
        }
        val layout = remember(score, metrics) {
            ScoreLayoutEngine.layout(score.measures, metrics)
        }
        val totalHeight = with(density) { (layout.totalHeight + 8f).toDp() }

        Column(modifier = Modifier.verticalScroll(scrollState)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight),
            ) {
                // Every colour comes from the palette, so the same drawing is legible
                // on the light and on the dark background (it used to be fixed black).
                val ink = palette.ink
                val staff = palette.staff
                val accent = palette.accent
                val highlight = palette.highlight
                val stroke = 1.05f.dp.toPx()
                val thickStroke = 1.7f.dp.toPx()

                val textPaint = android.graphics.Paint().apply {
                    color = palette.ink.toArgb()
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                }
                val labelPaint = android.graphics.Paint().apply {
                    color = palette.label.toArgb()
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 8f.dp.toPx()
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                }

                fun drawText(text: String, x: Float, y: Float, sizePx: Float, paint: android.graphics.Paint, centered: Boolean = true) {
                    paint.textSize = sizePx
                    paint.textAlign = if (centered) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
                    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
                }

                if (layout.systems.isEmpty()) {
                    drawText("音符がありません", size.width / 2f, 80f, 12f.dp.toPx(), labelPaint)
                    return@Canvas
                }

                for (system in layout.systems) {
                    val trebleTop = system.top + systemTopPad
                    val bassTop = trebleTop + staffHeight + staffGap
                    val trebleBottom = trebleTop + staffHeight
                    val bassBottom = bassTop + staffHeight
                    val left = horizontalPad
                    val right = left + headerWidth + metrics.systemWidth

                    fun lineY(top: Float, index: Int): Float = top + index * lineGap

                    // Current measure tint.
                    if (currentMeasureIndex >= 0) {
                        val box = system.measures.firstOrNull { it.measure.index == currentMeasureIndex }
                        if (box != null) {
                            drawRect(
                                color = highlight,
                                topLeft = Offset(left + headerWidth + box.x, trebleTop - 6f),
                                size = Size(box.width, bassBottom - trebleTop + 12f),
                            )
                        }
                    }

                    // Staff lines.
                    for (staffIndex in 0..1) {
                        val top = if (staffIndex == 0) trebleTop else bassTop
                        for (index in 0..4) {
                            drawLine(
                                color = staff,
                                start = Offset(left, lineY(top, index)),
                                end = Offset(right, lineY(top, index)),
                                strokeWidth = stroke,
                            )
                        }
                    }

                    // Brace.
                    val brace = Path().apply {
                        moveTo(left + 2f, trebleTop)
                        cubicTo(
                            left + 14f, trebleTop + 26f,
                            left - 6f, (trebleTop + bassBottom) / 2 - 24f,
                            left + 12f, (trebleTop + bassBottom) / 2,
                        )
                        cubicTo(
                            left - 6f, (trebleTop + bassBottom) / 2 + 24f,
                            left + 14f, bassBottom - 26f,
                            left + 2f, bassBottom,
                        )
                    }
                    drawPath(brace, color = staff, style = Stroke(width = thickStroke))

                    // Clefs.
                    val clefX = left + StaffHeader.CLEF_CENTRE * lineGap
                    drawText(
                        "\uD834\uDD1E",
                        clefX,
                        lineY(trebleTop, 1) + lineGap * 1.25f,
                        4.4f * lineGap,
                        textPaint,
                    )
                    drawText(
                        "\uD834\uDD22",
                        clefX,
                        lineY(bassTop, 1) + lineGap * 0.95f,
                        4.4f * lineGap,
                        textPaint,
                    )

                    // Key signature.
                    drawKeySignature(
                        key = score.keySignature,
                        x = left + StaffHeader.KEY_START * lineGap,
                        trebleTop = trebleTop,
                        bassTop = bassTop,
                        lineGap = lineGap,
                        halfGap = halfGap,
                        color = ink,
                    )

                    // Time signature.
                    drawTimeSignature(
                        timeSignature = score.timeSignature,
                        x = left + StaffHeader.timeSignatureCentre(lineGap, keyFifths),
                        trebleTop = trebleTop,
                        bassTop = bassTop,
                        lineGap = lineGap,
                        paint = textPaint,
                        color = ink,
                    )

                    // Measure numbers and bar lines.
                    for ((position, box) in system.measures.withIndex()) {
                        val leftBar = if (position == 0) left + headerWidth else left + headerWidth + box.x
                        val rightBar = left + headerWidth + box.endX
                        drawLine(
                            color = staff,
                            start = Offset(leftBar, trebleTop),
                            end = Offset(leftBar, trebleBottom),
                            strokeWidth = if (position == 0) thickStroke else stroke,
                        )
                        drawLine(
                            color = staff,
                            start = Offset(leftBar, bassTop),
                            end = Offset(leftBar, bassBottom),
                            strokeWidth = if (position == 0) thickStroke else stroke,
                        )
                        if (position == system.measures.lastIndex) {
                            drawLine(
                                color = staff,
                                start = Offset(rightBar, trebleTop),
                                end = Offset(rightBar, trebleBottom),
                                strokeWidth = stroke,
                            )
                            drawLine(
                                color = staff,
                                start = Offset(rightBar, bassTop),
                                end = Offset(rightBar, bassBottom),
                                strokeWidth = stroke,
                            )
                        }
                        drawText(
                            "${box.measure.index + 1}",
                            left + headerWidth + box.x + 5f,
                            trebleTop - 7f,
                            7.5f.dp.toPx(),
                            labelPaint,
                            centered = false,
                        )
                    }

                    // Notes and rests.
                    val systemNotes = score.notes.filter { note ->
                        system.measures.any { box -> note.measureIndex == box.measure.index }
                    }
                    for (note in systemNotes) {
                        val box = system.measures.firstOrNull { it.measure.index == note.measureIndex } ?: continue
                        val x = left + headerWidth + box.xForBeat(note.startBeat)
                        drawNote(
                            note = note,
                            x = x,
                            trebleTop = trebleTop,
                            bassTop = bassTop,
                            lineGap = lineGap,
                            halfGap = halfGap,
                            stroke = stroke,
                            ink = if (activeNoteIds.contains(note.id)) accent else ink,
                            staffColor = staff,
                        )
                    }
                    for (rest in score.rests) {
                        val box = system.measures.firstOrNull { it.measure.index == rest.measureIndex } ?: continue
                        val x = left + headerWidth + box.xForBeat(rest.startBeat)
                        drawRest(
                            rest = rest,
                            x = x,
                            trebleTop = trebleTop,
                            bassTop = bassTop,
                            lineGap = lineGap,
                            halfGap = halfGap,
                            ink = palette.rest,
                            stroke = stroke,
                        )
                    }

                    // Playhead.
                    if (playheadBeat != null) {
                        val box = system.measures.firstOrNull { systemBox ->
                            playheadBeat >= systemBox.measure.startBeat - 1e-6 &&
                                playheadBeat < systemBox.measure.endBeat + 1e-6
                        }
                        if (box != null) {
                            val x = left + headerWidth + box.xForBeat(playheadBeat)
                            drawLine(
                                color = accent,
                                start = Offset(x, trebleTop - 10f),
                                end = Offset(x, bassBottom + 8f),
                                strokeWidth = 1.6f.dp.toPx(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private object StaffHeader {
    /** Clef centre, in staff spaces (one space = one line gap). */
    const val CLEF_CENTRE = 1.22f
    /** Half the drawn width of the clef glyph; its ink reaches this far right. */
    const val CLEF_HALF_WIDTH = 2.4f
    const val ACCIDENTAL_ADVANCE = 0.78f
    /** Half the width of one accidental; a flat reaches further right than a sharp. */
    const val ACCIDENTAL_HALF_WIDTH = 0.58f
    /** Centre of the first accidental: just clear of the clef's right edge. */
    const val KEY_START = CLEF_CENTRE + CLEF_HALF_WIDTH + ACCIDENTAL_HALF_WIDTH
    const val KEY_TO_TIME_GAP = 0.55f
    /** Half the width of the time signature (two digits). */
    const val TIME_HALF_WIDTH = 1.1f
    const val RIGHT_PAD = 0.35f

    /** Where the key signature ends, in px. */
    fun keySignatureEnd(lineGap: Float, fifths: Int): Float {
        val clefEnd = (CLEF_CENTRE + CLEF_HALF_WIDTH) * lineGap
        val count = kotlin.math.abs(fifths)
        if (count == 0) return clefEnd
        val keyEnd = (KEY_START + (count - 1) * ACCIDENTAL_ADVANCE + ACCIDENTAL_HALF_WIDTH) * lineGap
        return max(clefEnd, keyEnd)
    }

    fun timeSignatureCentre(lineGap: Float, fifths: Int): Float =
        keySignatureEnd(lineGap, fifths) + (KEY_TO_TIME_GAP + TIME_HALF_WIDTH) * lineGap

    /** Width of the whole header for this key, so no accidental can reach the 6/8. */
    fun widthPx(lineGap: Float, fifths: Int): Float =
        timeSignatureCentre(lineGap, fifths) + (TIME_HALF_WIDTH + RIGHT_PAD) * lineGap
}

private fun DrawScope.drawKeySignature(
    key: KeySignature,
    x: Float,
    trebleTop: Float,
    bassTop: Float,
    lineGap: Float,
    halfGap: Float,
    color: Color,
) {
    if (key.fifths == 0) return
    val isSharp = key.fifths > 0
    val trebleSteps = if (isSharp) SHARP_STEPS_TREBLE else FLAT_STEPS_TREBLE
    val bassSteps = if (isSharp) SHARP_STEPS_BASS else FLAT_STEPS_BASS
    val count = kotlin.math.abs(key.fifths)
    val inkColor = color
    val stroke = lineGap * 0.11f
    for (index in 0 until count) {
        // Spacing and glyph width are shared with [StaffHeader], so the header width
        // always covers every accidental.
        val offset = index * StaffHeader.ACCIDENTAL_ADVANCE * lineGap
        val trebleY = trebleTop + 4 * lineGap - trebleSteps[index] * halfGap
        val bassY = bassTop + 4 * lineGap - bassSteps[index] * halfGap
        drawAccidental(
            kind = if (isSharp) Accidental.SHARP else Accidental.FLAT,
            x = x + offset,
            centerY = trebleY,
            size = lineGap * 1.35f,
            color = inkColor,
            stroke = stroke,
        )
        drawAccidental(
            kind = if (isSharp) Accidental.SHARP else Accidental.FLAT,
            x = x + offset,
            centerY = bassY,
            size = lineGap * 1.35f,
            color = inkColor,
            stroke = stroke,
        )
    }
}

/**
 * Sharps, flats and naturals drawn as strokes.
 *
 * A text glyph would depend on a music font being present; four short lines are
 * always legible and keep the engraving self-contained.
 */
private fun DrawScope.drawAccidental(
    kind: Accidental,
    x: Float,
    centerY: Float,
    size: Float,
    color: Color,
    stroke: Float,
) {
    val halfHeight = size / 2f
    when (kind) {
        Accidental.SHARP -> {
            drawLine(
                color = color,
                start = Offset(x - size * 0.16f, centerY - halfHeight),
                end = Offset(x - size * 0.16f, centerY + halfHeight),
                strokeWidth = stroke,
            )
            drawLine(
                color = color,
                start = Offset(x + size * 0.16f, centerY - halfHeight),
                end = Offset(x + size * 0.16f, centerY + halfHeight),
                strokeWidth = stroke,
            )
            for (direction in intArrayOf(-1, 1)) {
                val y = centerY + direction * size * 0.2f
                drawLine(
                    color = color,
                    start = Offset(x - size * 0.33f, y + size * 0.12f),
                    end = Offset(x + size * 0.33f, y - size * 0.12f),
                    strokeWidth = stroke * 1.2f,
                )
            }
        }

        Accidental.FLAT -> {
            drawLine(
                color = color,
                start = Offset(x - size * 0.05f, centerY - halfHeight),
                end = Offset(x - size * 0.05f, centerY + halfHeight * 0.55f),
                strokeWidth = stroke,
            )
            val loop = Path().apply {
                moveTo(x - size * 0.05f, centerY - size * 0.02f)
                cubicTo(
                    x + size * 0.42f, centerY - size * 0.16f,
                    x + size * 0.42f, centerY + size * 0.34f,
                    x - size * 0.05f, centerY + size * 0.42f,
                )
            }
            drawPath(loop, color = color, style = Stroke(width = stroke))
        }

        Accidental.NATURAL -> {
            for (direction in intArrayOf(-1, 1)) {
                val offsetX = direction * size * 0.14f
                val top = if (direction < 0) centerY - halfHeight else centerY - size * 0.25f
                val bottom = if (direction < 0) centerY + size * 0.25f else centerY + halfHeight
                drawLine(
                    color = color,
                    start = Offset(x + offsetX, top),
                    end = Offset(x + offsetX, bottom),
                    strokeWidth = stroke,
                )
            }
            drawLine(
                color = color,
                start = Offset(x - size * 0.22f, centerY - size * 0.1f),
                end = Offset(x + size * 0.22f, centerY - size * 0.16f),
                strokeWidth = stroke,
            )
            drawLine(
                color = color,
                start = Offset(x - size * 0.22f, centerY + size * 0.14f),
                end = Offset(x + size * 0.22f, centerY + size * 0.08f),
                strokeWidth = stroke,
            )
        }
    }
}

private fun DrawScope.drawTimeSignature(
    timeSignature: TimeSignature,
    x: Float,
    trebleTop: Float,
    bassTop: Float,
    lineGap: Float,
    paint: android.graphics.Paint,
    color: Color,
) {
    paint.color = color.toArgb()
    paint.typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.textSize = lineGap * 2.5f
    drawContext.canvas.nativeCanvas.drawText(
        timeSignature.numerator.toString(),
        x,
        trebleTop + lineGap * 1.9f,
        paint,
    )
    drawContext.canvas.nativeCanvas.drawText(
        timeSignature.denominator.toString(),
        x,
        trebleTop + lineGap * 3.9f,
        paint,
    )
    drawContext.canvas.nativeCanvas.drawText(
        timeSignature.numerator.toString(),
        x,
        bassTop + lineGap * 1.9f,
        paint,
    )
    drawContext.canvas.nativeCanvas.drawText(
        timeSignature.denominator.toString(),
        x,
        bassTop + lineGap * 3.9f,
        paint,
    )
    paint.typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
}

private fun DrawScope.drawNote(
    note: ScoreNote,
    x: Float,
    trebleTop: Float,
    bassTop: Float,
    lineGap: Float,
    halfGap: Float,
    stroke: Float,
    ink: Color,
    staffColor: Color = ink,
) {
    val staffTop = if (note.staff == Staff.TREBLE) trebleTop else bassTop
    val bottomLine = staffTop + 4 * lineGap
    val diatonic = note.octave * 7 + note.letter
    val step = diatonic - (if (note.staff == Staff.TREBLE) StaffNotation.trebleBottomDiatonic else StaffNotation.bassBottomDiatonic)
    val centerY = bottomLine - step * halfGap

    for (ledger in StaffNotation.ledgerSteps(step)) {
        drawLine(
            color = staffColor,
            start = Offset(x - lineGap * 0.95f, bottomLine - ledger * halfGap),
            end = Offset(x + lineGap * 0.95f, bottomLine - ledger * halfGap),
            strokeWidth = stroke,
        )
    }

    val headWidth = lineGap * 1.28f
    val headHeight = lineGap * 0.92f
    val hollow = note.noteValue == NoteValue.WHOLE || note.noteValue == NoteValue.HALF
    rotate(degrees = -16f, pivot = Offset(x, centerY)) {
        drawOval(
            color = if (hollow) Color.Transparent else ink,
            topLeft = Offset(x - headWidth / 2f, centerY - headHeight / 2f),
            size = Size(headWidth, headHeight),
        )
        drawOval(
            color = ink,
            topLeft = Offset(x - headWidth / 2f, centerY - headHeight / 2f),
            size = Size(headWidth, headHeight),
            style = Stroke(width = stroke * 1.35f),
        )
    }

    if (note.accidental != null) {
        drawAccidental(
            kind = note.accidental,
            x = x - headWidth * 1.15f,
            centerY = centerY,
            size = lineGap * 1.4f,
            color = ink,
            stroke = stroke * 1.1f,
        )
    }

    if (note.dotted) {
        drawCircle(color = ink, radius = lineGap * 0.16f, center = Offset(x + headWidth * 0.85f, centerY))
    }

    if (note.noteValue == NoteValue.WHOLE) return

    val stemUp = step <= 4
    val stemLength = 3.4f * lineGap
    val stemX = if (stemUp) x + headWidth / 2f - stroke * 0.4f else x - headWidth / 2f + stroke * 0.4f
    val stemStart = if (stemUp) centerY - lineGap * 0.12f else centerY + lineGap * 0.12f
    val stemEnd = if (stemUp) centerY - stemLength else centerY + stemLength
    drawLine(
        color = ink,
        start = Offset(stemX, stemStart),
        end = Offset(stemX, stemEnd),
        strokeWidth = stroke * 1.6f,
    )

    val flags = when (note.noteValue) {
        NoteValue.EIGHTH -> 1
        NoteValue.SIXTEENTH -> 2
        else -> 0
    }
    for (flag in 0 until flags) {
        val flagTop = if (stemUp) stemEnd + flag * lineGap * 0.9f else stemEnd - flag * lineGap * 0.9f
        val path = Path().apply {
            moveTo(stemX, flagTop)
            cubicTo(
                stemX + lineGap * 1.5f, flagTop + if (stemUp) lineGap * 0.9f else -lineGap * 0.9f,
                stemX + lineGap * 1.5f, flagTop + if (stemUp) lineGap * 1.9f else -lineGap * 1.9f,
                stemX + lineGap * 0.4f, flagTop + if (stemUp) lineGap * 2.2f else -lineGap * 2.2f,
            )
        }
        drawPath(path, color = ink, style = Stroke(width = stroke * 1.5f))
    }
}

private fun DrawScope.drawRest(
    rest: ScoreRest,
    x: Float,
    trebleTop: Float,
    bassTop: Float,
    lineGap: Float,
    halfGap: Float,
    ink: Color,
    stroke: Float,
) {
    val staffTop = if (rest.staff == Staff.TREBLE) trebleTop else bassTop
    val thirdLine = staffTop + 2 * lineGap
    when (rest.noteValue) {
        NoteValue.WHOLE -> drawRect(
            color = ink,
            topLeft = Offset(x - lineGap * 0.62f, staffTop + lineGap - lineGap * 0.55f),
            size = Size(lineGap * 1.24f, lineGap * 0.55f),
        )
        NoteValue.HALF -> drawRect(
            color = ink,
            topLeft = Offset(x - lineGap * 0.62f, thirdLine),
            size = Size(lineGap * 1.24f, lineGap * 0.55f),
        )
        NoteValue.QUARTER -> {
            val path = Path().apply {
                moveTo(x - lineGap * 0.25f, staffTop + lineGap * 0.9f)
                lineTo(x + lineGap * 0.35f, staffTop + lineGap * 1.8f)
                lineTo(x - lineGap * 0.2f, staffTop + lineGap * 2.3f)
                lineTo(x + lineGap * 0.4f, staffTop + lineGap * 3.0f)
            }
            drawPath(path, color = ink, style = Stroke(width = stroke * 1.5f))
        }
        NoteValue.EIGHTH, NoteValue.SIXTEENTH -> {
            val count = if (rest.noteValue == NoteValue.EIGHTH) 1 else 2
            for (i in 0 until count) {
                val y = staffTop + lineGap * (1.4f + i * 1.05f)
                drawCircle(color = ink, radius = lineGap * 0.2f, center = Offset(x + lineGap * 0.2f, y))
                val path = Path().apply {
                    moveTo(x + lineGap * 0.05f, y + lineGap * 0.1f)
                    cubicTo(
                        x - lineGap * 0.7f, y - lineGap * 0.35f,
                        x - lineGap * 0.7f, y - lineGap * 0.7f,
                        x - lineGap * 0.2f, y - lineGap * 0.85f,
                    )
                }
                drawPath(path, color = ink, style = Stroke(width = stroke * 1.4f))
            }
        }
    }
    if (rest.dotted) {
        drawCircle(color = ink, radius = lineGap * 0.16f, center = Offset(x + lineGap * 0.85f, thirdLine - halfGap))
    }
}

// Written pitch classes of the signatures, from top to bottom on each staff.
private val SHARP_STEPS_TREBLE = intArrayOf(8, 5, 9, 6, 10, 7, 11)
private val SHARP_STEPS_BASS = intArrayOf(6, 3, 7, 4, 8, 5, 9)
private val FLAT_STEPS_TREBLE = intArrayOf(4, 7, 3, 6, 2, 5, 1)
private val FLAT_STEPS_BASS = intArrayOf(2, 5, 1, 4, 0, 3, -1)

@Suppress("unused")
private fun SystemBox.containsBeat(beat: Double): Boolean =
    beat >= measures.first().measure.startBeat - 1e-6 && beat < measures.last().measure.endBeat + 1e-6

@Suppress("unused")
private fun MeasureBox.beatSpan(): Double = measure.beats
