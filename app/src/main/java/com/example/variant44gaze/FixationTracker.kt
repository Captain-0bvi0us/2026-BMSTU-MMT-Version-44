package com.example.variant44gaze

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Детектор фиксаций по I-DT (dispersion-threshold) подходу.
 *
 * Алгоритм:
 *  1. Сэмплы взгляда буферизуются в скользящем окне.
 *  2. Если разброс (дисперсия) точек в окне меньше [radiusPx] и окно длиннее [dwellMs] —
 *     считаем что наступила фиксация.
 *  3. Пока пользователь смотрит в той же области, фиксация удлиняется в durationMs.
 *  4. Если разброс превышен — фиксация закрывается, начинается новая саккада.
 *
 * Координаты подаются в пикселях экрана.
 */
class FixationTracker(
    private val dwellMs: Long = 250L,
    private val radiusPx: Float = 60f,
    private val mergeRadiusPx: Float = 80f,
    private val maxStoredFixations: Int = 200,
    private val screenWidth: () -> Float,
    private val screenHeight: () -> Float
) {
    private data class Sample(val point: PointF, val timestampMs: Long)

    private val window = ArrayDeque<Sample>()
    private val fixations = mutableListOf<Fixation>()
    private var activeFixation: Fixation? = null
    private var fixationCounter = 0

    fun update(currentPoint: PointF, nowMs: Long): FixationState {
        window.addLast(Sample(PointF(currentPoint.x, currentPoint.y), nowMs))
        while (window.isNotEmpty() && nowMs - window.first().timestampMs > dwellMs * 4) {
            window.removeFirst()
        }

        val dwellWindow = window.filter { nowMs - it.timestampMs <= dwellMs }
        val dispersion = dispersionOf(dwellWindow.map { it.point })
        val centroidPoint = centroidOf(dwellWindow.map { it.point })

        val isStable = dwellWindow.size >= 3 && dispersion <= radiusPx
        val active = activeFixation

        if (isStable && centroidPoint != null) {
            if (active == null) {
                fixationCounter++
                val sw = screenWidth().coerceAtLeast(1f)
                val sh = screenHeight().coerceAtLeast(1f)
                val newFix = Fixation(
                    screenPoint = PointF(centroidPoint.x, centroidPoint.y),
                    normalizedPoint = PointF(centroidPoint.x / sw, centroidPoint.y / sh),
                    startTimeMs = dwellWindow.first().timestampMs,
                    durationMs = nowMs - dwellWindow.first().timestampMs,
                    sampleCount = dwellWindow.size,
                    index = fixationCounter
                )
                activeFixation = newFix
                fixations.add(newFix)
                if (fixations.size > maxStoredFixations) fixations.removeAt(0)
            } else {
                val sw = screenWidth().coerceAtLeast(1f)
                val sh = screenHeight().coerceAtLeast(1f)
                val updated = active.copy(
                    screenPoint = PointF(centroidPoint.x, centroidPoint.y),
                    normalizedPoint = PointF(centroidPoint.x / sw, centroidPoint.y / sh),
                    durationMs = nowMs - active.startTimeMs,
                    sampleCount = active.sampleCount + 1
                )
                activeFixation = updated
                if (fixations.isNotEmpty()) {
                    fixations[fixations.lastIndex] = updated
                }
            }
        } else if (active != null) {
            // Если центр последней фиксации недалеко от текущей —
            // объединяем, чтобы не плодить дубли из-за мелкого микро-саккадного дрожания.
            val dist = centroidPoint?.let { hypot(it.x - active.screenPoint.x, it.y - active.screenPoint.y) } ?: Float.MAX_VALUE
            if (dist > mergeRadiusPx) {
                activeFixation = null
            }
        }

        return FixationState(
            isFixating = activeFixation != null,
            currentDwellMs = activeFixation?.durationMs ?: 0L,
            fixationCount = fixationCounter,
            totalFixationMs = fixations.sumOf { it.durationMs },
            fixations = fixations.toList()
        )
    }

    fun snapshot(): List<Fixation> = fixations.toList()

    fun resetSamples() {
        window.clear()
        activeFixation = null
    }

    fun resetAll() {
        window.clear()
        fixations.clear()
        activeFixation = null
        fixationCounter = 0
    }

    private fun centroidOf(points: List<PointF>): PointF? {
        if (points.isEmpty()) return null
        var sx = 0f
        var sy = 0f
        for (p in points) {
            sx += p.x
            sy += p.y
        }
        return PointF(sx / points.size, sy / points.size)
    }

    /**
     * Разброс точек как максимальное удаление от центроиды.
     * Это устойчивее к выбросам, чем (maxX - minX) + (maxY - minY).
     */
    private fun dispersionOf(points: List<PointF>): Float {
        val centroid = centroidOf(points) ?: return Float.MAX_VALUE
        var maxDist = 0f
        for (p in points) {
            val d = hypot(p.x - centroid.x, p.y - centroid.y)
            if (d > maxDist) maxDist = d
        }
        return maxDist
    }
}
