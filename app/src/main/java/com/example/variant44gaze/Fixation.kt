package com.example.variant44gaze

import android.graphics.PointF

/**
 * Одна точка фиксации взгляда в системе координат экрана.
 *
 * @param screenPoint центр фиксации в пикселях экрана (X слева направо, Y сверху вниз).
 * @param normalizedPoint те же координаты, нормированные на размер экрана (0..1).
 * @param startTimeMs время начала фиксации (System.currentTimeMillis()).
 * @param durationMs длительность нахождения взгляда в радиусе устойчивости.
 * @param sampleCount количество сырых сэмплов, попавших в эту фиксацию.
 * @param index порядковый номер фиксации с момента последней очистки.
 */
data class Fixation(
    val screenPoint: PointF,
    val normalizedPoint: PointF,
    val startTimeMs: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val index: Int
)

/**
 * Состояние трекера фиксаций для одного кадра.
 */
data class FixationState(
    val isFixating: Boolean,
    val currentDwellMs: Long,
    val fixationCount: Int,
    val totalFixationMs: Long,
    val fixations: List<Fixation>
)
