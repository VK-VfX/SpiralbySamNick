package com.nikoezveracamspiral

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CameraController"

/**
 * Orchestrates [CameraEngine] for the UI layer: owns every piece of Compose-observable session
 * state (mode, Pro settings, zoom, flash, recording state, last capture) that [CameraScreen]
 * reads and writes directly -- a plain class holding mutableStateOf properties, read straight
 * from Compose, rather than an AndroidViewModel this single-Activity app has no real need for.
 *
 * Every suspend action below catches its own failures into [lastError] instead of letting them
 * propagate: these are all launched fire-and-forget from `scope.launch { ... }` call sites in
 * [CameraScreen] with no exception handler of their own, so an uncaught exception here -- a real
 * camera being busy, a session reconfiguration racing a mode switch, any of the ordinary ways a
 * physical device's camera stack can fail -- would otherwise crash the whole app instead of
 * surfacing as a recoverable, visible error.
 */
class CameraController(private val context: Context) {
    val engine = CameraEngine(context)

    var mode by mutableStateOf(CaptureMode.PHOTO)
        private set
    var isRawEnabled by mutableStateOf(false)
        private set
    var gridStyle by mutableStateOf(GridStyle.OFF)
    var photoStyle by mutableStateOf(PhotoStyle.NATURAL)
    var hdrLevel by mutableIntStateOf(3)
    var aspectRatio by mutableStateOf(AspectOption.R4_3)
    var mirrorSelfies by mutableStateOf(true)
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
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Bumped every time a camera finishes opening, so UI that depends on what that camera can do
     * (RAW support, zoom range, manual-control ranges) re-reads it -- [engine]'s capability
     * getters aren't observable state themselves. */
    var cameraEpoch by mutableIntStateOf(0)
        private set

    /** Bumped at the instant a still capture is triggered; [CameraScreen] plays its shutter
     * blink off each change. */
    var shutterTick by mutableIntStateOf(0)
        private set

    private var currentTexture: SurfaceTexture? = null
    private var currentPreviewSize: Size? = null
    private var applyTransform: ((Size) -> Unit)? = null
    private var videoFile: File? = null

    /** The preview resolution actually streaming, so the UI can re-apply its TextureView
     * transform when the view is resized. */
    val previewSize: Size? get() = currentPreviewSize

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingTimerJob: Job? = null

    /** Whether the on-screen preview should be flipped: the front camera, when selfies are set to
     * mirror (the saved photo follows the same setting). */
    fun mirrorPreview(): Boolean =
        mirrorSelfies && engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT

    fun clearError() {
        lastError = null
    }

    private fun fail(action: String, e: Exception) {
        Log.e(TAG, "$action failed", e)
        lastError = "$action failed: ${e.message ?: e::class.simpleName}"
    }

    /** Manual exposure/focus only apply in Pro mode; every other mode runs fully automatic even
     * if Pro's toggles were left on, while the stored values survive for when Pro is reselected. */
    private fun pushProSettings() {
        val effective = if (mode == CaptureMode.PRO) {
            proSettings
        } else {
            proSettings.copy(manualExposureEnabled = false, manualFocusEnabled = false)
        }
        engine.updateProSettings { effective }
    }

    fun selectMode(newMode: CaptureMode) {
        if (isRecording || newMode == mode) return
        mode = newMode
        pushProSettings()
    }

    /** [applyTransform] is invoked once the camera is open (so [CameraEngine.lensFacing] and
     * [CameraEngine.sensorOrientation] are both known) with the preview size actually chosen,
     * so the caller can scale its TextureView correctly -- and again from [switchCamera], since
     * the front and back cameras can support different preview sizes. */
    suspend fun start(cameraId: String, texture: SurfaceTexture, applyTransform: (Size) -> Unit) {
        try {
            currentTexture = texture
            this.applyTransform = applyTransform
            engine.startBackgroundThread()
            engine.openCamera(cameraId)
            val previewSize = engine.choosePreviewSize(engine.captureAspect())
            currentPreviewSize = previewSize
            applyTransform(previewSize)
            engine.startPreviewSession(texture, previewSize, isRawEnabled)
            engine.setFlashMode(flashMode)
            pushProSettings()
            cameraEpoch += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Opening camera", e)
        }
    }

    fun stop() {
        recordingTimerJob?.cancel()
        engine.closeCamera()
        engine.stopBackgroundThread()
    }

    suspend fun switchCamera() {
        val texture = currentTexture ?: return
        try {
            val targetFacing = if (engine.lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val targetId = engine.findCameraId(targetFacing) ?: return
            engine.closeCamera()
            engine.openCamera(targetId)
            val previewSize = engine.choosePreviewSize(engine.captureAspect())
            currentPreviewSize = previewSize
            applyTransform?.invoke(previewSize)
            engine.resetZoom()
            zoomRatio = 1f
            engine.startPreviewSession(texture, previewSize, isRawEnabled)
            cameraEpoch += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Switching camera", e)
        }
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

    fun updateProSettings(update: (ProSettings) -> ProSettings) {
        proSettings = update(proSettings)
        pushProSettings()
    }

    /** Takes effect immediately by restarting just the capture session (not the whole camera
     * device), adding or dropping the RAW ImageReader from its target surfaces. */
    suspend fun toggleRaw() {
        isRawEnabled = !isRawEnabled
        val texture = currentTexture ?: return
        val previewSize = currentPreviewSize ?: return
        if (isRecording) return
        try {
            engine.startPreviewSession(texture, previewSize, isRawEnabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Toggling RAW", e)
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
        shutterTick += 1
        try {
            val captured = engine.capturePhoto(rotationDegrees, isRawEnabled)
            val stamp = CaptureStore.newStamp()
            val style = photoStyle
            val hdr = hdrLevel
            val ratio = aspectRatio
            val mirror = mirrorSelfies && engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            val jpeg = withContext(Dispatchers.Default) {
                try {
                    PhotoProcessor.process(captured.jpegBytes, style, hdr, ratio, mirror)
                } catch (e: Exception) {
                    Log.e(TAG, "Post-processing failed; saving the camera's original JPEG", e)
                    captured.jpegBytes
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Post-processing ran out of memory; saving the camera's original JPEG", e)
                    captured.jpegBytes
                }
            }
            lastCaptureUri = withContext(Dispatchers.IO) { CaptureStore.saveJpeg(context, jpeg, stamp) }
            captured.rawImage?.let { raw ->
                withContext(Dispatchers.IO) { CaptureStore.saveDng(context, raw, captured.characteristics, captured.captureResult, stamp) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Taking photo", e)
        } finally {
            isCapturing = false
            countdownRemaining = 0
        }
    }

    suspend fun startVideo(rotationDegrees: Int) {
        if (isRecording || isCapturing) return
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Starting recording", e)
            videoFile = null
            isRecording = false
        }
    }

    suspend fun stopVideo() {
        if (!isRecording) return
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Stopping recording", e)
            isRecording = false
        }
    }
}
