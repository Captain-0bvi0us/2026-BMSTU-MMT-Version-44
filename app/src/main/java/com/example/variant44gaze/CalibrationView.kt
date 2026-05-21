package com.example.variant44gaze

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt

/**
 * Полноэкранный оверлей калибровки. Показывает пульсирующую точку поочерёдно
 * в 9 позициях, пока [GazeCalibrator] собирает сэмплы.
 *
 * Жизненный цикл:
 *   1. [start] — задаём список целей и callback'и.
 *   2. На каждой позиции точка пульсирует [holdMs] миллисекунд.
 *      В этот момент система фиксирует сэмплы взгляда.
 *   3. По завершении вызывается onFinished.
 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFEB3B".toColorInt()
        style = Paint.Style.FILL
    }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val dotInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#B0E5FF".toColorInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val backgroundPaint = Paint().apply {
        color = "#CC000000".toColorInt()
        style = Paint.Style.FILL
    }

    private var targets: List<GazeCalibrator.CalibrationTarget> = emptyList()
    private var currentIndex: Int = -1
    private var pulse: Float = 0.5f
    private var animator: ValueAnimator? = null
    private var holdMs: Long = 1400L
    private var onSample: ((GazeCalibrator.CalibrationTarget) -> Unit)? = null
    private var onFinished: (() -> Unit)? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start(
        targets: List<GazeCalibrator.CalibrationTarget>,
        holdMs: Long,
        onSample: (GazeCalibrator.CalibrationTarget) -> Unit,
        onFinished: () -> Unit
    ) {
        cancel()
        this.targets = targets
        this.holdMs = holdMs
        this.onSample = onSample
        this.onFinished = onFinished
        visibility = VISIBLE
        currentIndex = 0
        runStep()
    }

    fun cancel() {
        animator?.cancel()
        animator = null
        handler.removeCallbacksAndMessages(null)
        currentIndex = -1
        targets = emptyList()
        visibility = GONE
        invalidate()
    }

    private fun runStep() {
        val idx = currentIndex
        if (idx < 0 || idx >= targets.size) {
            visibility = GONE
            onFinished?.invoke()
            return
        }
        startPulse()

        // Самплируем сэмплы в первой половине удержания, чтобы успеть стабилизироваться.
        handler.postDelayed({
            onSample?.invoke(targets[idx])
        }, holdMs / 2)

        handler.postDelayed({
            currentIndex = idx + 1
            runStep()
        }, holdMs)
    }

    private fun startPulse() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.5f, 1f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentIndex < 0 || currentIndex >= targets.size) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val target = targets[currentIndex]
        val cx = target.nx * width
        val cy = target.ny * height
        val baseR = 28f
        val outerR = baseR * (1f + pulse * 1.2f)
        canvas.drawCircle(cx, cy, outerR + 12f, dotStroke)
        canvas.drawCircle(cx, cy, outerR, dotFill)
        canvas.drawCircle(cx, cy, baseR * 0.45f, dotInner)

        val total = targets.size
        val title = "Калибровка ${currentIndex + 1} / $total"
        val hint = "Смотрите точно на жёлтую точку"
        canvas.drawText(title, width / 2f, height * 0.06f, textPaint)
        canvas.drawText(hint, width / 2f, height * 0.06f + 38f, hintPaint)
    }
}
