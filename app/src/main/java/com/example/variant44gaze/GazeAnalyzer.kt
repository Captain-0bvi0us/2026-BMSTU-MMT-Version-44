package com.example.variant44gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
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

/**
 * Анализатор кадров CameraX → детекция лица/глаз через MediaPipe FaceLandmarker →
 * извлечение направления взгляда из ARKit-style blendshapes.
 *
 * Выходит **сырой** вектор взгляда (gx, gy) в [-1, 1]. Отображение на экран
 * выполняется во внешнем коде через [GazeCalibrator].
 */
class GazeAnalyzer(
    context: Context,
    private val onResult: (GazeFrameResult?) -> Unit
) : ImageAnalysis.Analyzer {

    private val isBusy = AtomicBoolean(false)

    @Volatile
    private var baselineX: Float = 0f

    @Volatile
    private var baselineY: Float = 0f

    @Volatile
    private var baselineFrames: Int = 0

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
            .setMinFaceDetectionConfidence(0.4f)
            .setMinFacePresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setOutputFaceBlendshapes(true)
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

        val leftEyeCenterN = PointF(
            (landmarks[33].x() + landmarks[133].x()) / 2f,
            (landmarks[159].y() + landmarks[145].y()) / 2f
        )
        val rightEyeCenterN = PointF(
            (landmarks[362].x() + landmarks[263].x()) / 2f,
            (landmarks[386].y() + landmarks[374].y()) / 2f
        )
        val leftIrisCenterN = PointF(landmarks[468].x(), landmarks[468].y())
        val rightIrisCenterN = PointF(landmarks[473].x(), landmarks[473].y())
        val noseN = PointF(landmarks[1].x(), landmarks[1].y())

        val leftEyeCenter = PointF(leftEyeCenterN.x * imageWidth, leftEyeCenterN.y * imageHeight)
        val rightEyeCenter = PointF(rightEyeCenterN.x * imageWidth, rightEyeCenterN.y * imageHeight)
        val leftIrisCenter = PointF(leftIrisCenterN.x * imageWidth, leftIrisCenterN.y * imageHeight)
        val rightIrisCenter = PointF(rightIrisCenterN.x * imageWidth, rightIrisCenterN.y * imageHeight)
        val nose = PointF(noseN.x * imageWidth, noseN.y * imageHeight)

        val leftEyeHalfW = (kotlin.math.abs(landmarks[33].x() - landmarks[133].x()) / 2f).coerceAtLeast(1e-4f)
        val rightEyeHalfW = (kotlin.math.abs(landmarks[362].x() - landmarks[263].x()) / 2f).coerceAtLeast(1e-4f)
        val leftEyeHalfH = (kotlin.math.abs(landmarks[159].y() - landmarks[145].y()) / 2f).coerceAtLeast(1e-4f)
        val rightEyeHalfH = (kotlin.math.abs(landmarks[386].y() - landmarks[374].y()) / 2f).coerceAtLeast(1e-4f)

        val blendGaze = extractGazeFromBlendshapes(result)
        val rawGx: Float
        val rawGy: Float
        if (blendGaze != null) {
            // Усиление сырого blendshape-сигнала: реальная амплитуда eyeLookOut/In редко
            // достигает 1.0, обычно сидит в 0.2–0.5. Без множителя точка едва уходит от центра.
            rawGx = (blendGaze.x * BLEND_GAIN_X).coerceIn(-1f, 1f)
            rawGy = (blendGaze.y * BLEND_GAIN_Y).coerceIn(-1f, 1f)
        } else {
            val leftNormX = (leftIrisCenterN.x - leftEyeCenterN.x) / leftEyeHalfW
            val rightNormX = (rightIrisCenterN.x - rightEyeCenterN.x) / rightEyeHalfW
            val leftNormY = (leftIrisCenterN.y - leftEyeCenterN.y) / leftEyeHalfH
            val rightNormY = (rightIrisCenterN.y - rightEyeCenterN.y) / rightEyeHalfH
            val avgRawX = (leftNormX + rightNormX) / 2f
            val avgRawY = (leftNormY + rightNormY) / 2f
            if (baselineFrames < CALIBRATION_FRAMES) {
                baselineX = (baselineX * baselineFrames + avgRawX) / (baselineFrames + 1)
                baselineY = (baselineY * baselineFrames + avgRawY) / (baselineFrames + 1)
                baselineFrames++
            } else {
                val drift = 0.002f
                baselineX = baselineX * (1f - drift) + avgRawX * drift
                baselineY = baselineY * (1f - drift) + avgRawY * drift
            }
            rawGx = ((avgRawX - baselineX) * IRIS_SENS_X).coerceIn(-1f, 1f)
            rawGy = ((avgRawY - baselineY) * IRIS_SENS_Y).coerceIn(-1f, 1f)
        }

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
        val pitchDeg = euler.first
        val yawDeg = euler.second
        val rollDeg = euler.third

        // Дополняем сигнал глаз поворотом головы. Когда пользователь "смотрит" в край экрана,
        // он почти всегда чуть-чуть докручивает голову — этот мини-поворот в чистом
        // blendshape-сигнале не виден, и точка взгляда не доходит до края.
        // HEAD_ANGLE_NORM = 25° соответствует gx = 1 (полный край экрана) только от головы.
        val headGx = (yawDeg / HEAD_ANGLE_NORM_DEG).coerceIn(-1f, 1f)
        val headGy = (pitchDeg / HEAD_ANGLE_NORM_DEG).coerceIn(-1f, 1f)
        val gx = (rawGx + HEAD_WEIGHT_X * headGx).coerceIn(-1f, 1f)
        val gy = (rawGy + HEAD_WEIGHT_Y * headGy).coerceIn(-1f, 1f)

        return GazeFrameResult(
            boundingBox = faceBox,
            leftEyeImagePoint = leftEyeCenter,
            rightEyeImagePoint = rightEyeCenter,
            noseImagePoint = nose,
            leftIrisImagePoint = leftIrisCenter,
            rightIrisImagePoint = rightIrisCenter,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            gazeRaw = PointF(gx, gy),
            eulerPitchDeg = pitchDeg,
            eulerYawDeg = yawDeg,
            eulerRollDeg = rollDeg,
            faceDetected = true,
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * Извлекает направление взгляда из ARKit-style blendshapes.
     * x > 0 — смотрит вправо (с точки зрения пользователя),
     * y > 0 — смотрит вниз. Диапазон ≈ [-1, 1].
     */
    private fun extractGazeFromBlendshapes(result: FaceLandmarkerResult): PointF? {
        val blendshapes = result.faceBlendshapes()
            .takeIf { it.isPresent && it.get().isNotEmpty() }
            ?.get()
            ?.get(0) ?: return null

        var lookUpL = 0f
        var lookDownL = 0f
        var lookInL = 0f
        var lookOutL = 0f
        var lookUpR = 0f
        var lookDownR = 0f
        var lookInR = 0f
        var lookOutR = 0f

        for (cat in blendshapes) {
            when (cat.categoryName()) {
                "eyeLookUpLeft" -> lookUpL = cat.score()
                "eyeLookDownLeft" -> lookDownL = cat.score()
                "eyeLookInLeft" -> lookInL = cat.score()
                "eyeLookOutLeft" -> lookOutL = cat.score()
                "eyeLookUpRight" -> lookUpR = cat.score()
                "eyeLookDownRight" -> lookDownR = cat.score()
                "eyeLookInRight" -> lookInR = cat.score()
                "eyeLookOutRight" -> lookOutR = cat.score()
            }
        }

        val anyEyeBlend = listOf(
            lookUpL, lookDownL, lookInL, lookOutL,
            lookUpR, lookDownR, lookInR, lookOutR
        ).any { it > 1e-6f }
        if (!anyEyeBlend) return null

        // С точки зрения пользователя:
        //   "вправо" = правый глаз смотрит наружу + левый смотрит внутрь.
        val horizontal = ((lookOutR + lookInL) - (lookOutL + lookInR)) / 2f
        val vertical = ((lookDownL + lookDownR) - (lookUpL + lookUpR)) / 2f
        return PointF(horizontal, vertical)
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

    fun resetIrisBaseline() {
        baselineX = 0f
        baselineY = 0f
        baselineFrames = 0
    }

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val CALIBRATION_FRAMES = 30

        // Усиление чистого blendshape-сигнала.
        // По X сильнее, чем по Y, потому что горизонтальные саккады дают слабее всплеск
        // в eyeLookOut/In, чем вертикальные в eyeLookDown/Up.
        private const val BLEND_GAIN_X = 3.6f
        private const val BLEND_GAIN_Y = 1.8f

        // Усиление iris-fallback (когда blendshapes пустые).
        private const val IRIS_SENS_X = 18.0f
        private const val IRIS_SENS_Y = 6.0f

        // Сколько градусов поворота головы соответствуют полному размаху взгляда (gx=±1).
        private const val HEAD_ANGLE_NORM_DEG = 25.0f
        // Вклад поворота головы в итоговый gaze (0 — не учитываем, 1 — голова даёт полный размах).
        private const val HEAD_WEIGHT_X = 0.55f
        private const val HEAD_WEIGHT_Y = 0.45f
    }
}
