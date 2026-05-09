package com.example.variant44gaze

import android.graphics.PointF
import android.graphics.Rect

data class GazeFrameResult(
    val boundingBox: Rect,
    val gazePointImage: PointF,
    val imageWidth: Int,
    val imageHeight: Int,
    val eulerX: Float,
    val eulerY: Float,
    val eulerZ: Float,
    val gazeVector3D: FloatArray
)
