package com.nikoezveracamspiral

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.sqrt
import android.widget.Toast
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

    /** What this phone can safely do: processing budgets are sized from its memory. */
    val deviceProfile: DeviceProfile = DeviceProfile.detect(context).also {
        PhotoProcessor.configure(it.singleMaxPixels, it.burstMaxPixels)
    }

    var mode by mutableStateOf(CaptureMode.PHOTO)
        private set
    var isRawEnabled by mutableStateOf(false)
        private set
    var gridStyle by mutableStateOf(GridStyle.OFF)
    var look by mutableStateOf(Look())
    /** Imported .cube LUTs, offered under "My LUTs" in the filter panel. */
    val luts = androidx.compose.runtime.mutableStateListOf<Lut>()
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
    /** Night Sight: roughly how long to expose, 3 or 6 seconds. */
    var nightSeconds by mutableIntStateOf(3)
    var videoConfig by mutableStateOf(VideoConfig())
    var histogramEnabled by mutableStateOf(false)
    /** Zoomed shots (2x and up) are taken as a short burst and merged for detail. */
    var zoomEnhance by mutableStateOf(true)
    /** Bumped when a saved photo is rewritten in place (instant save, then the processed result),
     * so the thumbnail reloads even though its Uri is unchanged. */
    var captureRevision by mutableIntStateOf(0)
        private set
    /** The phone is resting on something (tripod): Night Sight then uses longer exposures. */
    var tripod by mutableStateOf(false)
        private set
    var aeAfLocked by mutableStateOf(false)
        private set
    /** 0..1 while a recorded video is having its filter rendered. */
    var videoProgress by mutableStateOf<Float?>(null)
        private set
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

    private val backgroundErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        // A failure on a background coroutine is reported, never allowed to take the app down.
        Log.e(TAG, "Background task failed", error)
        lastError = "Something went wrong in the background: ${error.message ?: error::class.simpleName}"
    }

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + backgroundErrors)

    /** Post-capture work runs one job at a time on its own thread: bounded memory, and the
     * shutter never waits for it. */
    private val processingScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher() + backgroundErrors)
    private var recordingTimerJob: Job? = null

    /** Whether the on-screen preview should be flipped: the front camera, when selfies are set to
     * mirror (the saved photo follows the same setting). */
    fun mirrorPreview(): Boolean =
        mirrorSelfies && engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT

    private fun prefs() = context.getSharedPreferences("never_spiral", Context.MODE_PRIVATE)

    fun changeAutoCheckUpdates(enabled: Boolean) {
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

    init {
        try {
            luts.addAll(LutStore.load(context))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load saved LUTs", e)
        }
        try {
            loadSettings()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load saved settings", e)
        }
    }

    /** Saves settings a moment after each change. Runs from the viewfinder's own coroutine (the
     * main thread, once the controller is fully built): starting this from a background thread
     * inside the constructor raced the state objects being created and crashed the app. */
    suspend fun keepSettingsSaved() {
        snapshotFlow { settingsSnapshot() }.collectLatest { snapshot ->
            delay(400)
            saveSettings(snapshot)
        }
    }

    private data class Persisted(
        val timer: Int, val grid: Int, val ratio: Int, val quality: Int, val mirror: Boolean,
        val hdr: Boolean, val hdrStrength: Int, val night: Int, val blur: Float, val raw: Boolean,
        val histogram: Boolean, val zoomEnhance: Boolean, val videoRes: Int, val videoFps: Int, val hevc: Boolean,
        val look: Look,
    )

    private fun settingsSnapshot() = Persisted(
        timerSeconds, gridStyle.ordinal, aspectRatio.ordinal, jpegQuality, mirrorSelfies,
        hdrEnabled, hdrStrength, nightSeconds, portraitBlurStrength, isRawEnabled,
        histogramEnabled, zoomEnhance, videoConfig.res.ordinal, videoConfig.fps, videoConfig.hevc, look,
    )

    private fun saveSettings(s: Persisted) {
        val l = s.look
        prefs().edit()
            .putInt("timer", s.timer).putInt("grid", s.grid).putInt("ratio", s.ratio).putInt("quality", s.quality)
            .putBoolean("mirror", s.mirror).putBoolean("hdr", s.hdr).putInt("hdrStrength", s.hdrStrength)
            .putInt("night", s.night).putFloat("blur", s.blur).putBoolean("raw", s.raw)
            .putBoolean("histogram", s.histogram).putBoolean("zoomEnhance", s.zoomEnhance)
            .putInt("videoRes", s.videoRes).putInt("videoFps", s.videoFps).putBoolean("hevc", s.hevc)
            .putString("filter", l.filter.name).putString("lut", l.lut?.name ?: "").putFloat("strength", l.strength)
            .putFloat("exposure", l.exposure).putFloat("contrast", l.contrast).putFloat("saturation", l.saturation)
            .putFloat("warmth", l.warmth).putFloat("tint", l.tint).putFloat("highlights", l.highlights)
            .putFloat("shadows", l.shadows).putFloat("vignette", l.vignette).putFloat("grain", l.grain)
            .putFloat("sharpness", l.sharpness)
            .apply()
    }

    private fun loadSettings() {
        val p = prefs()
        timerSeconds = p.getInt("timer", 0)
        gridStyle = GridStyle.entries.getOrElse(p.getInt("grid", 0)) { GridStyle.OFF }
        aspectRatio = AspectOption.entries.getOrElse(p.getInt("ratio", 0)) { AspectOption.R4_3 }
        jpegQuality = p.getInt("quality", 95)
        mirrorSelfies = p.getBoolean("mirror", true)
        hdrEnabled = p.getBoolean("hdr", true)
        hdrStrength = p.getInt("hdrStrength", 4).coerceIn(1, 5)
        nightSeconds = p.getInt("night", 3)
        portraitBlurStrength = p.getFloat("blur", 0.7f)
        isRawEnabled = p.getBoolean("raw", false)
        histogramEnabled = p.getBoolean("histogram", false)
        zoomEnhance = p.getBoolean("zoomEnhance", true)
        videoConfig = VideoConfig(
            VideoRes.entries.getOrElse(p.getInt("videoRes", 1)) { VideoRes.P1080 },
            p.getInt("videoFps", 30),
            p.getBoolean("hevc", false),
        )
        val lutName = p.getString("lut", "").orEmpty()
        look = Look(
            filter = PhotoFilter.entries.firstOrNull { it.name == p.getString("filter", "") } ?: PhotoFilter.NATURAL,
            lut = luts.firstOrNull { it.name == lutName },
            strength = p.getFloat("strength", 1f),
            exposure = p.getFloat("exposure", 0f), contrast = p.getFloat("contrast", 0f),
            saturation = p.getFloat("saturation", 0f), warmth = p.getFloat("warmth", 0f),
            tint = p.getFloat("tint", 0f), highlights = p.getFloat("highlights", 0f),
            shadows = p.getFloat("shadows", 0f), vignette = p.getFloat("vignette", 0f),
            grain = p.getFloat("grain", 0f), sharpness = p.getFloat("sharpness", 0f),
        )
    }

    // ---- steadiness (tripod) detection for Night Sight ----
    private var steadyListener: SensorEventListener? = null

    private fun startSteadyDetection() {
        if (steadyListener != null) return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val recent = ArrayDeque<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                recent.addLast(magnitude)
                if (recent.size > 40) recent.removeFirst()
                if (recent.size >= 30) {
                    val mean = recent.average().toFloat()
                    val variance = recent.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / recent.size
                    tripod = variance < 0.0008f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        steadyListener = listener
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopSteadyDetection() {
        val listener = steadyListener ?: return
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(listener)
        steadyListener = null
        tripod = false
    }

    fun toggleAeAfLock() {
        aeAfLocked = !aeAfLocked
        engine.setControlsLocked(aeAfLocked)
    }

    suspend fun importLut(uri: Uri) {
        try {
            val lut = withContext(Dispatchers.IO) { LutStore.import(context, uri) }
            luts.removeAll { it.name == lut.name }
            luts.add(lut)
            look = look.copy(lut = lut, filter = PhotoFilter.NATURAL, strength = 1f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "Couldn't import that LUT: ${e.message ?: e::class.simpleName}"
        }
    }

    fun deleteLut(lut: Lut) {
        luts.removeAll { it.name == lut.name }
        LutStore.delete(context, lut)
        if (look.lut?.name == lut.name) look = look.copy(lut = null)
    }

    fun reportError(message: String) {
        lastError = message
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
        val previous = mode
        mode = newMode
        pushProSettings()
        engine.macroMode = newMode == CaptureMode.MACRO
        if (newMode == CaptureMode.MACRO) {
            // Macro uses the ultra-wide lens, which focuses much closer than the main one.
            if (engine.minZoomRatio() < 1f) {
                setZoom(engine.minZoomRatio())
            } else {
                Toast.makeText(context, "No ultra-wide lens on this camera: close focus is limited", Toast.LENGTH_SHORT).show()
            }
        } else if (previous == CaptureMode.MACRO) {
            setZoom(1f)
        }
        engine.nightPreviewBoost = newMode == CaptureMode.NIGHT
        if (newMode == CaptureMode.NIGHT) startSteadyDetection() else stopSteadyDetection()
        engine.startRepeatingPreview()
    }

    /** [applyTransform] is invoked once the camera is open (so [CameraEngine.lensFacing] and
     * [CameraEngine.sensorOrientation] are both known) with the preview size actually chosen,
     * so the caller can scale its TextureView correctly -- and again from [switchCamera], since
     * the front and back cameras can support different preview sizes. */
    /** True from the moment [start] begins until [stop]; guards against opening twice when both the
     * surface callback and the lifecycle ask for the camera, and tells the lifecycle when to reopen. */
    var cameraStarted = false
        private set

    suspend fun start(cameraId: String, texture: SurfaceTexture, applyTransform: (Size) -> Unit) {
        if (cameraStarted) return
        cameraStarted = true
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
            cameraStarted = false
            try { engine.closeCamera(); engine.stopBackgroundThread() } catch (_: Exception) {}
            fail("Opening camera", e)
        }
    }

    fun stop() {
        cameraStarted = false
        stopSteadyDetection()
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
        zoomRatio = ratio.coerceIn(engine.minZoomRatio(), engine.maxZoomRatio().coerceAtLeast(1f))
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
        val target = !isRawEnabled
        val texture = currentTexture
        val previewSize = currentPreviewSize
        if (texture == null || previewSize == null || isRecording) {
            // No open camera to reconfigure right now: remember the choice; the next session
            // start picks it up.
            isRawEnabled = target
            return
        }
        try {
            engine.startPreviewSession(texture, previewSize, target)
            isRawEnabled = target
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
        if (pendingProcessing >= 3) {
            Toast.makeText(context, "Still processing your last shots", Toast.LENGTH_SHORT).show()
            return
        }
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
                    // 3 s: twelve frames of up to 1/6 s; 6 s: sixteen frames of up to 1/3 s.
                    // On a tripod there is no shake to freeze, so frames can be far longer.
                    val long = nightSeconds >= 6
                    val onTripod = tripod
                    val frame = when {
                        onTripod && long -> engine.nightExposure(base, 12, 1_000_000_000L)
                        onTripod -> engine.nightExposure(base, 8, 500_000_000L)
                        long -> engine.nightExposure(base, 6, 330_000_000L)
                        else -> engine.nightExposure(base, 4, 160_000_000L)
                    }
                    val frameCount = when {
                        onTripod -> 6
                        long -> 16
                        else -> 12
                    }
                    exposures = List(frameCount) { frame }
                    tone = PhotoProcessor.ToneMode.Night
                    holdStillMs = exposures.size * (frame.first / 1_000_000L + 40L)
                    Toast.makeText(context, "Hold your phone steady", Toast.LENGTH_SHORT).show()
                }
                hdrEnabled && !manualExposure && !flashActive && !mode.isVideo && base != null -> {
                    exposures = List(4) { engine.bracketExposure(base, underEv) }
                    tone = PhotoProcessor.ToneMode.HdrPlus(strength, underEv)
                }
                zoomEnhance && zoomRatio >= 2f && !manualExposure && !flashActive && !mode.isVideo -> {
                    // Zoomed in: several normal frames merged for detail instead of one soft one.
                    exposures = List(5) { null }
                    tone = PhotoProcessor.ToneMode.Stack
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
                    withContext(Dispatchers.IO) { CaptureStore.saveDng(
                        context, raw, burst.characteristics, result, stamp,
                        orientationDegrees = engine.sensorToJpegOrientation(rotationDegrees),
                        thumbnailJpeg = burst.jpegs.firstOrNull(),
                    ) }
                } else {
                    raw.close()
                }
            }

            val first = burst.firstResult
            val summary = when {
                night -> "Night Sight, ${exposures.size} frames"
                tone is PhotoProcessor.ToneMode.HdrPlus -> "HDR+, ${exposures.size} frames"
                mode == CaptureMode.PORTRAIT -> "Portrait"
                mode == CaptureMode.MACRO -> "Macro"
                else -> "Standard"
            }
            val meta = PhotoMeta(
                exposureNanos = first?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = first?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
                aperture = first?.get(android.hardware.camera2.CaptureResult.LENS_APERTURE),
                focalLengthMm = first?.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH),
                software = "Niko's Cam ${UpdateManager.installedVersionName(context)}",
                description = "Quality ${jpegQuality}; $summary; ${look.lut?.name ?: look.filter.label}; " +
                    "zoom ${String.format(java.util.Locale.US, "%.1f", zoomRatio)}x; lens ${first?.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: "-"}",
            )
            val ratioNow = aspectRatio
            val mirrorNow = mirrorSelfies && engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            val blurNow = if (mode == CaptureMode.PORTRAIT) portraitBlurStrength else 0f
            val sensorIsFourThree = kotlin.math.abs(engine.captureAspect() - 4f / 3f) < 0.02f
            // The camera's own JPEG is saved immediately, so a photo exists even if the app is
            // closed or killed while the rest is still processing.
            val instant = withContext(Dispatchers.IO) { CaptureStore.saveJpeg(context, burst.jpegs[burst.jpegs.size / 2], stamp, meta = meta) }
            if (instant != null) {
                lastCaptureUri = instant
                lastCaptureIsVideo = false
                captureRevision += 1
            }
            val needsProcessing = tone != PhotoProcessor.ToneMode.None || !look.isNeutral || zoomRatio >= 2f || mirrorNow || blurNow > 0f ||
                !(ratioNow == AspectOption.R4_3 && sensorIsFourThree)
            if (instant == null || !needsProcessing) return
            val job = PhotoJob(
                uri = instant,
                frames = burst.jpegs,
                tone = tone,
                look = look,
                ratio = ratioNow,
                mirror = mirrorNow,
                quality = jpegQuality,
                portraitBlur = blurNow,
                stamp = stamp,
                meta = meta,
                noiseScale = sqrt((meta.iso ?: 400) / 400f).coerceIn(0.8f, 2.2f),
                zoomRatio = zoomRatio,
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
        val uri: Uri,
        val frames: List<ByteArray>,
        val tone: PhotoProcessor.ToneMode,
        val look: Look,
        val ratio: AspectOption,
        val mirror: Boolean,
        val quality: Int,
        val portraitBlur: Float,
        val stamp: String,
        val meta: PhotoMeta,
        val noiseScale: Float,
        val zoomRatio: Float,
    )

    private suspend fun runPhotoJob(job: PhotoJob) {
        try {
            // On any failure the camera's own JPEG, already saved, simply stays as the photo.
            val jpeg = try {
                PhotoProcessor.processShots(job.frames, job.tone, job.look, job.ratio, job.mirror, job.quality, job.portraitBlur, job.noiseScale, job.zoomRatio)
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed; keeping the camera's own JPEG", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Processing ran out of memory; keeping the camera's own JPEG", e)
                null
            }
            if (jpeg != null && CaptureStore.overwriteJpeg(context, job.uri, jpeg, job.meta)) {
                lastCaptureUri = job.uri
                lastCaptureIsVideo = false
                captureRevision += 1
            }
        } catch (e: Exception) {
            fail("Saving photo", e)
        } finally {
            adjustPending(-1)
        }
    }

    private fun adjustPending(delta: Int) {
        val change: Int
        synchronized(this) {
            val before = pendingProcessing
            pendingProcessing += delta
            change = when {
                before == 0 && pendingProcessing > 0 -> 1
                before > 0 && pendingProcessing == 0 -> -1
                else -> 0
            }
        }
        try {
            val intent = Intent(context, ProcessingService::class.java)
            if (change > 0) ContextCompat.startForegroundService(context, intent)
            if (change < 0) context.stopService(intent)
        } catch (e: Exception) {
            // The service only protects the process from being reclaimed; losing it isn't fatal.
            Log.w(TAG, "Processing service change failed", e)
        }
    }

    suspend fun startVideo(rotationDegrees: Int) {
        if (isRecording || isCapturing) return
        try {
            val file = CaptureStore.newVideoCacheFile(context)
            videoFile = file
            val hasMic = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasMic) lastError = "Microphone isn't allowed, so this video has no sound"
            engine.startVideoRecording(file, rotationDegrees, hasMic, videoConfig)
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
                // Save the recording straight away so it can never be lost, then (if a look is
                // active) render the filtered version in the background and swap it in.
                val originalUri = withContext(Dispatchers.IO) {
                    if (VideoFilter.needed(videoLook)) CaptureStore.publishVideoCopy(context, file)
                    else CaptureStore.publishVideo(context, file)
                }
                if (originalUri != null) {
                    lastCaptureUri = originalUri
                    lastCaptureIsVideo = true
                    captureRevision++
                } else {
                    lastError = "Couldn't save the video"
                }
                if (originalUri != null && VideoFilter.needed(videoLook)) {
                    adjustPending(1)
                    processingScope.launch {
                        try {
                            val filtered = try {
                                VideoFilter.apply(context, file, videoLook) { videoProgress = it }
                            } catch (e: Exception) {
                                Log.e(TAG, "Video filter failed; keeping the unfiltered recording", e)
                                lastError = "Couldn't apply the filter to the video: ${e.message ?: e::class.simpleName}"
                                null
                            }
                            if (filtered != null && filtered.length() > 0) {
                                val uri = CaptureStore.publishVideo(context, filtered, file.name)
                                if (uri != null) {
                                    CaptureStore.deleteMedia(context, originalUri)
                                    if (lastCaptureUri == originalUri) {
                                        lastCaptureUri = uri
                                        lastCaptureIsVideo = true
                                        captureRevision++
                                    }
                                }
                            }
                        } finally {
                            file.delete()
                            videoProgress = null
                            adjustPending(-1)
                        }
                    }
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
