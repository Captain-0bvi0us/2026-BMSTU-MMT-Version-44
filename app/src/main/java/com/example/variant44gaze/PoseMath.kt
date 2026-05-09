package com.example.variant44gaze

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PoseMath {
    fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-6f) return floatArrayOf(0f, 0f, 1f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    fun rotateByEulerDegrees(
        vector: FloatArray,
        pitchXDeg: Float,
        yawYDeg: Float,
        rollZDeg: Float
    ): FloatArray {
        val rx = Math.toRadians(pitchXDeg.toDouble()).toFloat()
        val ry = Math.toRadians(yawYDeg.toDouble()).toFloat()
        val rz = Math.toRadians(rollZDeg.toDouble()).toFloat()

        val cx = cos(rx)
        val sx = sin(rx)
        val cy = cos(ry)
        val sy = sin(ry)
        val cz = cos(rz)
        val sz = sin(rz)

        val x1 = vector[0]
        val y1 = vector[1] * cx - vector[2] * sx
        val z1 = vector[1] * sx + vector[2] * cx

        val x2 = x1 * cy + z1 * sy
        val y2 = y1
        val z2 = -x1 * sy + z1 * cy

        val x3 = x2 * cz - y2 * sz
        val y3 = x2 * sz + y2 * cz
        val z3 = z2

        return normalize(floatArrayOf(x3, y3, z3))
    }
}
