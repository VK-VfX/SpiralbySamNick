package com.nikoezveracamspiral

import android.content.Context
import android.media.Image
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
    /** Set by the viewfinder: a small copy of the live picture at the given width (for Portrait's depth from focus). */
    @Volatile
    var previewGrabber: ((Int) -> android.graphics.Bitmap?)? = null
    var portraitBlurStrength by mutableStateOf(0.5f)
    /** How Portrait mode treats the background (Natural, Studio, Soft, Bokeh, Stage, Mono). */
    var portraitStyle by mutableStateOf(PortraitStyle.NATURAL)
    /** Night Sight: roughly how long to expose, 3 or 6 seconds. */
    var nightSeconds by mutableIntStateOf(3)
    var videoConfig by mutableStateOf(VideoConfig())
    var histogramEnabled by mutableStateOf(false)
    /** Zoomed shots (2x and up) are taken as a short burst and merged for detail. */
    var zoomEnhance by mutableStateOf(true)
    /** Photos keep an HDR gain map (Ultra HDR) where the camera offers it. */
    var ultraHdrEnabled by mutableStateOf(true)
    /** Night mode uses Google's own Night Sight (the system extension) rather than this app's. */
    var nightGoogle by mutableStateOf(false)
    /** Whether the open camera offers Google's Night extension. */
    var googleNightAvailable by mutableStateOf(false)
        private set
    /** Bumped when the lens changes (camera flip, a jump between zoom stops) so the screen can
     * play its transition. */
    var lensSwitchTick by mutableIntStateOf(0)
        private set
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
        val ultraHdr: Boolean, val nightGoogle: Boolean, val portraitStyle: Int,
        val look: Look,
    )

    private fun settingsSnapshot() = Persisted(
        timerSeconds, gridStyle.ordinal, aspectRatio.ordinal, jpegQuality, mirrorSelfies,
        hdrEnabled, hdrStrength, nightSeconds, portraitBlurStrength, isRawEnabled,
        histogramEnabled, zoomEnhance, videoConfig.res.ordinal, videoConfig.fps, videoConfig.hevc,
        ultraHdrEnabled, nightGoogle, portraitStyle.ordinal, look,
    )

    private fun saveSettings(s: Persisted) {
        val l = s.look
        prefs().edit()
            .putInt("timer", s.timer).putInt("grid", s.grid).putInt("ratio", s.ratio).putInt("quality", s.quality)
            .putBoolean("mirror", s.mirror).putBoolean("hdr", s.hdr).putInt("hdrStrength", s.hdrStrength)
            .putInt("night", s.night).putFloat("blur", s.blur).putBoolean("raw", s.raw)
            .putBoolean("histogram", s.histogram).putBoolean("zoomEnhance", s.zoomEnhance)
            .putInt("videoRes", s.videoRes).putInt("videoFps", s.videoFps).putBoolean("hevc", s.hevc)
            .putBoolean("ultraHdr", s.ultraHdr).putBoolean("nightGoogle", s.nightGoogle).putInt("portraitStyle", s.portraitStyle)
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
        portraitBlurStrength = p.getFloat("blur", 0.5f)
        portraitStyle = PortraitStyle.entries.getOrElse(p.getInt("portraitStyle", 0)) { PortraitStyle.NATURAL }
        isRawEnabled = p.getBoolean("raw", false)
        histogramEnabled = p.getBoolean("histogram", false)
        zoomEnhance = p.getBoolean("zoomEnhance", true)
        ultraHdrEnabled = p.getBoolean("ultraHdr", true)
        nightGoogle = p.getBoolean("nightGoogle", false)
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
        if (newMode == CaptureMode.PORTRAIT || previous == CaptureMode.PORTRAIT) {
            // Portrait takes the ultra-wide lens's view with each shot, which needs its own stream.
            engine.stereoWanted = newMode == CaptureMode.PORTRAIT
            if (!engine.extensionActive) restartStillSession()
        }
        if (newMode == CaptureMode.NIGHT) startSteadyDetection() else stopSteadyDetection()
        engine.startRepeatingPreview()
        if (newMode == CaptureMode.NIGHT || previous == CaptureMode.NIGHT) applyNightEngine()
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
            engine.ultraHdrWanted = ultraHdrEnabled
            engine.rawFrameCapacity = rawFramesFor(deviceProfile.tier)
            engine.startPreviewSession(texture, previewSize, isRawEnabled)
            afterSessionStarted()
            engine.setFlashMode(flashMode)
            pushProSettings()
            cameraEpoch += 1
            applyNightEngine()
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
            lensSwitchTick += 1
            engine.closeCamera()
            engine.openCamera(targetId)
            val previewSize = engine.choosePreviewSize(engine.captureAspect())
            currentPreviewSize = previewSize
            applyTransform?.invoke(previewSize)
            engine.resetZoom()
            zoomRatio = 1f
            engine.startPreviewSession(texture, previewSize, isRawEnabled)
            afterSessionStarted()
            cameraEpoch += 1
            applyNightEngine()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Switching camera", e)
        }
    }

    fun setZoom(ratio: Float) {
        val wasBelowOne = zoomRatio < 0.99f
        val wasStereoRange = zoomRatio in 0.99f..1.05f
        zoomRatio = ratio.coerceIn(engine.minZoomRatio(), engine.maxZoomRatio().coerceAtLeast(1f))
        engine.setZoom(zoomRatio)
        // RAW below 1x comes from the ultra-wide lens itself, which needs its own stream, so the
        // session is rebuilt when zoom crosses 1x with RAW on.
        val rawChange = wasBelowOne != (zoomRatio < 0.99f) && isRawEnabled
        // Portrait's two-lens depth only works at 1x, where the main lens is the one in use.
        val stereoChange = mode == CaptureMode.PORTRAIT && wasStereoRange != (zoomRatio in 0.99f..1.05f)
        if ((rawChange || stereoChange) && !engine.extensionActive && !isRecording) {
            restartStillSession()
        }
    }

    /** A jump to a zoom stop (a chip tap or the zoom dial's end): plays the lens transition, then zooms. */
    fun jumpZoom(ratio: Float) {
        lensSwitchTick += 1
        setZoom(ratio)
    }

    /** Frames a RAW merge may use on this phone: more on phones with memory to spare. */
    private fun rawFramesFor(tier: PerformanceTier): Int = when (tier) {
        PerformanceTier.HIGH -> 5
        PerformanceTier.MID -> 4
        PerformanceTier.LOW -> 1
    }

    /** True while a capture session is being rebuilt (a lens or format change); the shutter waits for it. */
    @Volatile
    private var sessionRestarting = false
    private var restartRequested = false
    private var restartRunning = false

    /** Rebuilds the capture session in the background, coalescing quick repeats (a pinch that
     * crosses 1x several times) into one rebuild after another. */
    private fun restartStillSession() {
        synchronized(this) {
            restartRequested = true
            if (restartRunning) return
            restartRunning = true
        }
        controllerScope.launch {
            try {
                while (true) {
                    val go = synchronized(this@CameraController) {
                        val requested = restartRequested
                        restartRequested = false
                        requested
                    }
                    if (!go) break
                    val texture = currentTexture
                    val size = currentPreviewSize
                    if (texture == null || size == null || isRecording || !cameraStarted || engine.extensionActive) continue
                    sessionRestarting = true
                    try {
                        engine.startPreviewSession(texture, size, isRawEnabled)
                        afterSessionStarted()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fail("Switching lens", e)
                    } finally {
                        sessionRestarting = false
                    }
                }
            } finally {
                synchronized(this@CameraController) { restartRunning = false }
            }
        }
    }

    /** Reads what the new session turned out to support, and passes on any note about a dropped feature. */
    private fun afterSessionStarted() {
        googleNightAvailable = engine.extensionNightSupported()
        engine.sessionNote?.let { note ->
            engine.sessionNote = null
            lastError = note
        }
    }

    fun setUltraHdr(enabled: Boolean) {
        ultraHdrEnabled = enabled
        engine.ultraHdrWanted = enabled
        restartStillSession()
    }

    /** Exposure compensation (the focus ring's sun slider), clamped to what the camera allows. */
    fun setExposureIndex(index: Int) {
        val range = engine.exposureCompensationRange ?: return
        updateProSettings { it.copy(exposureCompensation = index.coerceIn(range.lower, range.upper)) }
    }

    // ---- Google's own Night Sight (system extension) ----

    private val nightLock = kotlinx.coroutines.sync.Mutex()

    fun changeNightGoogle(on: Boolean) {
        nightGoogle = on
        applyNightEngine()
    }

    /** Makes the open session match the mode: the system Night extension while Night mode is on
     * with Google's engine chosen, a normal session otherwise. */
    fun applyNightEngine() {
        controllerScope.launch { applyNightEngineNow() }
    }

    private suspend fun applyNightEngineNow() {
        nightLock.lock()
        try {
            val texture = currentTexture ?: return
            if (!cameraStarted || isRecording) return
            val wantExtension = mode == CaptureMode.NIGHT && nightGoogle && googleNightAvailable
            if (wantExtension == engine.extensionActive) return
            sessionRestarting = true
            try {
                if (wantExtension) {
                    // The extension has no ultra-wide: it works from 1x up.
                    if (zoomRatio < 1f) {
                        zoomRatio = 1f
                        engine.setZoom(1f)
                    }
                    val size = engine.startNightExtension(texture, engine.captureAspect())
                    currentPreviewSize = size
                    withContext(Dispatchers.Main) { applyTransform?.invoke(size) }
                } else {
                    val size = engine.choosePreviewSize(engine.captureAspect())
                    currentPreviewSize = size
                    engine.startPreviewSession(texture, size, isRawEnabled)
                    withContext(Dispatchers.Main) { applyTransform?.invoke(size) }
                    afterSessionStarted()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Night Sight (Google)", e)
                nightGoogle = false
                try {
                    val size = engine.choosePreviewSize(engine.captureAspect())
                    currentPreviewSize = size
                    engine.startPreviewSession(texture, size, isRawEnabled)
                    withContext(Dispatchers.Main) { applyTransform?.invoke(size) }
                } catch (inner: Exception) {
                    fail("Restoring the camera", inner)
                }
            } finally {
                sessionRestarting = false
            }
        } finally {
            nightLock.unlock()
        }
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

    /** Switches RAW on or off from the UI. It runs on the controller's own scope: a popup that
     * closes right after the tap would otherwise cancel the coroutine that is mid-way through
     * restarting the session, and the switch would silently do nothing. */
    fun setRaw(enabled: Boolean) {
        controllerScope.launch {
            if (isRawEnabled != enabled) toggleRaw()
        }
    }

    /** Takes effect immediately by restarting just the capture session (not the whole camera
     * device), adding or dropping the RAW ImageReader from its target surfaces. */
    suspend fun toggleRaw() {
        val target = !isRawEnabled
        val texture = currentTexture
        val previewSize = currentPreviewSize
        if (texture == null || previewSize == null || isRecording || engine.extensionActive) {
            // No open camera to reconfigure right now: remember the choice; the next session
            // start picks it up.
            isRawEnabled = target
            return
        }
        sessionRestarting = true
        try {
            engine.startPreviewSession(texture, previewSize, target)
            isRawEnabled = target
            afterSessionStarted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Toggling RAW", e)
        } finally {
            sessionRestarting = false
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
        // The images of a RAW burst that no one has taken over yet; closed if anything goes wrong.
        var strayRaw: List<Image> = emptyList()
        try {
            // A lens or format change rebuilds the session in the background; give it a moment.
            var waited = 0
            while (sessionRestarting && waited < 30) {
                delay(100)
                waited++
            }
            if (mode == CaptureMode.NIGHT && engine.extensionActive) {
                captureGoogleNight(rotationDegrees)
                return
            }
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

            val rawIndices: Set<Int>? = if (isRawEnabled && engine.rawActive) rawIndicesFor(exposures.size) else null
            val burst = withTimeout(25_000) {
                engine.captureBurst(rotationDegrees, exposures, rawIndices, withStereo = mode == CaptureMode.PORTRAIT && engine.stereoActive)
            }
            strayRaw = burst.rawImages
            val stamp = CaptureStore.newStamp()

            // RAW (if on): several frames are merged in the background into one cleaner DNG;
            // a single frame (or when a merge is already running) is written right now, while its
            // buffer is still ours.
            val dngOrientation = engine.sensorToJpegOrientation(rotationDegrees)
            val dngThumbnail = burst.jpegs.firstOrNull()
            var mergeRaw: (() -> Unit)? = null
            val rawImages = burst.rawImages
            if (rawImages.size >= 2 && !rawMergeBusy) {
                rawMergeBusy = true
                mergeRaw = {
                    adjustPending(1)
                    processingScope.launch {
                        try {
                            val refIndex = rawImages.size / 2
                            val merged = try {
                                RawMerge.merge(rawImages, burst.rawResults, burst.rawCharacteristics, refIndex)
                            } catch (e: Exception) {
                                Log.w(TAG, "RAW merge failed; saving one frame", e)
                                null
                            } catch (e: OutOfMemoryError) {
                                Log.w(TAG, "RAW merge ran out of memory; saving one frame", e)
                                null
                            }
                            if (merged != null) {
                                CaptureStore.saveDng(
                                    context, null, burst.rawCharacteristics, burst.rawResults[refIndex], stamp,
                                    orientationDegrees = dngOrientation, thumbnailJpeg = dngThumbnail, merged = merged,
                                )
                            } else {
                                CaptureStore.saveDng(
                                    context, rawImages[refIndex], burst.rawCharacteristics, burst.rawResults[refIndex], stamp,
                                    orientationDegrees = dngOrientation, thumbnailJpeg = dngThumbnail,
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Couldn't save the RAW", e)
                        } finally {
                            rawImages.forEach { try { it.close() } catch (_: Exception) { } }
                            rawMergeBusy = false
                            adjustPending(-1)
                        }
                    }
                }
                strayRaw = emptyList()
            } else if (rawImages.isNotEmpty()) {
                val index = rawImages.size / 2
                rawImages.forEachIndexed { i, image -> if (i != index) try { image.close() } catch (_: Exception) { } }
                strayRaw = emptyList()
                withContext(Dispatchers.IO) {
                    CaptureStore.saveDng(
                        context, rawImages[index], burst.rawCharacteristics, burst.rawResults[index], stamp,
                        orientationDegrees = dngOrientation, thumbnailJpeg = dngThumbnail,
                    )
                }
            }

            val first = burst.firstResult
            fun buildMeta(depthNote: String): PhotoMeta {
            val summary = when {
                night -> "Night Sight, ${exposures.size} frames"
                tone is PhotoProcessor.ToneMode.HdrPlus -> "HDR+, ${exposures.size} frames"
                mode == CaptureMode.PORTRAIT -> "Portrait, ${portraitStyle.label} ($depthNote)"
                mode == CaptureMode.MACRO -> "Macro"
                else -> "Standard"
            }
            return PhotoMeta(
                exposureNanos = first?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = first?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
                aperture = first?.get(android.hardware.camera2.CaptureResult.LENS_APERTURE),
                focalLengthMm = first?.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH),
                software = "Niko's Cam ${UpdateManager.installedVersionName(context)}",
                description = "Quality ${jpegQuality}; $summary; ${look.lut?.name ?: look.filter.label}; " +
                    "zoom ${String.format(java.util.Locale.US, "%.1f", zoomRatio)}x; lens ${first?.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: "-"}" +
                    (if (engine.ultraHdrActive) "; Ultra HDR" else "") + (if (engine.p3Active) "; Display P3" else "") +
                    (if (rawImages.size >= 2) "; RAW merged from ${rawImages.size} frames" else ""),
            )
            }
            val meta = buildMeta(if (burst.stereo != null) "subject outline + two-lens stereo" else "subject outline")
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
            if (instant == null || !needsProcessing) {
                mergeRaw?.invoke()
                return
            }
            // Portrait also steps the lens from far to near for depth from focus. The photo is
            // already saved, so nothing is lost if this is slow or fails.
            val focusStack = if (blurNow > 0f && mode == CaptureMode.PORTRAIT) captureFocusStack() else null
            val depthCapture = if (burst.stereo != null || focusStack != null) DepthCapture(burst.stereo, focusStack) else null
            val jobMeta = if (focusStack != null) {
                buildMeta(if (burst.stereo != null) "subject outline + two-lens stereo + focus sweep" else "subject outline + focus sweep")
            } else meta
            val job = PhotoJob(
                uri = instant,
                frames = burst.jpegs,
                tone = tone,
                look = look,
                ratio = ratioNow,
                mirror = mirrorNow,
                quality = jpegQuality,
                portraitBlur = blurNow,
                portraitStyle = portraitStyle,
                stamp = stamp,
                meta = jobMeta,
                depth = depthCapture,
                noiseScale = sqrt((meta.iso ?: 400) / 400f).coerceIn(0.8f, 2.2f),
                zoomRatio = zoomRatio,
                keepUltraHdr = engine.ultraHdrActive,
            )
            adjustPending(1)
            processingScope.launch { runPhotoJob(job) }
            mergeRaw?.invoke()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            fail("Taking photo", RuntimeException("the camera didn't deliver its frames in time"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Taking photo", e)
        } finally {
            strayRaw.forEach { try { it.close() } catch (_: Exception) { } }
            isCapturing = false
            countdownRemaining = 0
            holdStillMs = 0L
        }
    }

    /** Portrait: steps the lens from far to near, grabbing a small viewfinder frame at each step, so
     * how sharp each area looks says how far away it is. Null if the lens can't be stepped. */
    private suspend fun captureFocusStack(): FocusStack? {
        val grab = previewGrabber ?: return null
        if (engine.extensionActive || proSettings.manualFocusEnabled) return null
        val diopters = engine.focusSweepDiopters() ?: return null
        holdStillMs = diopters.size * 340L + 300L
        val frames = ArrayList<ByteArray>()
        var width = 0
        var height = 0
        try {
            for (d in diopters) {
                engine.setFocusOverride(d)
                delay(300)
                val bitmap = withContext(Dispatchers.Main) { grab(320) } ?: return null
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()
                if (width != 0 && (w != width || h != height)) return null
                width = w
                height = h
                frames.add(ByteArray(w * h) { i ->
                    val p = pixels[i]
                    ((0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))).toInt().coerceIn(0, 255).toByte()
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Focus sweep failed", e)
            return null
        } finally {
            engine.setFocusOverride(null)
            holdStillMs = 0L
        }
        return FocusStack(width, height, diopters, frames)
    }

    /** Which requests of a burst of [frames] carry a RAW frame: the middle one alone, or an even
     * spread of up to the phone's limit when the frames can be merged in the RAW domain. */
    private fun rawIndicesFor(frames: Int): Set<Int> {
        val capacity = engine.rawFrameCapacity
        val wanted = if (frames > 1 && capacity >= 2 && !rawMergeBusy) minOf(capacity, frames) else 1
        if (wanted <= 1) return setOf(frames / 2)
        return (0 until wanted).map { it * (frames - 1) / (wanted - 1) }.toSet()
    }

    /** True while a RAW merge is running in the background (the next shot then saves a single RAW). */
    @Volatile
    private var rawMergeBusy = false

    /** Night mode with Google's engine: the system does all the work and hands back a JPEG. */
    private suspend fun captureGoogleNight(rotationDegrees: Int) {
        holdStillMs = 4000L
        Toast.makeText(context, "Hold your phone steady", Toast.LENGTH_SHORT).show()
        val bytes = withTimeout(40_000) { engine.captureExtensionPhoto(rotationDegrees) }
        val stamp = CaptureStore.newStamp()
        val meta = PhotoMeta(
            software = "Niko's Cam ${UpdateManager.installedVersionName(context)}",
            description = "Quality 98; Night Sight (Google); ${look.filter.label}; zoom ${String.format(java.util.Locale.US, "%.1f", zoomRatio)}x",
        )
        val uri = withContext(Dispatchers.IO) { CaptureStore.saveJpeg(context, bytes, stamp, meta = meta) }
        if (uri != null) {
            lastCaptureUri = uri
            lastCaptureIsVideo = false
            captureRevision += 1
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
        val portraitStyle: PortraitStyle,
        val stamp: String,
        val meta: PhotoMeta,
        val noiseScale: Float,
        val zoomRatio: Float,
        val keepUltraHdr: Boolean,
        val depth: DepthCapture? = null,
    )

    private suspend fun runPhotoJob(job: PhotoJob) {
        try {
            // On any failure the camera's own JPEG, already saved, simply stays as the photo.
            val jpeg = try {
                PhotoProcessor.processShots(job.frames, job.tone, job.look, job.ratio, job.mirror, job.quality, job.portraitBlur, job.noiseScale, job.zoomRatio, job.keepUltraHdr, job.portraitStyle, job.depth)
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
