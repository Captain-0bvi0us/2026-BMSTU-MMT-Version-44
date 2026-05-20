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

    private val leftEyeTrackPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val rightEyeTrackPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val nosePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val trailLeftPaint = Paint().apply {
        color = Color.argb(190, 255, 235, 59)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val trailRightPaint = Paint().apply {
        color = Color.argb(190, 76, 175, 80)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val trailGazePaint = Paint().apply {
        color = Color.argb(180, 0, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val crossPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var frameResult: GazeFrameResult? = null
    private var fixationPoints: List<PointF> = emptyList()
    private var leftEyeTrail: List<PointF> = emptyList()
    private var rightEyeTrail: List<PointF> = emptyList()
    private var gazeTrail: List<PointF> = emptyList()
    private var isFixating: Boolean = false
    private var isFrontCamera: Boolean = true

    fun update(
        frameResult: GazeFrameResult?,
        fixationPoints: List<PointF>,
        leftEyeTrail: List<PointF>,
        rightEyeTrail: List<PointF>,
        gazeTrail: List<PointF>,
        isFixating: Boolean,
        isFrontCamera: Boolean
    ) {
        this.frameResult = frameResult
        this.fixationPoints = fixationPoints
        this.leftEyeTrail = leftEyeTrail
        this.rightEyeTrail = rightEyeTrail
        this.gazeTrail = gazeTrail
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

        drawTrail(canvas, leftEyeTrail, result.imageWidth, result.imageHeight, trailLeftPaint)
        drawTrail(canvas, rightEyeTrail, result.imageWidth, result.imageHeight, trailRightPaint)
        drawTrail(canvas, gazeTrail, result.imageWidth, result.imageHeight, trailGazePaint)

        for ((index, p) in fixationPoints.withIndex()) {
            val mapped = mapToView(p, result.imageWidth, result.imageHeight)
            canvas.drawCircle(mapped.x, mapped.y, 9f, fixationPaint)
            canvas.drawCircle(mapped.x, mapped.y, 20f, fixationStrokePaint)
            if (index == fixationPoints.lastIndex) {
                canvas.drawCircle(mapped.x, mapped.y, 28f, fixationStrokePaint)
            }
        }

        val gaze = mapToView(result.gazePointImage, result.imageWidth, result.imageHeight)
        val leftEye = mapToView(result.leftEyeTrackPointImage, result.imageWidth, result.imageHeight)
        val rightEye = mapToView(result.rightEyeTrackPointImage, result.imageWidth, result.imageHeight)
        val nose = mapToView(result.nosePointImage, result.imageWidth, result.imageHeight)

        canvas.drawCircle(leftEye.x, leftEye.y, 10f, leftEyeTrackPaint)
        canvas.drawCircle(rightEye.x, rightEye.y, 10f, rightEyeTrackPaint)
        canvas.drawCircle(nose.x, nose.y, 8f, nosePaint)

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

    private fun drawTrail(
        canvas: Canvas,
        points: List<PointF>,
        imageWidth: Int,
        imageHeight: Int,
        paint: Paint
    ) {
        if (points.size < 2) return
        var prev = mapToView(points.first(), imageWidth, imageHeight)
        for (i in 1 until points.size) {
            val curr = mapToView(points[i], imageWidth, imageHeight)
            canvas.drawLine(prev.x, prev.y, curr.x, curr.y, paint)
            prev = curr
        }
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
