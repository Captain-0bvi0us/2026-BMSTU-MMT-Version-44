package com.example.variant44gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
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
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isBusy.set(false)
            imageProxy.close()
            return
        }
        try {
            val raw = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            // Поворачиваем кадр сами, чтобы MediaPipe всегда получал "выпрямленную" портретную картинку.
            // Так landmarks гарантированно приходят в системе координат экрана (X - горизонталь, Y - вертикаль).
            val upright = if (rotation == 0) {
                raw
            } else {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                raw.recycle()
                rotated
            }
            val mpImage = BitmapImageBuilder(upright).build()
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(0)
                .build()
            faceLandmarker.detectAsync(mpImage, processingOptions, System.currentTimeMillis())
            upright.recycle()
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

        // Центр глаза по X - середина между уголками (33-133, 362-263).
        // Центр глаза по Y - середина между верхним и нижним веком (159-145, 386-374),
        // потому что уголки не лежат на линии зрачка и дают смещение точки вверх.
        val leftEyeCenterN = PointF(
            (landmarks[33].x() + landmarks[133].x()) / 2f,
            (landmarks[159].y() + landmarks[145].y()) / 2f
        )
        val rightEyeCenterN = PointF(
            (landmarks[362].x() + landmarks[263].x()) / 2f,
            (landmarks[386].y() + landmarks[374].y()) / 2f
        )
        // Центры радужек дают сами landmarks (468 и 473 - центральные точки).
        val leftIrisCenterN = PointF(landmarks[468].x(), landmarks[468].y())
        val rightIrisCenterN = PointF(landmarks[473].x(), landmarks[473].y())

        val leftEyeCenter = PointF(leftEyeCenterN.x * imageWidth, leftEyeCenterN.y * imageHeight)
        val rightEyeCenter = PointF(rightEyeCenterN.x * imageWidth, rightEyeCenterN.y * imageHeight)
        val leftIrisCenter = PointF(leftIrisCenterN.x * imageWidth, leftIrisCenterN.y * imageHeight)
        val rightIrisCenter = PointF(rightIrisCenterN.x * imageWidth, rightIrisCenterN.y * imageHeight)

        val leftEyeHalfW = (kotlin.math.abs(landmarks[33].x() - landmarks[133].x()) / 2f).coerceAtLeast(1e-4f)
        val rightEyeHalfW = (kotlin.math.abs(landmarks[362].x() - landmarks[263].x()) / 2f).coerceAtLeast(1e-4f)
        val leftEyeHalfH = (kotlin.math.abs(landmarks[159].y() - landmarks[145].y()) / 2f).coerceAtLeast(1e-4f)
        val rightEyeHalfH = (kotlin.math.abs(landmarks[386].y() - landmarks[374].y()) / 2f).coerceAtLeast(1e-4f)

        val leftNormX = (leftIrisCenterN.x - leftEyeCenterN.x) / leftEyeHalfW
        val rightNormX = (rightIrisCenterN.x - rightEyeCenterN.x) / rightEyeHalfW
        val leftNormY = (leftIrisCenterN.y - leftEyeCenterN.y) / leftEyeHalfH
        val rightNormY = (rightIrisCenterN.y - rightEyeCenterN.y) / rightEyeHalfH

        // Чувствительность: реальное смещение радужки внутри глаза маленькое
        // (~0.2-0.4 от полуширины), поэтому усиливаем, чтобы точка ходила по всему экрану.
        val sensitivityX = 5.5f
        val sensitivityY = 5.0f
        val rawOffsetX = ((leftNormX + rightNormX) / 2f * sensitivityX).coerceIn(-1f, 1f)
        val rawOffsetY = ((leftNormY + rightNormY) / 2f * sensitivityY).coerceIn(-1f, 1f)

        val centerX = imageWidth / 2f
        val centerY = imageHeight / 2f

        val eyeDistance = max(kotlin.math.abs(rightEyeCenter.x - leftEyeCenter.x), 1f)
        val minX = min(leftEyeCenter.x, rightEyeCenter.x)
        val maxX = max(leftEyeCenter.x, rightEyeCenter.x)
        val minY = min(leftEyeCenter.y, rightEyeCenter.y)
        val maxY = max(leftEyeCenter.y, rightEyeCenter.y)
        val faceBox = Rect(
            max(0f, minX - eyeDistance * 0.9f).toInt(),
            max(0f, minY - eyeDistance * 0.9f).toInt(),
            min(imageWidth.toFloat(), maxX + eyeDistance * 0.9f).toInt(),
            min(imageHeight.toFloat(), maxY + eyeDistance * 1.6f).toInt()
        )

        val matrix = result.facialTransformationMatrixes()
            .takeIf { it.isPresent && it.get().isNotEmpty() }
            ?.get()
            ?.get(0)
        val euler = matrix?.let { eulerFromColumnMajor(it) } ?: Triple(0f, 0f, 0f)

        // Точка взгляда зависит ТОЛЬКО от движения зрачков (радужки),
        // повороты головы не подмешиваем, иначе точка прилипает к краю.
        val gainX = imageWidth * 0.5f
        val gainY = imageHeight * 0.5f
        val gazePoint = PointF(
            (centerX + rawOffsetX * gainX).coerceIn(0f, imageWidth.toFloat()),
            (centerY + rawOffsetY * gainY).coerceIn(0f, imageHeight.toFloat())
        )
        val worldGaze = PoseMath.normalize(floatArrayOf(rawOffsetX, rawOffsetY, 1f))

        return GazeFrameResult(
            boundingBox = faceBox,
            gazePointImage = gazePoint,
            leftEyeTrackPointImage = leftIrisCenter,
            rightEyeTrackPointImage = rightIrisCenter,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            eulerX = euler.first,
            eulerY = euler.second,
            eulerZ = euler.third,
            gazeVector3D = worldGaze
        )
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
