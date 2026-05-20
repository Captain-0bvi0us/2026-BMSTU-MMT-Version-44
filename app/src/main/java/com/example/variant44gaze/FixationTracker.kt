package com.example.variant44gaze

import android.graphics.PointF
import kotlin.math.hypot

data class FixationState(
    val isFixating: Boolean,
    val fixationCount: Int,
    val fixationPoints: List<PointF>
)

class FixationTracker(
    private val dwellMs: Long = 350L,
    private val radiusPx: Float = 70f,
    private val mergeRadiusPx: Float = 45f
) {
    private var stablePoint: PointF? = null
    private var stableStartMs: Long = 0L
    private var fixationCaptured = false
    private val points = mutableListOf<PointF>()

    fun update(currentPoint: PointF, nowMs: Long): FixationState {
        val anchor = stablePoint
        if (anchor == null) {
            stablePoint = PointF(currentPoint.x, currentPoint.y)
            stableStartMs = nowMs
            fixationCaptured = false
            return FixationState(isFixating = false, fixationCount = points.size, fixationPoints = points.toList())
        }

        val dist = hypot(currentPoint.x - anchor.x, currentPoint.y - anchor.y)
        if (dist > radiusPx) {
            stablePoint = PointF(currentPoint.x, currentPoint.y)
            stableStartMs = nowMs
            fixationCaptured = false
            return FixationState(isFixating = false, fixationCount = points.size, fixationPoints = points.toList())
        }

        val isFixating = nowMs - stableStartMs >= dwellMs
        if (isFixating && !fixationCaptured) {
            fixationCaptured = true
            addFixationPoint(anchor)
        }

        return FixationState(
            isFixating = isFixating,
            fixationCount = points.size,
            fixationPoints = points.toList()
        )
    }

    private fun addFixationPoint(anchor: PointF) {
        val existing = points.indexOfFirst { p -> hypot(p.x - anchor.x, p.y - anchor.y) <= mergeRadiusPx }
        if (existing >= 0) {
            points[existing] = PointF(anchor.x, anchor.y)
        } else {
            points.add(PointF(anchor.x, anchor.y))
            if (points.size > 30) points.removeAt(0)
        }
    }

    fun resetTracking() {
        stablePoint = null
        stableStartMs = 0L
        fixationCaptured = false
    }

    fun resetAll() {
        resetTracking()
        points.clear()
    }
}
