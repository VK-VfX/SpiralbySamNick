package com.nikoezveracamspiral

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The capture pipeline, built directly on android.hardware.camera2 rather than CameraX: Pro
 * mode's manual ISO/shutter/focus/white-balance controls and true RAW_SENSOR (DNG) capture both
 * need CaptureRequest-level access that CameraX's stable public API doesn't expose, the same
 * reason the visualizer's AudioCaptureService talks to AudioRecord directly instead of through a
 * wrapper library. All Camera2 calls happen on a dedicated background HandlerThread, per the
 * API's own requirement -- none of this runs on the main thread.
 *
 * One engine instance is opened against exactly one camera (back or front) at a time;
 * [switchCamera] tears down and reopens against the other rather than juggling two live devices.
 */
class CameraEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null

    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var extensionSession: CameraExtensionSession? = null
    private var extensionJpegReader: ImageReader? = null
    private var extensionZoomSupported = false
    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null

    var currentCameraId: String = ""
        private set
    var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
        private set

    /** The angle (always a multiple of 90 on real devices -- 90 for the typical back camera, 270
     * for the typical front one) the sensor's raw buffer must be rotated clockwise to appear
     * upright when the device is held in its natural orientation. Read directly from
     * characteristics rather than assumed from [lensFacing], since a handful of devices deviate
     * from the 90/270 convention. */
    val sensorOrientation: Int
        get() = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    var proSettings: ProSettings = ProSettings()
        private set
    var zoomRatio: Float = 1f
        private set
    var flashMode: FlashMode = FlashMode.OFF
        private set

    /** True when the current session has a RAW stream to capture into. */
    val rawActive: Boolean
        get() = rawReader != null

    val isRawSupported: Boolean
        get() = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

    val isoRange: Range<Int>?
        get() = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    val exposureTimeRange: Range<Long>?
        get() = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    val exposureCompensationRange: Range<Int>?
        get() = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

    val minFocusDistance: Float
        get() = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    val supportsManualExposure: Boolean
        get() = isoRange != null && exposureTimeRange != null

    val supportsManualFocus: Boolean
        get() = minFocusDistance > 0f

    fun startBackgroundThread() {
        val thread = HandlerThread("CameraEngine").also { it.start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: InterruptedException) {
            // Nothing left to clean up if the join itself was interrupted.
        }
        backgroundThread = null
        backgroundHandler = null
    }

    fun findCameraId(facing: Int): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }

    fun hasFrontCamera(): Boolean = findCameraId(CameraCharacteristics.LENS_FACING_FRONT) != null

    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String): Unit = suspendCancellableCoroutine { cont ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    currentCameraId = cameraId
                    characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    lensFacing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onClosed(camera: CameraDevice) {
                    closedLatch?.countDown()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Camera open error: $error"))
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /** Tears down the capture session and its reader/preview surfaces without closing the camera
     * device itself -- shared by [closeCamera] and by [startPreviewSession] (called there so
     * re-starting the preview, e.g. after toggling RAW or returning from video, never leaks the
     * previous session's surfaces instead of needing every caller to remember to close them). */
    private fun closeSessionResources() {
        captureSession?.close()
        captureSession = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { extensionSession?.close() } catch (_: Exception) { }
        }
        extensionSession = null
        extensionJpegReader?.close()
        extensionJpegReader = null
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        // previewSurface is kept: it is tied to the SurfaceTexture, and creating a second Surface
        // for a texture whose first one is still connected is what the log flagged as
        // "createEvilTwin". It is released in closeCamera().
    }

    fun closeCamera() {
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            // Already stopped, or stopped before any frame was recorded; nothing to salvage.
        }
        mediaRecorder?.release()
        mediaRecorder = null
        recorderSurface = null

        closeSessionResources()
        previewSurface?.release()
        previewSurface = null
        previewTexture = null

        // Wait (briefly) for the camera to report it is closed before the caller stops the
        // background thread its callbacks are delivered on.
        val device = cameraDevice
        cameraDevice = null
        characteristics = null
        if (device != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            closedLatch = latch
            device.close()
            try {
                latch.await(700, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Nothing more to wait for.
            }
            closedLatch = null
        }
    }

    @Volatile
    private var closedLatch: java.util.concurrent.CountDownLatch? = null
    private var previewTexture: SurfaceTexture? = null

    private fun chooseJpegSize(format: Int = ImageFormat.JPEG): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(format)
        return sizes?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)
    }

    private fun chooseRawSize(chars: CameraCharacteristics? = characteristics): Size? {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        return sizes.maxByOrNull { it.width.toLong() * it.height }
    }

    /** The still-capture aspect ratio as long side over short side (4/3 for a typical sensor), so
     * the viewfinder can be shaped to match exactly what ends up saved. */
    fun captureAspect(): Float {
        val size = chooseJpegSize()
        return max(size.width, size.height).toFloat() / min(size.width, size.height).toFloat()
    }

    /** Picks the largest preview resolution matching [targetAspect] (long over short side) among
     * sizes the HAL can actually stream to a SurfaceTexture, capped at 1920 on the long side -- a
     * full-sensor preview buffer wastes bandwidth the on-screen TextureView can't use anyway. Falls
     * back to the closest aspect if nothing matches exactly. */
    fun choosePreviewSize(targetAspect: Float): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))
        val capped = sizes.filter { it.width <= 1920 && it.height <= 1920 }.ifEmpty { sizes.toList() }
        fun aspectOf(size: Size) = max(size.width, size.height).toFloat() / min(size.width, size.height).toFloat()
        val matching = capped.filter { abs(aspectOf(it) - targetAspect) < 0.02f }
        return matching.maxByOrNull { it.width.toLong() * it.height }
            ?: capped.minByOrNull { abs(aspectOf(it) - targetAspect) }
            ?: capped.first()
    }

    private fun zoomRatioRange(): android.util.Range<Float>? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }

    /** Below 1.0 on phones whose back camera is a logical multi-lens camera (0.5x ultra-wide). */
    fun minZoomRatio(): Float = zoomRatioRange()?.lower?.coerceAtMost(1f) ?: 1f

    fun maxZoomRatio(): Float =
        zoomRatioRange()?.upper ?: (characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

    /** Longer exposures and a brightened viewfinder while Night Sight is selected. */
    var nightPreviewBoost: Boolean = false

    /** How many stops brighter than "normal" the Night viewfinder currently is, so a capture can
     * be based on the scene's real exposure rather than the boosted one. */
    @Volatile
    private var nightBoostEv: Float = 0f

    private var videoFps = 30

    private var stopsCache: Pair<String, List<Float>>? = null
    private var lensCache: Pair<String, String>? = null

    /** 35 mm-equivalent focal length of each distinct physical lens behind the current (logical)
     * camera. A phone can list the same lens under several ids, so lenses are de-duplicated by
     * focal length. */
    private fun physicalLensEquivalents(): List<Float> {
        val chars = characteristics ?: return emptyList()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return emptyList()
        val byFocal = LinkedHashMap<Int, Float>()
        for (id in chars.physicalCameraIds) {
            try {
                val c = cameraManager.getCameraCharacteristics(id)
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                byFocal.getOrPut(Math.round(focal * 10f)) { focal * 43.27f / kotlin.math.hypot(size.width, size.height) }
            } catch (e: Exception) {
                // An unreadable lens is simply left out.
            }
        }
        return byFocal.values.sorted()
    }

    /**
     * The zoom stops to offer as one-tap chips, built from what this particular phone's camera
     * reports: its widest zoom, 1x, the optical stop of its telephoto (a Pixel 9 Pro XL's 5x, a
     * Pixel 7 Pro's 4x, none on a Pixel 8a), a 2x sensor crop, and a doubled telephoto. Optical
     * stops are rounded the way phones label them (4.5 becomes 5). Falls back to common stops
     * when the lens data isn't readable.
     */
    fun zoomStops(): List<Float> {
        val cached = stopsCache
        if (cached != null && cached.first == currentCameraId) return cached.second
        if (characteristics == null) return listOf(1f)
        val min = minZoomRatio()
        val max = maxZoomRatio()
        val stops = sortedSetOf(1f)
        // The widest zoom is the camera's own minimum (0.51x, 0.53x, ...): labelled ".5" in the UI.
        if (min < 0.95f) stops.add(min)
        val equivalents = physicalLensEquivalents()
        var telephoto: Float? = null
        var lensDataUsable = false
        if (equivalents.size >= 2) {
            val main = equivalents.minByOrNull { kotlin.math.abs(it - 24f) } ?: equivalents.first()
            for (eq in equivalents) {
                val ratio = eq / main
                if (ratio in 1.3f..12f && ratio <= max) {
                    val rounded = if (ratio >= 3f) Math.round(ratio + 0.25f).toFloat() else Math.round(ratio * 2f) / 2f
                    stops.add(rounded)
                    telephoto = rounded
                    lensDataUsable = true
                }
            }
        }
        if (max >= 2f && (telephoto == null || kotlin.math.abs(telephoto - 2f) > 0.4f)) stops.add(2f)
        if (telephoto != null && lensDataUsable) {
            if (telephoto * 2f <= max) stops.add(telephoto * 2f)
        } else if (equivalents.size < 2) {
            // Lens data wasn't readable: offer the common stops. (When it was readable and there
            // is no telephoto, as on a Pixel 8a, only the 2x crop is offered.)
            if (max >= 5f) stops.add(5f)
            if (max >= 10f) stops.add(10f)
        }
        val result = stops.filter { it <= max + 0.01f && (it >= min - 0.01f || it == 1f) }
        stopsCache = currentCameraId to result
        return result
    }

    /** "0.5x, 1x, 5x" style summary of this camera's lenses, for the settings sheet and reports. */
    fun lensSummary(): String =
        zoomStops().joinToString(" · ") { if (it == it.toInt().toFloat()) "${it.toInt()}x" else "${it}x" }

    private var macroCache: Pair<String, Boolean>? = null
    private val lensNameCache = HashMap<String, String>()

    /**
     * Whether any lens behind this camera can focus really close (about 5 cm or nearer): a Pixel
     * 9 Pro XL's ultra-wide can; a Pixel 8a's ultra-wide is fixed-focus and its main lens stops at
     * about 10 cm, so Macro isn't offered there.
     */
    fun supportsMacro(): Boolean {
        val chars = characteristics ?: return false
        val cached = macroCache
        if (cached != null && cached.first == currentCameraId) return cached.second
        var capable = (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) >= 20f
        if (!capable && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            for (id in chars.physicalCameraIds) {
                try {
                    val diopters = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    if (diopters >= 20f) capable = true
                } catch (e: Exception) {
                    // Unreadable lens: ignore.
                }
            }
        }
        macroCache = currentCameraId to capable
        return capable
    }

    /** Which physical lens is taking the picture right now ("Telephoto 17.9 mm"), if the camera says. */
    fun activeLensLabel(): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
        val id = lastPreviewResult?.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return null
        lensNameCache[id]?.let { return it }
        return try {
            val c = cameraManager.getCameraCharacteristics(id)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val equivalent = if (size != null) focal * 43.27f / kotlin.math.hypot(size.width, size.height) else focal * 4f
            val name = when {
                equivalent < 18f -> "Ultra-wide"
                equivalent < 45f -> "Main"
                else -> "Telephoto"
            }
            "$name ${String.format(java.util.Locale.US, "%.1f", focal)} mm".also { lensNameCache[id] = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Macro: prefers the camera's close-focus AF mode where it has one. */
    var macroMode: Boolean = false

    /** Where the lens is currently focused, in centimetres, from the latest preview frame. */
    fun currentFocusDistanceCm(): Float? {
        val diopters = lastPreviewResult?.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE) ?: return null
        return if (diopters > 0.3f) 100f / diopters else null
    }

    /** AE/AF lock (long-press on the viewfinder). */
    var controlsLocked: Boolean = false
        private set

    fun setControlsLocked(locked: Boolean) {
        controlsLocked = locked
        startRepeatingPreview()
    }

    // ---- Session features: Display P3, Ultra HDR, RAW from the ultra-wide lens -----------------
    // Each is requested only where the phone says it supports it, and dropped one at a time (in
    // the order below) if the camera refuses the combination, so a refusal never costs the shot.

    /** How many RAW frames one burst may keep (1 = a single RAW). Set before a session starts. */
    var rawFrameCapacity: Int = 1

    /** Whether Ultra HDR (a JPEG carrying an HDR gain map) is wanted where the camera offers it. */
    var ultraHdrWanted: Boolean = true

    /** The format of the still stream in the current session: JPEG, or JPEG_R for Ultra HDR. */
    var jpegFormat: Int = ImageFormat.JPEG
        private set
    val ultraHdrActive: Boolean
        get() = Build.VERSION.SDK_INT >= 34 && jpegFormat == ImageFormat.JPEG_R

    /** True when stills are captured in Display P3 rather than sRGB. */
    var p3Active: Boolean = false
        private set

    /** The physical camera RAW comes from (the ultra-wide at 0.5x), or null for the logical one. */
    var rawPhysicalId: String? = null
        private set

    /** Characteristics to build a DNG from: the physical lens's own when RAW comes from one. */
    var rawCharacteristics: CameraCharacteristics? = null
        private set

    /** One line about a feature the session had to drop; the UI shows it once. */
    @Volatile
    var sessionNote: String? = null

    /** Short description of what the current session ended up using, for the problem report. */
    var sessionSummary: String = "not started"
        private set

    /** True when the session was built for zoom below 1x with RAW coming from the wide lens. */
    var rawIsFromWideLens: Boolean = false
        private set

    private data class SessionPlan(val ultraHdr: Boolean, val p3: Boolean, val physicalRaw: String?, val raw: Boolean)

    @SuppressLint("NewApi")
    private fun jpegRSupported(chars: CameraCharacteristics): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.outputFormats?.contains(ImageFormat.JPEG_R) == true

    @SuppressLint("NewApi")
    private fun p3Supported(chars: CameraCharacteristics, format: Int): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES) ?: return false
        return profiles.getSupportedColorSpaces(format).contains(ColorSpace.Named.DISPLAY_P3)
    }

    /** The physical camera with the shortest focal length, when this is a multi-lens camera whose
     * widest lens can also deliver RAW. */
    @SuppressLint("NewApi")
    private fun widePhysicalId(chars: CameraCharacteristics): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val ids = chars.physicalCameraIds
        if (ids.size < 2) return null
        fun focal(id: String): Float = try {
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 99f
        } catch (e: Exception) {
            99f
        }
        val widest = ids.minByOrNull { focal(it) } ?: return null
        val widestChars = try { cameraManager.getCameraCharacteristics(widest) } catch (e: Exception) { return null }
        val canRaw = widestChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        return if (canRaw) widest else null
    }

    @SuppressLint("NewApi")
    suspend fun startPreviewSession(
        texture: SurfaceTexture,
        previewSize: Size,
        includeRaw: Boolean,
    ) {
        val device = cameraDevice ?: throw IllegalStateException("Camera not open")
        val chars = characteristics ?: throw IllegalStateException("Camera not open")

        closeSessionResources()
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val existing = previewSurface
        if (!(existing != null && previewTexture === texture && existing.isValid)) {
            existing?.release()
            previewSurface = Surface(texture)
            previewTexture = texture
        }

        val rawRequested = includeRaw && isRawSupported
        val belowOne = zoomRatio < 0.99f
        val wide = if (rawRequested && belowOne) widePhysicalId(chars) else null
        // Below 1x the RAW has to come from the wide lens itself; a RAW through the main lens
        // would be a different picture from the JPEG, so with no way to reach it there is no RAW.
        val wantRaw = rawRequested && !(belowOne && wide == null)
        val ultra = ultraHdrWanted && jpegRSupported(chars)
        val p3WithUltra = ultra && p3Supported(chars, ImageFormat.JPEG_R)
        val p3Plain = p3Supported(chars, ImageFormat.JPEG)

        val plans = LinkedHashSet<SessionPlan>()
        plans.add(SessionPlan(ultra, if (ultra) p3WithUltra else p3Plain, wide, wantRaw))
        if (ultra) plans.add(SessionPlan(true, false, wide, wantRaw))
        if (ultra) plans.add(SessionPlan(false, p3Plain, wide, wantRaw))
        plans.add(SessionPlan(false, false, wide, wantRaw))
        // Last resort when the camera refuses RAW outright: JPEG only, rather than no camera.
        if (wantRaw) plans.add(SessionPlan(false, false, null, false))

        var failure: Exception? = null
        for (plan in plans) {
            try {
                configureSession(device, plan)
                sessionSummary = buildString {
                    append(if (plan.ultraHdr) "Ultra HDR" else "JPEG")
                    append(if (plan.p3) ", Display P3" else ", sRGB")
                    append(
                        when {
                            !plan.raw -> if (rawRequested) ", RAW off (not available here)" else ", RAW not requested"
                            plan.physicalRaw != null -> ", RAW from wide lens ${plan.physicalRaw}"
                            else -> ", RAW from main camera"
                        },
                    )
                }
                if (rawRequested && !plan.raw) {
                    sessionNote = "RAW isn't available at this zoom on this phone, so shots save as JPEG"
                }
                rawIsFromWideLens = plan.physicalRaw != null
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                closeSessionResources()
            }
        }
        throw failure ?: RuntimeException("Capture session configuration failed")
    }

    @SuppressLint("NewApi")
    private suspend fun configureSession(device: CameraDevice, plan: SessionPlan): Unit =
        suspendCancellableCoroutine { cont ->
            closeSessionResources()
            val previewSurface = this.previewSurface
            if (previewSurface == null) {
                cont.resumeWithException(IllegalStateException("No preview surface"))
                return@suspendCancellableCoroutine
            }
            val jpegFmt = if (plan.ultraHdr && Build.VERSION.SDK_INT >= 34) ImageFormat.JPEG_R else ImageFormat.JPEG
            val jpegSize = chooseJpegSize(jpegFmt)
            val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, jpegFmt, 4)
            jpegReader = reader
            jpegFormat = jpegFmt
            p3Active = plan.p3
            rawPhysicalId = null
            rawCharacteristics = null

            var rawSurface: Surface? = null
            if (plan.raw) {
                val rawChars = if (plan.physicalRaw != null) {
                    try { cameraManager.getCameraCharacteristics(plan.physicalRaw) } catch (e: Exception) { null }
                } else {
                    characteristics
                }
                val rawSize = chooseRawSize(rawChars)
                if (rawChars != null && rawSize != null) {
                    val capacity = (rawFrameCapacity + 1).coerceIn(2, 9)
                    val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, capacity)
                    rawReader = raw
                    rawSurface = raw.surface
                    rawPhysicalId = plan.physicalRaw
                    rawCharacteristics = rawChars
                }
            }

            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startRepeatingPreview()
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    try { session.close() } catch (_: Exception) { }
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Capture session configuration failed"))
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val configs = ArrayList<OutputConfiguration>()
                    configs.add(OutputConfiguration(previewSurface))
                    val still = OutputConfiguration(reader.surface)
                    if (plan.p3 && Build.VERSION.SDK_INT >= 34) still.setColorSpace(ColorSpace.Named.DISPLAY_P3)
                    configs.add(still)
                    rawSurface?.let { surface ->
                        val rawConfig = OutputConfiguration(surface)
                        plan.physicalRaw?.let { rawConfig.setPhysicalCameraId(it) }
                        configs.add(rawConfig)
                    }
                    val executor = Executor { task -> backgroundHandler?.post(task) }
                    device.createCaptureSession(
                        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor, callback),
                    )
                } else {
                    val targets = mutableListOf(previewSurface, reader.surface)
                    rawSurface?.let { targets.add(it) }
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(targets, callback, backgroundHandler)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    // ---- Google's own Night Sight, through the Camera Extensions API ----------------------------

    /** True when the system offers its Night extension for the open camera (Android 12+). */
    @SuppressLint("NewApi")
    fun extensionNightSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || currentCameraId.isEmpty()) return false
        return try {
            cameraManager.getCameraExtensionCharacteristics(currentCameraId)
                .supportedExtensions.contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
        } catch (e: Exception) {
            false
        }
    }

    val extensionActive: Boolean
        get() = extensionSession != null

    /** Opens the Night extension session and returns the preview size it chose. */
    @SuppressLint("NewApi")
    suspend fun startNightExtension(texture: SurfaceTexture, targetAspect: Float): Size =
        suspendCancellableCoroutine { cont ->
            val device = cameraDevice
            if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                cont.resumeWithException(IllegalStateException("Night Sight (Google) isn't available"))
                return@suspendCancellableCoroutine
            }
            try {
                closeSessionResources()
                val extChars = cameraManager.getCameraExtensionCharacteristics(currentCameraId)
                val ext = CameraExtensionCharacteristics.EXTENSION_NIGHT
                val previewSizes = extChars.getExtensionSupportedSizes(ext, SurfaceTexture::class.java)
                val jpegSizes = extChars.getExtensionSupportedSizes(ext, ImageFormat.JPEG)
                fun aspectOf(size: Size) = max(size.width, size.height).toFloat() / min(size.width, size.height).toFloat()
                val capped = previewSizes.filter { it.width <= 1920 && it.height <= 1920 }.ifEmpty { previewSizes }
                val preview = capped.filter { abs(aspectOf(it) - targetAspect) < 0.02f }.maxByOrNull { it.width.toLong() * it.height }
                    ?: capped.minByOrNull { abs(aspectOf(it) - targetAspect) }
                val jpegSize = jpegSizes.filter { abs(aspectOf(it) - targetAspect) < 0.02f }.maxByOrNull { it.width.toLong() * it.height }
                    ?: jpegSizes.maxByOrNull { it.width.toLong() * it.height }
                if (preview == null || jpegSize == null) {
                    cont.resumeWithException(IllegalStateException("Night Sight (Google) has no usable sizes"))
                    return@suspendCancellableCoroutine
                }
                extensionZoomSupported = extChars.getAvailableCaptureRequestKeys(ext).contains(CaptureRequest.CONTROL_ZOOM_RATIO)

                texture.setDefaultBufferSize(preview.width, preview.height)
                val existing = previewSurface
                if (!(existing != null && previewTexture === texture && existing.isValid)) {
                    existing?.release()
                    previewSurface = Surface(texture)
                    previewTexture = texture
                }
                val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
                extensionJpegReader = reader
                val executor = Executor { task -> backgroundHandler?.post(task) }
                val configs = listOf(OutputConfiguration(previewSurface!!), OutputConfiguration(reader.surface))
                val config = ExtensionSessionConfiguration(
                    ext, configs, executor,
                    object : CameraExtensionSession.StateCallback() {
                        override fun onConfigured(session: CameraExtensionSession) {
                            extensionSession = session
                            startRepeatingPreview()
                            sessionSummary = "Night Sight (Google system extension)"
                            if (cont.isActive) cont.resume(preview)
                        }

                        override fun onConfigureFailed(session: CameraExtensionSession) {
                            if (cont.isActive) cont.resumeWithException(RuntimeException("Night Sight (Google) couldn't start"))
                        }

                        override fun onClosed(session: CameraExtensionSession) = Unit
                    },
                )
                device.createExtensionSession(config)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    @SuppressLint("NewApi")
    private fun startExtensionPreview(session: CameraExtensionSession) {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            if (extensionZoomSupported) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceAtLeast(1f))
            val executor = Executor { task -> backgroundHandler?.post(task) }
            session.setRepeatingRequest(builder.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
        } catch (_: Exception) {
            // The session was closed between the call and here; a new one is being built.
        }
    }

    /** One photo from the Night extension: the system does all the work and returns a JPEG. */
    @SuppressLint("NewApi")
    suspend fun captureExtensionPhoto(deviceRotationDegrees: Int): ByteArray =
        suspendCancellableCoroutine { cont ->
            val device = cameraDevice
            val session = extensionSession
            val reader = extensionJpegReader
            if (device == null || session == null || reader == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                cont.resumeWithException(IllegalStateException("Night Sight (Google) isn't ready"))
                return@suspendCancellableCoroutine
            }
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorToJpegOrientation(deviceRotationDegrees))
                builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                if (extensionZoomSupported) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceAtLeast(1f))
                reader.setOnImageAvailableListener({ r ->
                    val image = try { r.acquireNextImage() } catch (e: IllegalStateException) { null }
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        if (cont.isActive) cont.resume(bytes)
                    }
                }, backgroundHandler)
                val executor = Executor { task -> backgroundHandler?.post(task) }
                session.capture(builder.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                    override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                        if (cont.isActive) cont.resumeWithException(RuntimeException("Night Sight (Google) capture failed"))
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    @Volatile
    private var lastPreviewResult: TotalCaptureResult? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastPreviewResult = result
        }
    }

    /** The exposure (nanoseconds, ISO) auto-exposure has currently settled on, read from the
     * latest preview frame: the starting point an HDR bracket is built around. */
    fun currentAutoExposure(): Pair<Long, Int>? {
        val result = lastPreviewResult ?: return null
        val time = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return null
        if (nightBoostEv > 0.05f) {
            // Undo the viewfinder's own brightening: give back its exposure, shutter first.
            val factor = Math.pow(2.0, nightBoostEv.toDouble())
            var neutralTime = (time / factor).toLong()
            var neutralIso = iso.toDouble()
            val minTime = exposureTimeRange?.lower ?: 1_000L
            if (neutralTime < minTime) {
                neutralIso = iso * (neutralTime.toDouble() / minTime)
                neutralTime = minTime
            }
            val isoLow = isoRange?.lower ?: 50
            return neutralTime to neutralIso.toInt().coerceAtLeast(isoLow)
        }
        return time to iso
    }

    /**
     * A Night Sight frame that gathers the same total light as the scene's auto exposure but
     * spends it on a longer shutter and a lower ISO (less noise per frame, same brightness).
     * [shutterFactor] says how much longer than auto, up to [capNanos].
     */
    fun nightExposure(auto: Pair<Long, Int>, shutterFactor: Int, capNanos: Long): Pair<Long, Int> {
        val product = auto.first.toDouble() * auto.second
        val timeRange = exposureTimeRange
        val isoLow = isoRange?.lower ?: 50
        val isoHigh = isoRange?.upper ?: 3200
        var time = minOf(auto.first * shutterFactor, capNanos)
        if (timeRange != null) time = time.coerceIn(timeRange.lower, timeRange.upper)
        var iso = (product / time).toInt()
        if (iso < isoLow) {
            iso = isoLow
            time = (product / iso).toLong()
            if (timeRange != null) time = time.coerceIn(timeRange.lower, timeRange.upper)
        }
        return time to iso.coerceIn(isoLow, isoHigh)
    }

    /** One bracket step: [evStops] away from [auto], shifting the shutter first (up to 1/10 s,
     * to keep handheld frames sharp) and making up the rest with ISO, all clamped to what the
     * sensor supports. */
    fun bracketExposure(auto: Pair<Long, Int>, evStops: Float): Pair<Long, Int> {
        val timeRange = exposureTimeRange
        val isoRange = isoRange
        val factor = Math.pow(2.0, evStops.toDouble())
        val wantedTime = (auto.first * factor).toLong()
        val maxHandheld = 100_000_000L
        var time = wantedTime.coerceAtMost(maxOf(auto.first, maxHandheld))
        if (timeRange != null) time = time.coerceIn(timeRange.lower, timeRange.upper)
        var iso = (auto.second * (wantedTime.toDouble() / time.toDouble())).toInt()
        if (isoRange != null) iso = iso.coerceIn(isoRange.lower, isoRange.upper)
        return time to iso
    }

    fun startRepeatingPreview() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extensionSession?.let { startExtensionPreview(it); return }
        }
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        val template = if (mediaRecorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val builder = device.createCaptureRequest(template)
        builder.addTarget(surface)
        recorderSurface?.let { builder.addTarget(it) }
        applyControls(builder, preview = true)
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (_: IllegalStateException) {
            // The session was torn down (e.g. mid camera-switch) between this call starting and
            // reaching here; the caller is already rebuilding a fresh session.
        }
    }

    /**
     * Applies zoom, flash, and Pro mode's manual controls to a request builder. The manual
     * controls lean on a specific, easy-to-get-wrong Camera2 detail: CONTROL_MODE stays AUTO the
     * whole time, with CONTROL_AE_MODE and CONTROL_AF_MODE independently switched OFF -- setting
     * CONTROL_MODE itself to OFF disables all three of AE/AF/AWB at once and would also force
     * manual color-correction math just to keep white balance working, which manual exposure or
     * manual focus alone has no reason to need.
     */
    private fun applyControls(builder: CaptureRequest.Builder, preview: Boolean = false) {
        val chars = characteristics ?: return

        // Straighten the lens curve (most visible on an ultra-wide: bowed walls, stretched edges).
        // Some phones do this by default and some, like the Pixel 9 Pro XL, only when asked. The
        // preview and video use the cheaper mode; stills use the best the camera offers.
        val distortionModes = chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        if (distortionModes != null) {
            val cheap = preview || mediaRecorder != null
            val wanted = if (cheap) {
                listOf(CameraMetadata.DISTORTION_CORRECTION_MODE_FAST, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            } else {
                listOf(CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, CameraMetadata.DISTORTION_CORRECTION_MODE_FAST)
            }
            wanted.firstOrNull { distortionModes.contains(it) }?.let {
                builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, it)
            }
        }

        val zoomRange = zoomRatioRange()
        if (zoomRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper))
        } else {
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (activeArray != null && zoomRatio > 1.001f) {
                val cropWidth = (activeArray.width() / zoomRatio).toInt().coerceAtLeast(1)
                val cropHeight = (activeArray.height() / zoomRatio).toInt().coerceAtLeast(1)
                val left = (activeArray.width() - cropWidth) / 2
                val top = (activeArray.height() - cropHeight) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
            }
        }

        // Stabilisation: electronic while filming, optical otherwise, wherever the camera has it.
        val eis = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (mediaRecorder != null && eis?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        } else if (ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, proSettings.whiteBalanceMode.awbMode)

        if (proSettings.manualExposureEnabled) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, proSettings.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proSettings.exposureTimeNanos)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, proSettings.exposureTimeNanos)
        } else {
            val aeMode = when (flashMode) {
                FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            var compensation = proSettings.exposureCompensation
            nightBoostEv = 0f
            if (preview && nightPreviewBoost) {
                // Night Sight: a modestly brighter viewfinder (+1 EV, not the camera's maximum,
                // which washed bright rooms out to white) and slower frame rates allowed.
                val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
                if (range != null && step > 0f) {
                    val boostIndex = Math.round(1f / step).coerceIn(range.lower, range.upper)
                    compensation = maxOf(compensation, boostIndex)
                    nightBoostEv = (compensation - proSettings.exposureCompensation) * step
                }
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.lower <= 12 }
                    ?.maxByOrNull { it.upper }
                    ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
            if (controlsLocked) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }

        if (proSettings.manualFocusEnabled) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, proSettings.focusDistance)
        } else {
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                when {
                    controlsLocked -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    macroMode && afModes?.contains(CameraMetadata.CONTROL_AF_MODE_MACRO) == true -> CameraMetadata.CONTROL_AF_MODE_MACRO
                    else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                },
            )
        }

        builder.set(
            CaptureRequest.FLASH_MODE,
            if (flashMode == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )
    }

    fun updateProSettings(update: (ProSettings) -> ProSettings) {
        proSettings = update(proSettings)
        startRepeatingPreview()
    }

    fun setZoom(ratio: Float) {
        // The lower bound is the camera's own minimum (0.5x on phones with an ultra-wide lens),
        // not 1x: clamping to 1x here is what kept the wide lens from ever being selected.
        zoomRatio = ratio.coerceIn(minZoomRatio(), maxZoomRatio().coerceAtLeast(1f))
        startRepeatingPreview()
    }

    /** Resets zoom without issuing a request -- used right before a new session starts, so a
     * freshly opened camera doesn't inherit the previous one's zoom. */
    fun resetZoom() {
        zoomRatio = 1f
    }

    fun setFlashMode(mode: FlashMode) {
        flashMode = mode
        startRepeatingPreview()
    }

    /** Rotates a normalized (0..1, 0..1) tap point from on-screen preview space into sensor
     * space, accounting for sensor orientation and front-camera mirroring -- the preview surface
     * is drawn rotated/mirrored relative to the raw sensor, so a tap at the displayed top-left
     * isn't the sensor's top-left unless this is undone first. */
    private fun rotateToSensorSpace(nx: Float, ny: Float, sensorOrientation: Int, facingFront: Boolean): Pair<Float, Float> {
        var x = nx
        val y = ny
        if (facingFront) x = 1f - x
        return when (((sensorOrientation % 360) + 360) % 360) {
            90 -> Pair(y, 1f - x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(1f - y, x)
            else -> Pair(x, y)
        }
    }

    /** Drives tap-to-focus/meter from a normalized point within the displayed preview. Only
     * triggers an AF sweep when focus is currently automatic -- in manual focus, a tap setting
     * the AE metering region still makes sense, but there's no autofocus to trigger. */
    fun tapToFocus(normalizedX: Float, normalizedY: Float) {
        val chars = characteristics ?: return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facingFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT

        val (rx, ry) = rotateToSensorSpace(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f), sensorOrientation, facingFront)

        val regionSize = (min(activeArray.width(), activeArray.height()) * 0.12f).toInt().coerceAtLeast(1)
        // The viewfinder shows only the centered zoom crop, so a tap at a normalized point maps
        // into that crop rectangle, not the whole active array.
        val cropWidth = activeArray.width() / zoomRatio.coerceAtLeast(1f)  // below 1x the view shows the whole array
        val cropHeight = activeArray.height() / zoomRatio.coerceAtLeast(1f)
        val cropLeft = (activeArray.width() - cropWidth) / 2f
        val cropTop = (activeArray.height() - cropHeight) / 2f
        val sensorX = (cropLeft + rx * cropWidth).toInt().coerceIn(0, activeArray.width() - 1)
        val sensorY = (cropTop + ry * cropHeight).toInt().coerceIn(0, activeArray.height() - 1)
        val left = (sensorX - regionSize / 2).coerceIn(0, (activeArray.width() - regionSize).coerceAtLeast(0))
        val top = (sensorY - regionSize / 2).coerceIn(0, (activeArray.height() - regionSize).coerceAtLeast(0))
        val region = MeteringRectangle(left, top, regionSize, regionSize, MeteringRectangle.METERING_WEIGHT_MAX)

        val template = if (mediaRecorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val builder = device.createCaptureRequest(template)
        builder.addTarget(surface)
        recorderSurface?.let { builder.addTarget(it) }
        applyControls(builder)

        val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAfRegions > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        if (maxAeRegions > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        // Regions go on the repeating request; the AF trigger is fired once, not every frame.
        val triggerBuilder = if (!proSettings.manualFocusEnabled) {
            device.createCaptureRequest(template).also { b ->
                b.addTarget(surface)
                recorderSurface?.let { b.addTarget(it) }
                applyControls(b)
                if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
        } else {
            null
        }

        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            triggerBuilder?.let { session.capture(it.build(), captureCallback, backgroundHandler) }
        } catch (_: IllegalStateException) {
            // Session torn down concurrently; nothing to apply the metering region to anymore.
        }
    }

    /** [deviceRotationDegrees] is the raw reading of an OrientationEventListener (degrees the
     * device is turned clockwise from upright), or negative if unknown. This is the pairing the
     * Camera2 documentation gives for SENSOR_ORIENTATION: the front camera's sense is mirrored. */
    fun sensorToJpegOrientation(deviceRotationDegrees: Int): Int {
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facingFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val d = if (deviceRotationDegrees < 0) 0 else ((deviceRotationDegrees + 45) / 90 * 90) % 360
        return if (facingFront) (sensorOrientation - d + 360) % 360 else (sensorOrientation + d) % 360
    }

    /**
     * The frames of one burst. [rawImages] are the RAW frames requested (several when a RAW merge
     * is wanted) and are still open: whoever receives them must close every one. [rawResults]
     * line up with them one for one.
     */
    class BurstResult(
        val jpegs: List<ByteArray>,
        val rawImages: List<Image>,
        val rawResults: List<CaptureResult>,
        val rawCharacteristics: CameraCharacteristics,
        val characteristics: CameraCharacteristics,
        val firstResult: TotalCaptureResult? = null,
    )

    /**
     * Fires [exposures].size still captures back to back, as fast as the camera allows, and
     * returns their JPEGs: the shutter-side half of the Pixel-style pipeline (the merge happens
     * afterwards, in the background). A null exposure means automatic. RAW (if on) rides along on
     * the requests listed in [rawIndices].
     */
    suspend fun captureBurst(deviceRotationDegrees: Int, exposures: List<Pair<Long, Int>?>, rawIndices: Set<Int>?): BurstResult =
        suspendCancellableCoroutine { cont ->
            val device = cameraDevice
            val session = captureSession
            val jpeg = jpegReader
            val chars = characteristics
            if (device == null || session == null || jpeg == null || chars == null) {
                cont.resumeWithException(IllegalStateException("Camera not ready"))
                return@suspendCancellableCoroutine
            }
            val raw = rawReader
            val rawSet: Set<Int> = if (raw != null && rawIndices != null) rawIndices else emptySet()
            val wantsRaw = rawSet.isNotEmpty()
            val physicalId = rawPhysicalId
            val rawChars = rawCharacteristics ?: chars
            val total = exposures.size

            // Anything left over from an earlier shot would be mistaken for this burst's frames.
            if (raw != null) {
                while (true) {
                    val stale = try { raw.acquireNextImage() } catch (e: IllegalStateException) { null } ?: break
                    stale.close()
                }
            }

            val requests = exposures.mapIndexed { i, exposure ->
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(jpeg.surface)
                val carriesRaw = wantsRaw && i in rawSet
                if (carriesRaw) builder.addTarget(raw!!.surface)
                applyControls(builder)
                if (carriesRaw) {
                    // The DNG needs the lens shading map to correct the colour/brightness falloff of
                    // each lens (most visible as a magenta/green cast on the ultrawide).
                    val modes = rawChars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                    if (modes?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true) {
                        builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                    }
                }
                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorToJpegOrientation(deviceRotationDegrees))
                applyStillQuality(builder, chars)
                if (exposure != null) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.first)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.second)
                }
                builder.setTag(i)
                builder.build()
            }

            val jpegs = mutableListOf<ByteArray>()
            val rawImages = mutableListOf<Image>()
            val rawResultsByIndex = java.util.TreeMap<Int, CaptureResult>()
            var completed = 0
            var failed = 0
            var firstResult: TotalCaptureResult? = null
            var finished = false

            fun maybeFinish() {
                if (finished) return
                if (completed + failed < total) return
                if (jpegs.size < completed) return
                if (wantsRaw && rawImages.size < rawResultsByIndex.size) return
                finished = true
                if (jpegs.isEmpty()) {
                    rawImages.forEach { try { it.close() } catch (_: Exception) { } }
                    cont.resumeWithException(RuntimeException("Capture failed: no frames received"))
                } else {
                    // A RAW with no matching result can't be made into a DNG: drop the extras.
                    val results = rawResultsByIndex.values.toList()
                    while (rawImages.size > results.size) {
                        try { rawImages.removeAt(rawImages.size - 1).close() } catch (_: Exception) { }
                    }
                    cont.resume(BurstResult(jpegs.toList(), rawImages.toList(), results, rawChars, chars, firstResult))
                }
            }

            cont.invokeOnCancellation {
                if (!finished) rawImages.forEach { try { it.close() } catch (_: Exception) { } }
            }

            jpeg.setOnImageAvailableListener({ reader ->
                while (true) {
                    val image = try { reader.acquireNextImage() } catch (e: IllegalStateException) { null } ?: break
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    jpegs.add(bytes)
                }
                maybeFinish()
            }, backgroundHandler)

            if (wantsRaw) {
                raw!!.setOnImageAvailableListener({ reader ->
                    while (true) {
                        val image = try { reader.acquireNextImage() } catch (e: IllegalStateException) { null } ?: break
                        rawImages.add(image)
                    }
                    maybeFinish()
                }, backgroundHandler)
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    completed += 1
                    if (firstResult == null) firstResult = result
                    val index = request.tag as? Int
                    if (wantsRaw && index != null && index in rawSet) {
                        val physical: CaptureResult? =
                            if (physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) result.physicalCameraResults[physicalId] else null
                        rawResultsByIndex[index] = physical ?: result
                    }
                    maybeFinish()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    failed += 1
                    maybeFinish()
                }
            }, backgroundHandler)
        }

    /** Asks the camera's own ISP for its best still-capture processing wherever the device
     * advertises support, plus near-lossless JPEG encoding. */
    private fun applyStillQuality(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        fun supports(key: CameraCharacteristics.Key<IntArray>, mode: Int) = chars.get(key)?.contains(mode) == true
        // Zoomed shots keep more detail with minimal in-camera noise reduction (the frames are
        // merged afterwards, which cleans the noise without smearing the detail).
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if (zoomRatio >= 2f && nrModes?.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL) == true) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
        } else if (supports(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }
        if (supports(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }
        if (supports(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        }
        builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
    }

    suspend fun capturePhoto(
        deviceRotationDegrees: Int,
        includeRaw: Boolean,
        exposureOverride: Pair<Long, Int>? = null,
    ): CapturedPhoto =
        suspendCancellableCoroutine { cont ->
            val device = cameraDevice
            val session = captureSession
            val jpeg = jpegReader
            val chars = characteristics
            if (device == null || session == null || jpeg == null || chars == null) {
                cont.resumeWithException(IllegalStateException("Camera not ready"))
                return@suspendCancellableCoroutine
            }

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jpeg.surface)
            val raw = rawReader
            val wantsRaw = includeRaw && raw != null
            if (wantsRaw) builder.addTarget(raw!!.surface)
            applyControls(builder)
            builder.set(CaptureRequest.JPEG_ORIENTATION, sensorToJpegOrientation(deviceRotationDegrees))
            applyStillQuality(builder, chars)
            if (exposureOverride != null) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureOverride.first)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, exposureOverride.second)
            }

            var jpegBytes: ByteArray? = null
            var rawImage: Image? = null
            var captureResult: TotalCaptureResult? = null
            var finished = false

            fun maybeFinish() {
                if (finished) return
                val result = captureResult ?: return
                val bytes = jpegBytes ?: return
                if (wantsRaw && rawImage == null) return
                finished = true
                cont.resume(CapturedPhoto(bytes, rawImage, result, chars))
            }

            jpeg.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                jpegBytes = bytes
                maybeFinish()
            }, backgroundHandler)

            if (wantsRaw) {
                raw!!.setOnImageAvailableListener({ reader ->
                    rawImage = reader.acquireLatestImage()
                    maybeFinish()
                }, backgroundHandler)
            }

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    captureResult = result
                    maybeFinish()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (!finished) {
                        finished = true
                        cont.resumeWithException(RuntimeException("Capture failed: reason=${failure.reason}"))
                    }
                }
            }, backgroundHandler)
        }

    /** Tears down the preview-only session and rebuilds one that also targets a freshly prepared
     * [MediaRecorder]'s input surface, matching the standard Camera2 pattern for still+video
     * capture from one camera: the recorder's surface has to exist before the capture session
     * that includes it is created, so [MediaRecorder.prepare] has to run first. */
    fun supportedVideoResolutions(): List<VideoRes> {
        val id = currentCameraId.toIntOrNull() ?: return listOf(VideoRes.P1080)
        return VideoRes.entries.filter { CamcorderProfile.hasProfile(id, it.quality) }.ifEmpty { listOf(VideoRes.P1080) }
    }

    fun supports60fps(): Boolean =
        characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.upper >= 60 } == true

    fun supportsHevc(): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/hevc", ignoreCase = true) } }
    } catch (e: Exception) {
        false
    }

    suspend fun startVideoRecording(outputFile: File, deviceRotationDegrees: Int, withAudio: Boolean, config: VideoConfig): Unit =
        suspendCancellableCoroutine { cont ->
            val device = cameraDevice
            val previewSurf = previewSurface
            if (device == null || previewSurf == null) {
                cont.resumeWithException(IllegalStateException("Camera not ready"))
                return@suspendCancellableCoroutine
            }

            // The still-capture readers aren't part of a video session's target surfaces (only
            // the shared preview surface and the recorder's surface are) -- close them here
            // rather than leaving them open and unused until the camera itself eventually closes.
            closeSessionResources()

            val recorder = MediaRecorder()
            val camIdInt = currentCameraId.toIntOrNull() ?: 0
            val profile = listOf(config.res.quality, CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P)
                .firstOrNull { CamcorderProfile.hasProfile(camIdInt, it) }
                ?.let { CamcorderProfile.get(camIdInt, it) }
                ?: CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_HIGH)
            val fps = if (config.fps >= 60 && supports60fps()) 60 else 30
            var bitrate = profile.videoBitRate
            if (fps == 60) bitrate = (bitrate * 1.5).toInt()
            if (config.hevc) bitrate = (bitrate * 0.7).toInt()

            try {
                if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(if (config.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
                recorder.setVideoFrameRate(fps)
                recorder.setVideoEncodingBitRate(bitrate)
                if (withAudio) {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(profile.audioBitRate)
                    recorder.setAudioSamplingRate(profile.audioSampleRate)
                    recorder.setAudioChannels(profile.audioChannels)
                }
                recorder.setOrientationHint(sensorToJpegOrientation(deviceRotationDegrees))
                recorder.setOutputFile(outputFile.absolutePath)
                recorder.prepare()
            } catch (e: Exception) {
                recorder.release()
                cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            videoFps = fps

            val recorderSurf = recorder.surface
            recorderSurface = recorderSurf
            mediaRecorder = recorder

            device.createCaptureSession(listOf(previewSurf, recorderSurf), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(previewSurf)
                    builder.addTarget(recorderSurf)
                    applyControls(builder)
                    if (videoFps >= 60) {
                        characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                            ?.filter { it.upper >= 60 }
                            ?.maxByOrNull { it.lower }
                            ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }
                    session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                    recorder.start()
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Video session configuration failed"))
                }
            }, backgroundHandler)
        }

    /** Stops and releases the recorder, then hands back to the caller to rebuild a plain preview
     * session via [startPreviewSession] -- this only tears the recorder down, since rebuilding
     * needs a live SurfaceTexture/size the engine doesn't hold onto between calls. */
    fun stopVideoRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            // Stop can throw if called too soon after start with no frames recorded yet; the
            // output file is simply unusable in that case, nothing left to salvage.
        }
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
        recorderSurface = null
        captureSession?.close()
        captureSession = null
    }
}
