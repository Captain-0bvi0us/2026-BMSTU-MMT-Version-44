package com.example.variant44gaze

import androidx.camera.core.ImageAnalysis

interface FrameGazeAnalyzer : ImageAnalysis.Analyzer {
    fun release()
}
