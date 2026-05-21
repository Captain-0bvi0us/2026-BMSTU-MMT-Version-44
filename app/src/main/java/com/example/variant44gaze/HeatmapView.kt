package com.example.variant44gaze

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Тепловая карта фиксаций. Каждая фиксация рисуется радиальным градиентом
 * с прозрачностью, пропорциональной её длительности. Чем дольше пользователь
 * смотрел на точку — тем ярче пятно.
 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var fixations: List<Fixation> = emptyList()
    private val tmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setFixations(list: List<Fixation>) {
        fixations = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fixations.isEmpty()) return
        val maxDuration = fixations.maxOf { it.durationMs }.coerceAtLeast(1L)

        for (fix in fixations) {
            val cx = fix.screenPoint.x
            val cy = fix.screenPoint.y
            val ratio = (fix.durationMs.toFloat() / maxDuration).coerceIn(0.15f, 1f)
            val radius = (90f + ratio * 110f)
            val alphaCenter = (110 + ratio * 130).toInt().coerceIn(60, 255)

            val colors = intArrayOf(
                Color.argb(alphaCenter, 255, 60, 0),
                Color.argb((alphaCenter * 0.65f).toInt(), 255, 180, 0),
                Color.argb((alphaCenter * 0.30f).toInt(), 0, 200, 255),
                Color.argb(0, 0, 0, 255)
            )
            val stops = floatArrayOf(0f, 0.45f, 0.8f, 1f)
            tmpPaint.shader = RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
            tmpPaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius, tmpPaint)
        }
        tmpPaint.shader = null
    }
}
