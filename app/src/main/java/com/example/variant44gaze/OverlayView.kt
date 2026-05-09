package com.example.variant44gaze

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val facePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val gazePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private val fixationPaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
    }

    private val fixationStrokePaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val crossPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var frameResult: GazeFrameResult? = null
    private var fixationPoints: List<PointF> = emptyList()
    private var isFixating: Boolean = false
    private var isFrontCamera: Boolean = true

    fun update(
        frameResult: GazeFrameResult?,
        fixationPoints: List<PointF>,
        isFixating: Boolean,
        isFrontCamera: Boolean
    ) {
        this.frameResult = frameResult
        this.fixationPoints = fixationPoints
        this.isFixating = isFixating
        this.isFrontCamera = isFrontCamera
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = frameResult ?: return

        val box = RectF(
            mapX(result.boundingBox.left.toFloat(), result.imageWidth),
            mapY(result.boundingBox.top.toFloat(), result.imageHeight),
            mapX(result.boundingBox.right.toFloat(), result.imageWidth),
            mapY(result.boundingBox.bottom.toFloat(), result.imageHeight)
        )
        box.sort()
        canvas.drawRect(box, facePaint)

        for ((index, p) in fixationPoints.withIndex()) {
            val mapped = mapToView(p, result.imageWidth, result.imageHeight)
            canvas.drawCircle(mapped.x, mapped.y, 9f, fixationPaint)
            canvas.drawCircle(mapped.x, mapped.y, 20f, fixationStrokePaint)
            if (index == fixationPoints.lastIndex) {
                canvas.drawCircle(mapped.x, mapped.y, 28f, fixationStrokePaint)
            }
        }

        val gaze = mapToView(result.gazePointImage, result.imageWidth, result.imageHeight)
        canvas.drawCircle(gaze.x, gaze.y, 12f, gazePaint)
        drawCrosshair(canvas, gaze.x, gaze.y)
        if (isFixating) {
            canvas.drawCircle(gaze.x, gaze.y, 35f, fixationStrokePaint)
        }
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val size = 22f
        canvas.drawLine(cx - size, cy, cx + size, cy, crossPaint)
        canvas.drawLine(cx, cy - size, cx, cy + size, crossPaint)
    }

    private fun mapToView(point: PointF, imageWidth: Int, imageHeight: Int): PointF {
        return PointF(
            mapX(point.x, imageWidth),
            mapY(point.y, imageHeight)
        )
    }

    private fun mapX(x: Float, imageWidth: Int): Float {
        val scaled = x * width / imageWidth.toFloat()
        return if (isFrontCamera) width - scaled else scaled
    }

    private fun mapY(y: Float, imageHeight: Int): Float = y * height / imageHeight.toFloat()
}
