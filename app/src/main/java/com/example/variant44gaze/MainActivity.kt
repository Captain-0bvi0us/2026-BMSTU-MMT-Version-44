package com.example.variant44gaze

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.variant44gaze.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Главный экран:
 *   - запускает камеру + GazeAnalyzer,
 *   - применяет GazeCalibrator (сырое направление → координаты экрана),
 *   - обновляет FixationTracker,
 *   - управляет режимами отображения (live / heatmap),
 *   - режим калибровки (9 точек) с автоматическим сбором сэмплов.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var gazeAnalyzer: GazeAnalyzer? = null
    private lateinit var fixationTracker: FixationTracker
    private lateinit var calibrator: GazeCalibrator

    private var smoothedRaw: PointF? = null
    private val gazeTrail = ArrayDeque<PointF>()
    private val trailMaxSize = 28

    private var mode: AppMode = AppMode.LIVE
    private var showFaceLandmarks: Boolean = true
    private var showGrid: Boolean = false
    private var showFixationDots: Boolean = true

    // Сэмплы взгляда, накопленные за период удержания одной калибровочной точки.
    private val pendingCalibSamples = mutableListOf<PointF>()
    private var calibrationActive: Boolean = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.toast_camera_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE

        calibrator = GazeCalibrator(this)
        fixationTracker = FixationTracker(
            screenWidth = { binding.root.width.toFloat() },
            screenHeight = { binding.root.height.toFloat() }
        )

        bindControls()
        applyMode()

        cameraExecutor = Executors.newSingleThreadExecutor()
        checkCameraPermissionAndStart()
    }

    override fun onDestroy() {
        gazeAnalyzer?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindControls() {
        binding.btnCalibrate.setOnClickListener { startCalibrationFlow() }
        binding.btnClear.setOnClickListener {
            fixationTracker.resetAll()
            gazeTrail.clear()
            updateHeatmap()
            Toast.makeText(this, R.string.toast_fixations_cleared, Toast.LENGTH_SHORT).show()
        }
        binding.btnHeatmap.setOnClickListener {
            mode = if (mode == AppMode.HEATMAP) AppMode.LIVE else AppMode.HEATMAP
            applyMode()
        }
        binding.btnExport.setOnClickListener { exportFixations() }
        binding.btnResetCalibration.setOnClickListener {
            calibrator.clearCalibration()
            gazeAnalyzer?.resetIrisBaseline()
            Toast.makeText(this, R.string.toast_calibration_reset, Toast.LENGTH_SHORT).show()
            updateStatus()
        }
        binding.btnToggleFace.setOnClickListener {
            showFaceLandmarks = !showFaceLandmarks
            binding.btnToggleFace.isSelected = showFaceLandmarks
        }
        binding.btnToggleGrid.setOnClickListener {
            showGrid = !showGrid
            binding.btnToggleGrid.isSelected = showGrid
        }
        binding.btnToggleFixations.setOnClickListener {
            showFixationDots = !showFixationDots
            binding.btnToggleFixations.isSelected = showFixationDots
        }
        binding.btnToggleFace.isSelected = showFaceLandmarks
        binding.btnToggleGrid.isSelected = showGrid
        binding.btnToggleFixations.isSelected = showFixationDots
    }

    private fun applyMode() {
        when (mode) {
            AppMode.LIVE -> {
                binding.previewView.visibility = View.VISIBLE
                binding.heatmapView.visibility = View.GONE
                binding.btnHeatmap.text = getString(R.string.btn_heatmap)
            }
            AppMode.HEATMAP -> {
                binding.previewView.visibility = View.INVISIBLE
                binding.heatmapView.visibility = View.VISIBLE
                binding.btnHeatmap.text = getString(R.string.btn_live)
                updateHeatmap()
            }
        }
    }

    private fun updateHeatmap() {
        binding.heatmapView.setFixations(fixationTracker.snapshot())
    }

    private fun checkCameraPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = try {
                GazeAnalyzer(this) { frame -> runOnUiThread { processFrame(frame) } }
            } catch (t: Throwable) {
                binding.infoText.text = getString(R.string.error_mediapipe, t.javaClass.simpleName)
                binding.statusText.text = getString(R.string.error_mediapipe_hint)
                null
            }
            gazeAnalyzer = analyzer
            analyzer?.let { analysis.setAnalyzer(cameraExecutor, it) }
            cameraProvider.unbindAll()
            val selector = when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                    CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                    CameraSelector.DEFAULT_BACK_CAMERA
                else -> null
            }

            if (selector == null) {
                binding.infoText.text = getString(R.string.error_camera_unavailable)
                binding.statusText.text = getString(R.string.error_camera_hint)
                return@addListener
            }

            try {
                if (analyzer != null) {
                    cameraProvider.bindToLifecycle(this, selector, preview, analysis)
                } else {
                    cameraProvider.bindToLifecycle(this, selector, preview)
                }
                binding.statusText.text = if (selector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    getString(R.string.status_camera_front)
                } else {
                    getString(R.string.status_camera_back)
                }
            } catch (t: Throwable) {
                binding.infoText.text = getString(R.string.error_camera_attach, t.javaClass.simpleName)
                binding.statusText.text = getString(R.string.error_camera_restart)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(frame: GazeFrameResult?) {
        if (frame == null) {
            smoothedRaw = null
            fixationTracker.resetSamples()
            binding.infoText.text = getString(R.string.status_face_not_found)
            binding.statusText.text = getString(R.string.status_face_hint)
            binding.overlayView.update(
                frameResult = null,
                gazeScreenPoint = null,
                fixations = fixationTracker.snapshot(),
                gazeTrail = emptyList(),
                isFixating = false,
                isFrontCamera = true,
                showFaceLandmarks = showFaceLandmarks,
                showGrid = showGrid,
                showFixations = showFixationDots,
                previewBounds = previewBoundsForOverlay()
            )
            return
        }

        val smoothedRawPoint = smoothRaw(frame.gazeRaw)
        val screenW = binding.root.width.toFloat().coerceAtLeast(1f)
        val screenH = binding.root.height.toFloat().coerceAtLeast(1f)
        val gazeScreen = calibrator.map(smoothedRawPoint, screenW, screenH)

        if (calibrationActive) {
            pendingCalibSamples.add(PointF(smoothedRawPoint.x, smoothedRawPoint.y))
            // Во время калибровки только собираем сэмплы, не трекаем фиксации и не перерисовываем
            // оверлей (его закрывает CalibrationView).
            return
        }

        addToTrail(gazeScreen)
        val fixState = fixationTracker.update(gazeScreen, System.currentTimeMillis())

        binding.overlayView.update(
            frameResult = frame,
            gazeScreenPoint = gazeScreen,
            fixations = fixState.fixations,
            gazeTrail = gazeTrail.toList(),
            isFixating = fixState.isFixating,
            isFrontCamera = true,
            showFaceLandmarks = showFaceLandmarks,
            showGrid = showGrid,
            showFixations = showFixationDots,
            previewBounds = previewBoundsForOverlay()
        )

        if (mode == AppMode.HEATMAP) {
            binding.heatmapView.setFixations(fixState.fixations)
        }

        updateInfo(gazeScreen, smoothedRawPoint, frame, fixState)
    }

    /**
     * Возвращает прямоугольник PreviewView в системе координат корневого ConstraintLayout.
     * Нужно overlay'ю, чтобы рисовать face landmarks ровно поверх предпросмотра.
     */
    private fun previewBoundsForOverlay(): RectF {
        val previewW = binding.previewView.width.toFloat()
        val previewH = binding.previewView.height.toFloat()
        if (previewW <= 0f || previewH <= 0f) {
            return RectF(0f, 0f, binding.root.width.toFloat(), binding.root.height.toFloat())
        }
        // PreviewView и OverlayView имеют одни и те же constraint'ы — координаты совпадают.
        return RectF(0f, 0f, previewW, previewH)
    }

    private fun smoothRaw(current: PointF): PointF {
        val prev = smoothedRaw
        val alpha = 0.38f
        val smoothed = if (prev == null) {
            PointF(current.x, current.y)
        } else {
            PointF(
                prev.x * (1f - alpha) + current.x * alpha,
                prev.y * (1f - alpha) + current.y * alpha
            )
        }
        smoothedRaw = smoothed
        return smoothed
    }

    private fun addToTrail(point: PointF) {
        gazeTrail.addLast(PointF(point.x, point.y))
        while (gazeTrail.size > trailMaxSize) gazeTrail.removeFirst()
    }

    private fun updateInfo(
        gazeScreen: PointF,
        rawSmoothed: PointF,
        frame: GazeFrameResult,
        state: FixationState
    ) {
        val screenW = binding.root.width.toFloat().coerceAtLeast(1f)
        val screenH = binding.root.height.toFloat().coerceAtLeast(1f)
        val normX = (gazeScreen.x / screenW).coerceIn(0f, 1f)
        val normY = (gazeScreen.y / screenH).coerceIn(0f, 1f)

        binding.infoText.text = getString(
            R.string.info_template,
            fmt(gazeScreen.x), fmt(gazeScreen.y),
            fmt(normX), fmt(normY),
            fmt(rawSmoothed.x), fmt(rawSmoothed.y),
            fmt(frame.eulerPitchDeg), fmt(frame.eulerYawDeg), fmt(frame.eulerRollDeg)
        )

        val calibText = if (calibrator.isCalibrated) {
            val err = calibrator.calibrationErrorPx()
            if (err >= 0f) getString(R.string.status_calibrated_err, fmt(err))
            else getString(R.string.status_calibrated)
        } else {
            getString(R.string.status_uncalibrated)
        }

        val avgDur = if (state.fixationCount > 0) state.totalFixationMs / state.fixationCount else 0L
        val activeText = if (state.isFixating) getString(R.string.status_fixating, state.currentDwellMs)
        else getString(R.string.status_not_fixating)
        binding.statusText.text = getString(
            R.string.status_template,
            state.fixationCount, avgDur, state.totalFixationMs, activeText, calibText
        )
    }

    private fun updateStatus() {
        val state = FixationState(
            isFixating = false,
            currentDwellMs = 0L,
            fixationCount = fixationTracker.snapshot().size,
            totalFixationMs = fixationTracker.snapshot().sumOf { it.durationMs },
            fixations = fixationTracker.snapshot()
        )
        val calibText = if (calibrator.isCalibrated) getString(R.string.status_calibrated)
        else getString(R.string.status_uncalibrated)
        val avgDur = if (state.fixationCount > 0) state.totalFixationMs / state.fixationCount else 0L
        binding.statusText.text = getString(
            R.string.status_template,
            state.fixationCount, avgDur, state.totalFixationMs,
            getString(R.string.status_not_fixating),
            calibText
        )
    }

    private fun startCalibrationFlow() {
        if (calibrationActive) return
        gazeAnalyzer?.resetIrisBaseline()
        calibrator.beginCalibration()
        calibrationActive = true
        pendingCalibSamples.clear()
        binding.controlPanel.visibility = View.INVISIBLE
        binding.calibrationView.start(
            targets = calibrator.targets,
            holdMs = 1600L,
            onSample = { target ->
                val gx = pendingCalibSamples.takeLast(20).map { it.x }.averageOrZero()
                val gy = pendingCalibSamples.takeLast(20).map { it.y }.averageOrZero()
                calibrator.addSample(
                    target = target,
                    gazeRaw = PointF(gx, gy),
                    screenWidth = binding.root.width.toFloat(),
                    screenHeight = binding.root.height.toFloat()
                )
                pendingCalibSamples.clear()
            },
            onFinished = {
                calibrationActive = false
                binding.controlPanel.visibility = View.VISIBLE
                val ok = calibrator.finishCalibration()
                val msg = if (ok) {
                    val err = calibrator.calibrationErrorPx()
                    if (err >= 0f) getString(R.string.toast_calibration_done_err, fmt(err))
                    else getString(R.string.toast_calibration_done)
                } else {
                    getString(R.string.toast_calibration_failed)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                updateStatus()
            }
        )
    }

    private fun exportFixations() {
        val fixations = fixationTracker.snapshot()
        if (fixations.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_fixations, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = FixationExporter.saveToCache(this, fixations)
            val intent = FixationExporter.buildShareIntent(this, file)
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share_csv_title)))
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.toast_export_failed, t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    private fun List<Float>.averageOrZero(): Float = if (isEmpty()) 0f else this.sum() / size

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    private enum class AppMode { LIVE, HEATMAP }
}
