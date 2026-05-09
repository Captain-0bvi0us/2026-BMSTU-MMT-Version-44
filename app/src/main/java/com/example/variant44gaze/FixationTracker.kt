package com.example.variant44gaze

import android.graphics.PointF
import kotlin.math.hypot

data class FixationState(
    val isFixating: Boolean,
    val fixationCount: Int,
    val fixationPoints: List<PointF>
)

class FixationTracker(
    private val dwellMs: Long = 450L,
    private val radiusPx: Float = 55f
) {
    private var stablePoint: PointF? = null
    private var stableStartMs: Long = 0L
    private var lastEventMs: Long = 0L
    private val points = mutableListOf<PointF>()

    fun update(currentPoint: PointF, nowMs: Long): FixationState {
        val anchor = stablePoint
        if (anchor == null) {
            stablePoint = PointF(currentPoint.x, currentPoint.y)
            stableStartMs = nowMs
            return FixationState(isFixating = false, fixationCount = points.size, fixationPoints = points.toList())
        }

        val dist = hypot(currentPoint.x - anchor.x, currentPoint.y - anchor.y)
        if (dist > radiusPx) {
            stablePoint = PointF(currentPoint.x, currentPoint.y)
            stableStartMs = nowMs
            return FixationState(isFixating = false, fixationCount = points.size, fixationPoints = points.toList())
        }

        val isFixating = nowMs - stableStartMs >= dwellMs
        if (isFixating && nowMs - lastEventMs >= dwellMs) {
            lastEventMs = nowMs
            points.add(PointF(anchor.x, anchor.y))
            if (points.size > 30) points.removeAt(0)
        }

        return FixationState(
            isFixating = isFixating,
            fixationCount = points.size,
            fixationPoints = points.toList()
        )
    }

    fun reset() {
        stablePoint = null
        stableStartMs = 0L
        lastEventMs = 0L
        points.clear()
    }
}
