package com.example.variant44gaze

import android.graphics.PointF
import android.graphics.Rect
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

class MlKitGazeAnalyzer(
    private val onResult: (GazeFrameResult?) -> Unit
) : FrameGazeAnalyzer {

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
            .addOnSuccessListener { faces -> onResult(buildResult(faces, resultWidth, resultHeight)) }
            .addOnFailureListener { onResult(null) }
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

        val leftEye = averagePoint(leftEyePoints)
        val rightEye = averagePoint(rightEyePoints)
        val eyesMiddle = PointF((leftEye.x + rightEye.x) / 2f, (leftEye.y + rightEye.y) / 2f)
        val eyeDistance = max(kotlin.math.abs(rightEye.x - leftEye.x), 1f)

        val headYaw = (face.headEulerAngleY / 35f).coerceIn(-1f, 1f)
        val headPitch = (face.headEulerAngleX / 30f).coerceIn(-1f, 1f)
        val gazePoint = PointF(
            (imageWidth / 2f + headYaw * imageWidth * 0.32f).coerceIn(0f, imageWidth.toFloat()),
            (imageHeight / 2f + headPitch * imageHeight * 0.32f).coerceIn(0f, imageHeight.toFloat())
        )

        val faceBox = Rect(
            max(0f, min(leftEye.x, rightEye.x) - eyeDistance * 0.9f).toInt(),
            max(0f, min(leftEye.y, rightEye.y) - eyeDistance * 0.9f).toInt(),
            min(imageWidth.toFloat(), max(leftEye.x, rightEye.x) + eyeDistance * 0.9f).toInt(),
            min(imageHeight.toFloat(), max(leftEye.y, rightEye.y) + eyeDistance * 1.6f).toInt()
        )

        return GazeFrameResult(
            boundingBox = faceBox,
            gazePointImage = gazePoint,
            leftEyeTrackPointImage = leftEye,
            rightEyeTrackPointImage = rightEye,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            eulerX = face.headEulerAngleX,
            eulerY = face.headEulerAngleY,
            eulerZ = face.headEulerAngleZ,
            gazeVector3D = PoseMath.normalize(floatArrayOf(headYaw, headPitch, 1f))
        )
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

    override fun release() {
        detector.close()
    }
}
