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

class GazeAnalyzer(
    context: Context,
    private val onResult: (GazeFrameResult?) -> Unit
) : ImageAnalysis.Analyzer {

    private val isBusy = AtomicBoolean(false)

    // Автокалибровка "прямого взгляда": базовое смещение зрачка при взгляде в центр.
    // Без этого анатомическое смещение радужки давало рывок точки в край при старте.
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
            .setMinFaceDetectionConfidence(0.45f)
            .setMinFacePresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
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

        // Главный источник направления взгляда - eye blendshapes из MediaPipe.
        // Это ARKit-style значения (0..1): eyeLookUp/Down/In/Out для каждого глаза.
        // Они описывают именно НАПРАВЛЕНИЕ ВЗГЛЯДА и не зависят от поворота головы.
        val blendGaze = extractGazeFromBlendshapes(result)

        val gazeOffsetX: Float
        val gazeOffsetY: Float
        if (blendGaze != null) {
            // Чувствительность по X сильно завышена - пользователь хочет,
            // чтобы небольшое движение глаз вправо/влево уже укладывало точку у края.
            val sensitivityX = 6.0f
            val sensitivityY = 2.0f
            gazeOffsetX = (blendGaze.x * sensitivityX).coerceIn(-1f, 1f)
            gazeOffsetY = (blendGaze.y * sensitivityY).coerceIn(-1f, 1f)
        } else {
            // Fallback на iris-метод (если blendshapes не вернулись).
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
            val fallbackSensX = 14.0f
            val fallbackSensY = 6.0f
            gazeOffsetX = ((avgRawX - baselineX) * fallbackSensX).coerceIn(-1f, 1f)
            gazeOffsetY = ((avgRawY - baselineY) * fallbackSensY).coerceIn(-1f, 1f)
        }

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

        // Точка взгляда в координатах кадра.
        // X: gazeOffsetX > 0 значит "пользователь смотрит вправо".
        //    Из-за зеркала фронтальной камеры в OverlayView X инвертируется при отрисовке,
        //    поэтому здесь нужно ВЫЧИТАТЬ, чтобы на экране точка оказалась справа.
        // Y: gazeOffsetY > 0 значит "пользователь смотрит вниз" - просто прибавляем.
        // verticalShift поднимает "нейтральный" Y на треть экрана вверх,
        // потому что при взгляде в центр экрана камера видит глаза немного "сверху",
        // и MediaPipe в среднем считает что пользователь смотрит вниз.
        val gainX = imageWidth * 0.5f
        val gainY = imageHeight * 0.4f
        val verticalShift = imageHeight / 3f
        val gazePoint = PointF(
            (centerX - gazeOffsetX * gainX).coerceIn(0f, imageWidth.toFloat()),
            (centerY + gazeOffsetY * gainY - verticalShift).coerceIn(0f, imageHeight.toFloat())
        )
        val worldGaze = PoseMath.normalize(floatArrayOf(gazeOffsetX, gazeOffsetY, 1f))

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

    /**
     * Извлекает направление взгляда из ARKit-style blendshapes.
     * Возвращает PointF(x, y) где
     *   x > 0 - смотрит вправо (с точки зрения пользователя)
     *   y > 0 - смотрит вниз
     * Диапазон примерно [-1, 1].
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

        // Если ни одного из глазных blendshapes нет - считаем что модель их не возвращает.
        val anyEyeBlend = listOf(lookUpL, lookDownL, lookInL, lookOutL,
            lookUpR, lookDownR, lookInR, lookOutR).any { it > 1e-6f }
        if (!anyEyeBlend) return null

        // Для пользователя:
        //   "вправо" = правый глаз смотрит наружу + левый глаз смотрит внутрь
        //   "влево"  = левый глаз смотрит наружу + правый глаз смотрит внутрь
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

    fun recalibrate() {
        baselineX = 0f
        baselineY = 0f
        baselineFrames = 0
    }

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val CALIBRATION_FRAMES = 30
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
