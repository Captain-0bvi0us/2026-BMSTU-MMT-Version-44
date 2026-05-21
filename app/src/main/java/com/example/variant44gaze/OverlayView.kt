package com.example.variant44gaze

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt

/**
 * Главный визуализатор:
 *   - рамка лица (по координатам изображения камеры),
 *   - точки глаз и носа,
 *   - текущая точка взгляда на ЭКРАНЕ (с прицелом),
 *   - траектория взгляда (последние N точек),
 *   - все накопленные точки фиксации с номером и длительностью,
 *   - сетка экрана (по желанию).
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#7FDD3CFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val leftIrisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFEB3B".toColorInt()
        style = Paint.Style.FILL
    }

    private val rightIrisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#00E676".toColorInt()
        style = Paint.Style.FILL
    }

    private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FF7043".toColorInt()
        style = Paint.Style.FILL
    }

    private val gazePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#00BCD4".toColorInt()
        style = Paint.Style.FILL
    }

    private val gazeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val gazeTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#7F00BCD4".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fixationFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CCFF2196".toColorInt()
        style = Paint.Style.FILL
    }

    private val fixationRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFFF2196".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val fixationLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#80FFFFFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val fixationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val fixationActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFFF2196".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#33FFFFFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var frameResult: GazeFrameResult? = null
    private var gazeScreenPoint: PointF? = null
    private var fixations: List<Fixation> = emptyList()
    private var gazeTrail: List<PointF> = emptyList()
    private var isFixating: Boolean = false
    private var isFrontCamera: Boolean = true
    private var showFaceLandmarks: Boolean = true
    private var showGrid: Boolean = false
    private var showFixations: Boolean = true
    private var previewBounds: RectF? = null

    fun update(
        frameResult: GazeFrameResult?,
        gazeScreenPoint: PointF?,
        fixations: List<Fixation>,
        gazeTrail: List<PointF>,
        isFixating: Boolean,
        isFrontCamera: Boolean,
        showFaceLandmarks: Boolean,
        showGrid: Boolean,
        showFixations: Boolean,
        previewBounds: RectF?
    ) {
        this.frameResult = frameResult
        this.gazeScreenPoint = gazeScreenPoint
        this.fixations = fixations
        this.gazeTrail = gazeTrail
        this.isFixating = isFixating
        this.isFrontCamera = isFrontCamera
        this.showFaceLandmarks = showFaceLandmarks
        this.showGrid = showGrid
        this.showFixations = showFixations
        this.previewBounds = previewBounds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (showGrid) drawGrid(canvas)

        if (showFaceLandmarks) {
            frameResult?.let { drawFaceLandmarks(canvas, it) }
        }

        if (showFixations && fixations.isNotEmpty()) {
            drawFixationLinks(canvas, fixations)
            drawFixationDots(canvas, fixations)
        }

        if (gazeTrail.size > 1) {
            val path = Path()
            path.moveTo(gazeTrail.first().x, gazeTrail.first().y)
            for (i in 1 until gazeTrail.size) {
                path.lineTo(gazeTrail[i].x, gazeTrail[i].y)
            }
            canvas.drawPath(path, gazeTrailPaint)
        }

        gazeScreenPoint?.let { p ->
            canvas.drawCircle(p.x, p.y, 14f, gazePaint)
            canvas.drawCircle(p.x, p.y, 18f, gazeStrokePaint)
            drawCrosshair(canvas, p.x, p.y)
            if (isFixating) canvas.drawCircle(p.x, p.y, 36f, fixationActivePaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..3) {
            val x = w * i / 4f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }

    private fun drawFaceLandmarks(canvas: Canvas, result: GazeFrameResult) {
        val bounds = previewBounds ?: RectF(0f, 0f, width.toFloat(), height.toFloat())

        val box = RectF(
            mapX(result.boundingBox.left.toFloat(), result.imageWidth, bounds),
            mapY(result.boundingBox.top.toFloat(), result.imageHeight, bounds),
            mapX(result.boundingBox.right.toFloat(), result.imageWidth, bounds),
            mapY(result.boundingBox.bottom.toFloat(), result.imageHeight, bounds)
        )
        box.sort()
        canvas.drawRect(box, facePaint)

        val left = mapToView(result.leftIrisImagePoint, result.imageWidth, result.imageHeight, bounds)
        val right = mapToView(result.rightIrisImagePoint, result.imageWidth, result.imageHeight, bounds)
        val nose = mapToView(result.noseImagePoint, result.imageWidth, result.imageHeight, bounds)
        canvas.drawCircle(left.x, left.y, 7f, leftIrisPaint)
        canvas.drawCircle(right.x, right.y, 7f, rightIrisPaint)
        canvas.drawCircle(nose.x, nose.y, 5f, nosePaint)
    }

    private fun drawFixationLinks(canvas: Canvas, list: List<Fixation>) {
        if (list.size < 2) return
        for (i in 1 until list.size) {
            val a = list[i - 1].screenPoint
            val b = list[i].screenPoint
            canvas.drawLine(a.x, a.y, b.x, b.y, fixationLinkPaint)
        }
    }

    private fun drawFixationDots(canvas: Canvas, list: List<Fixation>) {
        val maxDur = list.maxOf { it.durationMs }.coerceAtLeast(1L)
        for (fix in list) {
            val cx = fix.screenPoint.x
            val cy = fix.screenPoint.y
            val ratio = fix.durationMs.toFloat() / maxDur
            val r = 14f + ratio * 30f
            canvas.drawCircle(cx, cy, r, fixationFillPaint)
            canvas.drawCircle(cx, cy, r + 4f, fixationRingPaint)
            canvas.drawText(fix.index.toString(), cx, cy + 9f, fixationTextPaint)
        }
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val size = 22f
        canvas.drawLine(cx - size, cy, cx + size, cy, crossPaint)
        canvas.drawLine(cx, cy - size, cx, cy + size, crossPaint)
    }

    private fun mapToView(point: PointF, imageWidth: Int, imageHeight: Int, bounds: RectF): PointF {
        return PointF(
            mapX(point.x, imageWidth, bounds),
            mapY(point.y, imageHeight, bounds)
        )
    }

    private fun mapX(x: Float, imageWidth: Int, bounds: RectF): Float {
        val scaled = bounds.left + x * bounds.width() / imageWidth.toFloat()
        return if (isFrontCamera) bounds.right - (scaled - bounds.left) else scaled
    }

    private fun mapY(y: Float, imageHeight: Int, bounds: RectF): Float {
        return bounds.top + y * bounds.height() / imageHeight.toFloat()
    }
}
