package com.nikoezveracamspiral

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Orchestrates [CameraEngine] for the UI layer: owns every piece of Compose-observable session
 * state (mode, Pro settings, zoom, flash, recording state, last capture) that [CameraScreen]
 * reads and writes directly -- the same "a plain class holding mutableStateOf properties, read
 * straight from Compose" shape [VuMeterEngine] and the other visualizer engines already used,
 * rather than an AndroidViewModel this single-Activity app has no real need for.
 */
class CameraController(private val context: Context) {
    val engine = CameraEngine(context)

    var mode by mutableStateOf(CaptureMode.PHOTO)
    var isRawEnabled by mutableStateOf(false)
        private set
    var isGridEnabled by mutableStateOf(false)
    var timerSeconds by mutableStateOf(0)
    var flashMode by mutableStateOf(FlashMode.OFF)
        private set
    var zoomRatio by mutableStateOf(1f)
        private set
    var proSettings by mutableStateOf(ProSettings())
        private set
    var isRecording by mutableStateOf(false)
        private set
    var recordingElapsedMs by mutableLongStateOf(0L)
        private set
    var lastCaptureUri by mutableStateOf<Uri?>(null)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    var countdownRemaining by mutableStateOf(0)
        private set

    private var currentTexture: SurfaceTexture? = null
    private var currentPreviewSize: Size? = null
    private var videoFile: File? = null

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingTimerJob: Job? = null

    suspend fun start(cameraId: String, texture: SurfaceTexture, previewSize: Size) {
        currentTexture = texture
        currentPreviewSize = previewSize
        engine.startBackgroundThread()
        engine.openCamera(cameraId)
        engine.startPreviewSession(texture, previewSize, isRawEnabled)
        engine.setFlashMode(flashMode)
        engine.updateProSettings { proSettings }
    }

    fun stop() {
        recordingTimerJob?.cancel()
        engine.closeCamera()
        engine.stopBackgroundThread()
    }

    suspend fun switchCamera() {
        val texture = currentTexture ?: return
        val previewSize = currentPreviewSize ?: return
        val targetFacing = if (engine.lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val targetId = engine.findCameraId(targetFacing) ?: return
        engine.closeCamera()
        engine.openCamera(targetId)
        engine.startPreviewSession(texture, previewSize, isRawEnabled)
        zoomRatio = 1f
    }

    fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(1f, engine.maxZoomRatio().coerceAtLeast(1f))
        engine.setZoom(zoomRatio)
    }

    fun cycleFlash() {
        flashMode = when (flashMode) {
            FlashMode.OFF -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        engine.setFlashMode(flashMode)
    }

    fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
    }

    fun updateProSettings(update: (ProSettings) -> ProSettings) {
        proSettings = update(proSettings)
        engine.updateProSettings { proSettings }
    }

    /** Takes effect immediately by restarting just the capture session (not the whole camera
     * device), adding or dropping the RAW ImageReader from its target surfaces. */
    suspend fun toggleRaw() {
        isRawEnabled = !isRawEnabled
        val texture = currentTexture ?: return
        val previewSize = currentPreviewSize ?: return
        if (!isRecording) {
            engine.startPreviewSession(texture, previewSize, isRawEnabled)
        }
    }

    suspend fun capturePhoto(rotationDegrees: Int) {
        if (isCapturing || isRecording) return
        if (timerSeconds > 0) {
            countdownRemaining = timerSeconds
            while (countdownRemaining > 0) {
                delay(1000)
                countdownRemaining -= 1
            }
        }
        isCapturing = true
        try {
            val captured = engine.capturePhoto(rotationDegrees, isRawEnabled)
            lastCaptureUri = CaptureStore.saveJpeg(context, captured.jpegBytes)
            captured.rawImage?.let { raw ->
                CaptureStore.saveDng(context, raw, captured.characteristics, captured.captureResult)
            }
        } finally {
            isCapturing = false
        }
    }

    suspend fun startVideo(rotationDegrees: Int) {
        if (isRecording || isCapturing) return
        val file = CaptureStore.newVideoCacheFile(context)
        videoFile = file
        engine.startVideoRecording(file, rotationDegrees)
        isRecording = true
        recordingElapsedMs = 0L
        recordingTimerJob = controllerScope.launch {
            while (isRecording) {
                delay(1000)
                recordingElapsedMs += 1000
            }
        }
    }

    suspend fun stopVideo() {
        if (!isRecording) return
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        engine.stopVideoRecording()
        isRecording = false

        val file = videoFile
        videoFile = null
        if (file != null) {
            lastCaptureUri = CaptureStore.publishVideo(context, file)
        }

        val texture = currentTexture
        val previewSize = currentPreviewSize
        if (texture != null && previewSize != null) {
            engine.startPreviewSession(texture, previewSize, isRawEnabled)
        }
    }
}
