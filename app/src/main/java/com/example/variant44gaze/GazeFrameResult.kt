package com.example.variant44gaze

import android.graphics.PointF
import android.graphics.Rect

/**
 * Результат обработки одного кадра.
 *
 * @param boundingBox прямоугольник лица в системе координат входного изображения.
 * @param leftEyeImagePoint центр левого глаза (image space).
 * @param rightEyeImagePoint центр правого глаза (image space).
 * @param noseImagePoint кончик носа (image space).
 * @param leftIrisImagePoint центр радужки левого глаза (image space).
 * @param rightIrisImagePoint центр радужки правого глаза (image space).
 * @param imageWidth ширина исходного изображения.
 * @param imageHeight высота исходного изображения.
 * @param gazeRaw нормированное направление взгляда в [-1..1]:
 *               x > 0 — пользователь смотрит вправо,
 *               y > 0 — пользователь смотрит вниз.
 * @param eulerPitchDeg, eulerYawDeg, eulerRollDeg — углы Эйлера головы (град.).
 */
data class GazeFrameResult(
    val boundingBox: Rect,
    val leftEyeImagePoint: PointF,
    val rightEyeImagePoint: PointF,
    val noseImagePoint: PointF,
    val leftIrisImagePoint: PointF,
    val rightIrisImagePoint: PointF,
    val imageWidth: Int,
    val imageHeight: Int,
    val gazeRaw: PointF,
    val eulerPitchDeg: Float,
    val eulerYawDeg: Float,
    val eulerRollDeg: Float,
    val faceDetected: Boolean,
    val timestampMs: Long
)
