package com.example.variant44gaze

import android.graphics.PointF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

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

        val leftCenter = averagePoint(leftEyePoints)
        val rightCenter = averagePoint(rightEyePoints)
        val eyesMiddle = PointF((leftCenter.x + rightCenter.x) / 2f, (leftCenter.y + rightCenter.y) / 2f)

        val noseBridge = face.getContour(FaceContour.NOSE_BRIDGE)?.points ?: emptyList()
        val noseCenter = if (noseBridge.isNotEmpty()) averagePoint(noseBridge) else eyesMiddle

        val eyeDistance = (rightCenter.x - leftCenter.x).coerceAtLeast(1f)
        val eyeOffsetX = ((noseCenter.x - eyesMiddle.x) / eyeDistance).coerceIn(-0.35f, 0.35f)
        val eyeOffsetY = ((eyesMiddle.y - noseCenter.y) / eyeDistance).coerceIn(-0.35f, 0.35f)
        val baseVector = floatArrayOf(eyeOffsetX * 1.8f, -eyeOffsetY * 1.6f, 1f)

        val worldGaze = PoseMath.rotateByEulerDegrees(
            vector = baseVector,
            pitchXDeg = face.headEulerAngleX,
            yawYDeg = face.headEulerAngleY,
            rollZDeg = face.headEulerAngleZ
        )

        val normalizedX = (
            0.5f +
                worldGaze[0] * 0.42f +
                (face.headEulerAngleY / 90f) * 0.25f
            ).coerceIn(0f, 1f)

        val normalizedY = (
            0.46f +
                worldGaze[1] * 0.52f -
                (face.headEulerAngleX / 90f) * 0.20f
            ).coerceIn(0f, 1f)

        val gazePoint = PointF(
            normalizedX * imageWidth,
            normalizedY * imageHeight
        )

        return GazeFrameResult(
            boundingBox = face.boundingBox,
            gazePointImage = gazePoint,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            eulerX = face.headEulerAngleX,
            eulerY = face.headEulerAngleY,
            eulerZ = face.headEulerAngleZ,
            gazeVector3D = worldGaze
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

    fun release() {
        detector.close()
    }
}
