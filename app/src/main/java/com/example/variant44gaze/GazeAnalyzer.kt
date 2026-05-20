package com.example.variant44gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class GazeAnalyzer(
    context: Context,
    private val onResult: (GazeFrameResult?) -> Unit
) : ImageAnalysis.Analyzer {

    private val isBusy = AtomicBoolean(false)

    private val faceLandmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.45f)
            .setMinFacePresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, input ->
                onResult(buildResult(result, input.width, input.height))
                isBusy.set(false)
            }
            .setErrorListener {
                onResult(null)
                isBusy.set(false)
            }
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        if (isBusy.getAndSet(true)) {
            imageProxy.close()
            return
        }
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val mpImage = BitmapImageBuilder(bitmap)
                .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                .build()
            faceLandmarker.detectAsync(mpImage, System.currentTimeMillis())
            bitmap.recycle()
        } catch (_: Exception) {
            isBusy.set(false)
            onResult(null)
        } finally {
            imageProxy.close()
        }
    }

    private fun buildResult(result: FaceLandmarkerResult, imageWidth: Int, imageHeight: Int): GazeFrameResult? {
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null
        val landmarks = faces[0]
        if (landmarks.size < 478) return null

        val leftEyeCenter = average(landmarks, LEFT_EYE)
        val rightEyeCenter = average(landmarks, RIGHT_EYE)
        val leftIrisCenter = average(landmarks, LEFT_IRIS)
        val rightIrisCenter = average(landmarks, RIGHT_IRIS)
        val nose = landmarks[NOSE_TIP]
        val nosePoint = PointF(nose.x() * imageWidth, nose.y() * imageHeight)

        val eyeDistance = max(kotlin.math.abs(rightEyeCenter.x - leftEyeCenter.x), 1e-4f)
        val leftOffsetX = (leftIrisCenter.x - leftEyeCenter.x) / eyeDistance
        val leftOffsetY = (leftIrisCenter.y - leftEyeCenter.y) / eyeDistance
        val rightOffsetX = (rightIrisCenter.x - rightEyeCenter.x) / eyeDistance
        val rightOffsetY = (rightIrisCenter.y - rightEyeCenter.y) / eyeDistance

        val gazeOffsetX = ((leftOffsetX + rightOffsetX) / 2f).coerceIn(-0.45f, 0.45f)
        val gazeOffsetY = ((leftOffsetY + rightOffsetY) / 2f).coerceIn(-0.45f, 0.45f)

        // Центр нейтрального взгляда - переносица; при смещении радужки точка уходит от носа.
        val gazePx = PointF(
            (nosePoint.x + gazeOffsetX * eyeDistance * imageWidth * 0.9f)
                .coerceIn(0f, imageWidth.toFloat()),
            (nosePoint.y + gazeOffsetY * eyeDistance * imageWidth * 1.05f)
                .coerceIn(0f, imageHeight.toFloat())
        )

        val matrix = result.facialTransformationMatrixes()
            .takeIf { it.isPresent && it.get().isNotEmpty() }
            ?.get()
            ?.get(0)
        val euler = matrix?.let { eulerFromColumnMajor(it) } ?: Triple(0f, 0f, 0f)
        val worldGaze = PoseMath.normalize(floatArrayOf(gazeOffsetX, gazeOffsetY, 1f))

        val minX = listOf(leftEyeCenter.x, rightEyeCenter.x, nosePoint.x).minOrNull() ?: 0f
        val minY = listOf(leftEyeCenter.y, rightEyeCenter.y, nosePoint.y).minOrNull() ?: 0f
        val maxX = listOf(leftEyeCenter.x, rightEyeCenter.x, nosePoint.x).maxOrNull() ?: imageWidth.toFloat()
        val maxY = listOf(leftEyeCenter.y, rightEyeCenter.y, nosePoint.y).maxOrNull() ?: imageHeight.toFloat()
        val faceBox = Rect(
            max(0f, minX - eyeDistance * imageWidth * 0.4f).toInt(),
            max(0f, minY - eyeDistance * imageWidth * 0.5f).toInt(),
            min(imageWidth.toFloat(), maxX + eyeDistance * imageWidth * 0.4f).toInt(),
            min(imageHeight.toFloat(), maxY + eyeDistance * imageWidth * 0.8f).toInt()
        )

        return GazeFrameResult(
            boundingBox = faceBox,
            gazePointImage = gazePx,
            leftEyeTrackPointImage = leftIrisCenter,
            rightEyeTrackPointImage = rightIrisCenter,
            nosePointImage = nosePoint,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            eulerX = euler.first,
            eulerY = euler.second,
            eulerZ = euler.third,
            gazeVector3D = worldGaze
        )
    }

    private fun average(landmarks: List<NormalizedLandmark>, indexes: IntArray): PointF {
        var sx = 0f
        var sy = 0f
        for (idx in indexes) {
            sx += landmarks[idx].x()
            sy += landmarks[idx].y()
        }
        val n = indexes.size.toFloat()
        return PointF(sx / n, sy / n)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate()
        buf.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val w = image.width + rowPadding / pixelStride.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buf)
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }
        }
    }

    private fun eulerFromColumnMajor(m: FloatArray): Triple<Float, Float, Float> {
        if (m.size < 11) return Triple(0f, 0f, 0f)
        val r00 = m[0].toDouble()
        val r10 = m[1].toDouble()
        val r20 = m[2].toDouble()
        val r21 = m[6].toDouble()
        val r22 = m[10].toDouble()
        val yaw = atan2(r10, r00)
        val pitch = atan2(-r20, hypot(r21, r22))
        val roll = atan2(r21, r22)
        return Triple(
            Math.toDegrees(pitch).toFloat(),
            Math.toDegrees(yaw).toFloat(),
            Math.toDegrees(roll).toFloat()
        )
    }

    fun release() {
        faceLandmarker.close()
    }

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val NOSE_TIP = 1
        private val LEFT_IRIS = intArrayOf(468, 469, 470, 471, 472)
        private val RIGHT_IRIS = intArrayOf(473, 474, 475, 476, 477)
        private val LEFT_EYE = intArrayOf(
            33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246
        )
        private val RIGHT_EYE = intArrayOf(
            362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398
        )
    }
}
