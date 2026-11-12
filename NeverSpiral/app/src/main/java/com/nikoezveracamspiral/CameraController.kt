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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
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
    var look by mutableStateOf(Look())
    var hdrEnabled by mutableStateOf(true)
    var hdrStrength by mutableIntStateOf(4)
    var jpegQuality by mutableIntStateOf(95)
    var lastCaptureIsVideo by mutableStateOf(false)
        private set
    /** Photos and videos still being processed in the background. */
    var pendingProcessing by mutableIntStateOf(0)
        private set
    /** While Night Sight is exposing: how long the user should hold still, in ms. */
    var holdStillMs by mutableLongStateOf(0L)
        private set
    var portraitBlurStrength by mutableStateOf(0.7f)
    var update by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set
    var autoCheckUpdates by mutableStateOf(prefs().getBoolean("autoCheckUpdates", true))
        private set
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

    /** Post-capture work runs one job at a time on its own thread: bounded memory, and the
     * shutter never waits for it. */
    private val processingScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private var recordingTimerJob: Job? = null

    /** Whether the on-screen preview should be flipped: the front camera, when selfies are set to
     * mirror (the saved photo follows the same setting). */
    fun mirrorPreview(): Boolean =
        mirrorSelfies && engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT

    private fun prefs() = context.getSharedPreferences("never_spiral", Context.MODE_PRIVATE)

    fun setAutoCheckUpdates(enabled: Boolean) {
        autoCheckUpdates = enabled
        prefs().edit().putBoolean("autoCheckUpdates", enabled).apply()
    }

    /** [silent] checks (at launch) only surface a result when an update exists. */
    suspend fun checkForUpdates(silent: Boolean = false) {
        if (update is UpdateStatus.Checking || update is UpdateStatus.Downloading) return
        if (!silent) update = UpdateStatus.Checking
        try {
            val info = UpdateManager.check(context)
            update = if (info != null) UpdateStatus.Available(info) else if (silent) UpdateStatus.Idle else UpdateStatus.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update = if (silent) UpdateStatus.Idle else UpdateStatus.Failed("Couldn't check for updates: ${e.message ?: e::class.simpleName}")
        }
    }

    suspend fun downloadUpdate() {
        val info = (update as? UpdateStatus.Available)?.info ?: return
        update = UpdateStatus.Downloading(info, 0f)
        try {
            val file = UpdateManager.download(context, info) { progress -> update = UpdateStatus.Downloading(info, progress) }
            update = UpdateStatus.Ready(info, file)
            UpdateManager.install(context, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update = UpdateStatus.Failed("Update download failed: ${e.message ?: e::class.simpleName}")
        }
    }

    fun installUpdate() {
        val ready = update as? UpdateStatus.Ready ?: return
        try {
            UpdateManager.install(context, ready.file)
        } catch (e: Exception) {
            update = UpdateStatus.Failed("Couldn't open the installer: ${e.message ?: e::class.simpleName}")
        }
    }

    fun dismissUpdateStatus() {
        update = UpdateStatus.Idle
    }

    /** Called by the editor after it writes a new photo, so the thumbnail follows. */
    fun publishCapture(uri: Uri) {
        lastCaptureUri = uri
        lastCaptureIsVideo = false
    }

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
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
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

    /**
     * The shutter. Like Pixel's camera, this only does the quick half: it fires the burst the
     * chosen mode needs (one frame; four underexposed frames for HDR+; nine long exposures for
     * Night) and returns, so the next shot can start at once. Aligning, merging, tone mapping,
     * portrait blur, the look and the save all happen afterwards on a background queue.
     */
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
            val night = mode == CaptureMode.NIGHT
            val manualExposure = mode == CaptureMode.PRO && proSettings.manualExposureEnabled
            val flashActive = flashMode != FlashMode.OFF && !night
            val base = engine.currentAutoExposure()
            val strength = hdrStrength / 5f
            val underEv = -(0.5f + 0.15f * hdrStrength)
            val exposures: List<Pair<Long, Int>?>
            val tone: PhotoProcessor.ToneMode
            when {
                night && base != null -> {
                    val range = engine.exposureTimeRange
                    var time = minOf(base.first * 4, 160_000_000L)
                    if (range != null) time = time.coerceIn(range.lower, range.upper)
                    exposures = List(9) { time to base.second }
                    tone = PhotoProcessor.ToneMode.Night
                    holdStillMs = exposures.size * (time / 1_000_000L + 40L)
                }
                hdrEnabled && !manualExposure && !flashActive && !mode.isVideo && base != null -> {
                    exposures = List(4) { engine.bracketExposure(base, underEv) }
                    tone = PhotoProcessor.ToneMode.HdrPlus(strength, underEv)
                }
                else -> {
                    exposures = listOf(null)
                    tone = PhotoProcessor.ToneMode.None
                }
            }

            val burst = withTimeout(15_000) {
                engine.captureBurst(rotationDegrees, exposures, if (isRawEnabled) exposures.size / 2 else null)
            }
            val stamp = CaptureStore.newStamp()

            // RAW (if on) has to be written while its buffer is still ours, so that part isn't
            // deferred.
            burst.rawImage?.let { raw ->
                val result = burst.rawResult
                if (result != null) {
                    withContext(Dispatchers.IO) { CaptureStore.saveDng(context, raw, burst.characteristics, result, stamp) }
                } else {
                    raw.close()
                }
            }

            val job = PhotoJob(
                frames = burst.jpegs,
                tone = tone,
                look = look,
                ratio = aspectRatio,
                mirror = mirrorSelfies && engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                quality = jpegQuality,
                portraitBlur = if (mode == CaptureMode.PORTRAIT) portraitBlurStrength else 0f,
                stamp = stamp,
            )
            adjustPending(1)
            processingScope.launch { runPhotoJob(job) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            fail("Taking photo", RuntimeException("the camera didn't deliver its frames in time"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Taking photo", e)
        } finally {
            isCapturing = false
            countdownRemaining = 0
            holdStillMs = 0L
        }
    }

    private class PhotoJob(
        val frames: List<ByteArray>,
        val tone: PhotoProcessor.ToneMode,
        val look: Look,
        val ratio: AspectOption,
        val mirror: Boolean,
        val quality: Int,
        val portraitBlur: Float,
        val stamp: String,
    )

    private suspend fun runPhotoJob(job: PhotoJob) {
        try {
            val fallback = job.frames[job.frames.size / 2]
            val jpeg = try {
                PhotoProcessor.processShots(job.frames, job.tone, job.look, job.ratio, job.mirror, job.quality, job.portraitBlur)
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed; saving the camera's own JPEG", e)
                fallback
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Processing ran out of memory; saving the camera's own JPEG", e)
                fallback
            }
            val uri = CaptureStore.saveJpeg(context, jpeg, job.stamp)
            if (uri != null) {
                lastCaptureUri = uri
                lastCaptureIsVideo = false
            }
        } catch (e: Exception) {
            fail("Saving photo", e)
        } finally {
            adjustPending(-1)
        }
    }

    private fun adjustPending(delta: Int) {
        synchronized(this) { pendingProcessing += delta }
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
                val videoLook = look
                if (VideoFilter.needed(videoLook)) {
                    // Rendered after recording, in the background; the unfiltered file is
                    // published instead if that fails.
                    adjustPending(1)
                    processingScope.launch {
                        try {
                            val displayName = file.name
                            val filtered = try {
                                VideoFilter.apply(context, file, videoLook)
                            } catch (e: Exception) {
                                Log.e(TAG, "Video filter failed; keeping the unfiltered recording", e)
                                lastError = "Couldn't apply the filter to the video: ${e.message ?: e::class.simpleName}"
                                null
                            }
                            val source = filtered ?: file
                            val uri = CaptureStore.publishVideo(context, source, displayName)
                            if (filtered != null) file.delete()
                            if (uri != null) {
                                lastCaptureUri = uri
                                lastCaptureIsVideo = true
                            }
                        } finally {
                            adjustPending(-1)
                        }
                    }
                } else {
                    lastCaptureUri = CaptureStore.publishVideo(context, file)
                    lastCaptureIsVideo = true
                }
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
