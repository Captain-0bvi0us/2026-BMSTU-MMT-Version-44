package com.example.variant44gaze

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.variant44gaze.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var gazeAnalyzer: GazeAnalyzer? = null
    private val fixationTracker = FixationTracker()
    private var smoothedPoint: PointF? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Для работы нужен доступ к камере", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        checkCameraPermissionAndStart()
    }

    override fun onDestroy() {
        gazeAnalyzer?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun checkCameraPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = try {
                GazeAnalyzer(this) { frame ->
                runOnUiThread {
                    if (frame == null) {
                        fixationTracker.resetTracking()
                        smoothedPoint = null
                        binding.infoText.text = "Лицо не обнаружено"
                        binding.statusText.text = "Наведите лицо в центр кадра"
                        binding.overlayView.update(
                            frameResult = null,
                            fixationPoints = emptyList(),
                            leftEyeTrail = emptyList(),
                            rightEyeTrail = emptyList(),
                            gazeTrail = emptyList(),
                            isFixating = false,
                            isFrontCamera = true
                        )
                        return@runOnUiThread
                    }

                    val smoothed = smoothPoint(frame.gazePointImage)
                    val smoothedFrame = frame.copy(gazePointImage = smoothed)
                    val fixationState = fixationTracker.update(smoothed, System.currentTimeMillis())

                    binding.overlayView.update(
                        frameResult = smoothedFrame,
                        fixationPoints = fixationState.fixationPoints,
                        leftEyeTrail = emptyList(),
                        rightEyeTrail = emptyList(),
                        gazeTrail = emptyList(),
                        isFixating = fixationState.isFixating,
                        isFrontCamera = true
                    )

                    val nx = (smoothed.x / frame.imageWidth).coerceIn(0f, 1f)
                    val ny = (smoothed.y / frame.imageHeight).coerceIn(0f, 1f)
                    val screenX = nx * binding.previewView.width
                    val screenY = ny * binding.previewView.height

                    binding.infoText.text = buildInfoText(
                        normX = nx,
                        normY = ny,
                        screenX = screenX,
                        screenY = screenY,
                        frame = frame
                    )
                    binding.statusText.text = buildStatusText(fixationState)
                }
            }
            } catch (t: Throwable) {
                binding.infoText.text = "Ошибка инициализации трекинга: ${t.javaClass.simpleName}"
                binding.statusText.text = "Попробуйте API 34 x86_64 и пересоберите проект"
                Toast.makeText(this, "Не удалось запустить анализатор взгляда", Toast.LENGTH_LONG).show()
                null
            }
            gazeAnalyzer = analyzer

            if (analyzer == null) {
                return@addListener
            }

            analysis.setAnalyzer(cameraExecutor, analyzer)
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun smoothPoint(current: PointF): PointF {
        val previous = smoothedPoint
        if (previous == null) {
            smoothedPoint = PointF(current.x, current.y)
            return smoothedPoint!!
        }
        val alpha = 0.2f
        val smooth = PointF(
            previous.x * (1f - alpha) + current.x * alpha,
            previous.y * (1f - alpha) + current.y * alpha
        )
        smoothedPoint = smooth
        return smooth
    }

    private fun buildInfoText(
        normX: Float,
        normY: Float,
        screenX: Float,
        screenY: Float,
        frame: GazeFrameResult
    ): String {
        return """
            Вариант 44: трекинг взгляда
            Координаты (norm): X=${fmt(normX)}  Y=${fmt(normY)}
            Координаты (px): X=${fmt(screenX)}  Y=${fmt(screenY)}
            Euler: X=${fmt(frame.eulerX)}  Y=${fmt(frame.eulerY)}  Z=${fmt(frame.eulerZ)}
        """.trimIndent()
    }

    private fun buildStatusText(state: FixationState): String {
        val currentState = if (state.isFixating) "фиксация: ДА" else "фиксация: нет"
        return "Точек фиксации: ${state.fixationCount} | $currentState"
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
}
