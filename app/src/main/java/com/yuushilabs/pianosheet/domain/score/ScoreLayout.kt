package com.yuushilabs.pianosheet.domain.score

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class LayoutMetrics(
    /** Horizontal space available to the measures of one system. */
    val systemWidth: Float,
    val minMeasureWidth: Float,
    val maxMeasureWidth: Float,
    val systemHeight: Float,
    val systemGap: Float = 0f,
) {
    init {
        require(systemWidth > 0f) { "systemWidth must be positive" }
        require(minMeasureWidth > 0f) { "minMeasureWidth must be positive" }
    }
}

data class MeasureBox(val measure: ScoreMeasure, val x: Float, val width: Float) {
    val endX: Float get() = x + width

    /** Horizontal position of a beat inside this measure. */
    fun xForBeat(beat: Double): Float {
        val fraction = ((beat - measure.startBeat) / measure.beats).coerceIn(0.0, 1.0)
        return x + (fraction * width).toFloat()
    }
}

data class SystemBox(val index: Int, val top: Float, val height: Float, val measures: List<MeasureBox>) {
    val bottom: Float get() = top + height
}

data class ScoreLayoutResult(
    val systems: List<SystemBox>,
    val totalHeight: Float,
) {
    fun systemForMeasure(measureIndex: Int): SystemBox? =
        systems.firstOrNull { system -> system.measures.any { it.measure.index == measureIndex } }

    fun boxForMeasure(measureIndex: Int): MeasureBox? =
        systems.asSequence()
            .flatMap { it.measures.asSequence() }
            .firstOrNull { it.measure.index == measureIndex }

    fun xForBeat(measureIndex: Int, beat: Double): Float {
        val box = boxForMeasure(measureIndex) ?: return 0f
        return box.xForBeat(beat)
    }
}

/**
 * Lays a finished score out in systems that wrap at the page width (spec
 * §3.6.2 "複数段に折り返す通常の楽譜レイアウト").
 *
 * A measure's natural width is proportional to its length, bounded so a single
 * whole note does not produce an unreadably narrow bar and a 6/8 bar is not as
 * wide as a 4/4 one.  Systems are then justified to the full width except the
 * last one, which stays left aligned — the way engraved scores look.
 */
object ScoreLayoutEngine {

    fun layout(measures: List<ScoreMeasure>, metrics: LayoutMetrics): ScoreLayoutResult {
        if (measures.isEmpty()) return ScoreLayoutResult(emptyList(), 0f)

        val natural = measures.map { measure ->
            val proportional = (metrics.minMeasureWidth * (measure.beats / 4.0)).toFloat()
            val floor = metrics.minMeasureWidth * 0.7f
            min(metrics.maxMeasureWidth, max(floor, proportional))
        }

        val systems = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var used = 0f
        for (index in measures.indices) {
            val width = natural[index]
            if (current.isNotEmpty() && used + width > metrics.systemWidth + 1e-3f) {
                systems.add(current)
                current = ArrayList()
                used = 0f
            }
            current.add(index)
            used += width
        }
        if (current.isNotEmpty()) systems.add(current)

        val boxes = ArrayList<SystemBox>(systems.size)
        var y = 0f
        for ((systemIndex, indices) in systems.withIndex()) {
            val isLast = systemIndex == systems.lastIndex
            val naturalTotal = indices.sumOf { natural[it].toDouble() }.toFloat()
            val scale = if (isLast || naturalTotal <= 0f) 1f else metrics.systemWidth / naturalTotal
            var x = 0f
            val measureBoxes = ArrayList<MeasureBox>(indices.size)
            for (index in indices) {
                val width = if (isLast) natural[index] else natural[index] * scale
                measureBoxes.add(MeasureBox(measures[index], x, width))
                x += width
            }
            boxes.add(SystemBox(index = systemIndex, top = y, height = metrics.systemHeight, measures = measureBoxes))
            y += metrics.systemHeight + metrics.systemGap
        }
        val totalHeight = if (boxes.isEmpty()) 0f else boxes.last().bottom
        return ScoreLayoutResult(boxes, totalHeight)
    }

    /** Measures per system for a given width, useful for the canvas and the tests. */
    fun measuresPerSystem(measures: List<ScoreMeasure>, metrics: LayoutMetrics): List<Int> =
        layout(measures, metrics).systems.map { it.measures.size }

    fun measureCount(systems: Int, measures: Int): Int = ceil(measures.toDouble() / max(1, systems)).toInt()
}
