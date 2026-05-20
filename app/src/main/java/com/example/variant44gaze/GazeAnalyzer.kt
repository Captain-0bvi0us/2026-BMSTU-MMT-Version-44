package com.example.variant44gaze

import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class GazeAnalyzer(
    private val onResult: (GazeFrameResult?) -> Unit
) : ImageAnalysis.Analyzer {

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .enableTracking()
        .build()

    private val detector: FaceDetector = FaceDetection.getClient(detectorOptions)
    private val isBusy = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        if (isBusy.getAndSet(true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isBusy.set(false)
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        val resultWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val resultHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        detector.process(image)
            .addOnSuccessListener { faces ->
                onResult(buildResult(faces, resultWidth, resultHeight))
            }
            .addOnFailureListener {
                onResult(null)
            }
            .addOnCompleteListener {
                imageProxy.close()
                isBusy.set(false)
            }
    }

    private fun buildResult(faces: List<Face>, imageWidth: Int, imageHeight: Int): GazeFrameResult? {
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
        val leftEyePoints = face.getContour(FaceContour.LEFT_EYE)?.points ?: emptyList()
        val rightEyePoints = face.getContour(FaceContour.RIGHT_EYE)?.points ?: emptyList()
        if (leftEyePoints.isEmpty() || rightEyePoints.isEmpty()) return null

        val leftTrack = estimateEyeTrackPoint(leftEyePoints)
        val rightTrack = estimateEyeTrackPoint(rightEyePoints)
        val leftCenter = leftTrack.center
        val rightCenter = rightTrack.center
        val eyesMiddle = PointF((leftCenter.x + rightCenter.x) / 2f, (leftCenter.y + rightCenter.y) / 2f)

        val noseBridge = face.getContour(FaceContour.NOSE_BRIDGE)?.points ?: emptyList()
        val nosePoint = if (noseBridge.isNotEmpty()) averagePoint(noseBridge) else eyesMiddle

        val eyeDistancePx = max(kotlin.math.abs(rightCenter.x - leftCenter.x), 1f)
        val eyeDistanceNorm = eyeDistancePx / imageWidth.toFloat().coerceAtLeast(1f)

        // Смещение "внутриглазной" трек-точки (proxy для направления взгляда) - без использования углов головы.
        val gazeOffsetX = ((leftTrack.normX + rightTrack.normX) / 2f).coerceIn(-0.9f, 0.9f)
        val gazeOffsetY = ((leftTrack.normY + rightTrack.normY) / 2f).coerceIn(-0.9f, 0.9f)

        // Привязываем точку взгляда к центру кадра, а не к положению лица:
        // при смещении головы по кадру "экранная" точка остается стабильнее.
        val centerX = imageWidth / 2f
        val centerY = imageHeight / 2f
        val gazePoint = PointF(
            (centerX + gazeOffsetX * imageWidth * 0.35f)
                .coerceIn(0f, imageWidth.toFloat()),
            (centerY + gazeOffsetY * imageHeight * 0.35f)
                .coerceIn(0f, imageHeight.toFloat())
        )

        val faceBox = buildFaceBox(leftCenter, rightCenter, nosePoint, imageWidth, imageHeight)
        val worldGaze = PoseMath.normalize(floatArrayOf(gazeOffsetX, gazeOffsetY, 1f))

        return GazeFrameResult(
            boundingBox = faceBox,
            gazePointImage = gazePoint,
            leftEyeTrackPointImage = leftTrack.point,
            rightEyeTrackPointImage = rightTrack.point,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            eulerX = face.headEulerAngleX,
            eulerY = face.headEulerAngleY,
            eulerZ = face.headEulerAngleZ,
            gazeVector3D = worldGaze
        )
    }

    private fun buildFaceBox(
        leftEye: PointF,
        rightEye: PointF,
        nose: PointF,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {
        val eyeDistance = max(kotlin.math.abs(rightEye.x - leftEye.x), 1f)
        val minX = min(leftEye.x, rightEye.x)
        val maxX = max(leftEye.x, rightEye.x)
        val top = min(min(leftEye.y, rightEye.y), nose.y) - eyeDistance * 0.8f
        val bottom = max(max(leftEye.y, rightEye.y), nose.y) + eyeDistance * 1.3f
        return Rect(
            max(0f, minX - eyeDistance * 0.8f).toInt(),
            max(0f, top).toInt(),
            min(imageWidth.toFloat(), maxX + eyeDistance * 0.8f).toInt(),
            min(imageHeight.toFloat(), bottom).toInt()
        )
    }

    private data class EyeTrack(
        val point: PointF,
        val center: PointF,
        val normX: Float,
        val normY: Float
    )

    private fun estimateEyeTrackPoint(points: List<PointF>): EyeTrack {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var sumX = 0f
        var sumY = 0f
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            sumX += p.x
            sumY += p.y
        }

        val center = PointF(sumX / points.size, sumY / points.size)
        val halfW = ((maxX - minX) / 2f).coerceAtLeast(1f)
        val halfH = ((maxY - minY) / 2f).coerceAtLeast(1f)
        val midX = (minX + maxX) / 2f
        val midY = (minY + maxY) / 2f

        val normX = ((center.x - midX) / halfW).coerceIn(-1f, 1f)
        val normY = ((center.y - midY) / halfH).coerceIn(-1f, 1f)
        return EyeTrack(point = PointF(center.x, center.y), center = center, normX = normX, normY = normY)
    }

    private fun averagePoint(points: List<PointF>): PointF {
        var sumX = 0f
        var sumY = 0f
        for (p in points) {
            sumX += p.x
            sumY += p.y
        }
        return PointF(sumX / points.size, sumY / points.size)
    }

    fun release() {
        detector.close()
    }
}
