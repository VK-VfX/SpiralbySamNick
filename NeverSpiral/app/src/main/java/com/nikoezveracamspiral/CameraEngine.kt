package com.nikoezveracamspiral

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
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
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        previewSurface = null
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
        cameraDevice?.close()
        cameraDevice = null
        characteristics = null
    }

    private fun chooseJpegSize(): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)
        return sizes?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)
    }

    private fun chooseRawSize(): Size? {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
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

    fun maxZoomRatio(): Float = characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

    suspend fun startPreviewSession(
        texture: SurfaceTexture,
        previewSize: Size,
        includeRaw: Boolean,
    ): Unit = suspendCancellableCoroutine { cont ->
        val device = cameraDevice
        if (device == null) {
            cont.resumeWithException(IllegalStateException("Camera not open"))
            return@suspendCancellableCoroutine
        }

        closeSessionResources()
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)
        previewSurface = surface

        val jpegSize = chooseJpegSize()
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        jpegReader = reader

        val targets = mutableListOf(surface, reader.surface)

        if (includeRaw && isRawSupported) {
            chooseRawSize()?.let { rawSize ->
                val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                rawReader = raw
                targets.add(raw.surface)
            }
        }

        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                startRepeatingPreview()
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("Capture session configuration failed"))
            }
        }, backgroundHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        // Hook for live AF/AE state (e.g. a focus-locked indicator) in a later iteration -- the
        // repeating request's result stream isn't needed for anything in this first build.
    }

    fun startRepeatingPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        val template = if (mediaRecorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val builder = device.createCaptureRequest(template)
        builder.addTarget(surface)
        recorderSurface?.let { builder.addTarget(it) }
        applyControls(builder)
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
    private fun applyControls(builder: CaptureRequest.Builder) {
        val chars = characteristics ?: return

        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (activeArray != null && zoomRatio > 1f) {
            val cropWidth = (activeArray.width() / zoomRatio).toInt().coerceAtLeast(1)
            val cropHeight = (activeArray.height() / zoomRatio).toInt().coerceAtLeast(1)
            val left = (activeArray.width() - cropWidth) / 2
            val top = (activeArray.height() - cropHeight) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
        }

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, proSettings.whiteBalanceMode.awbMode)

        if (proSettings.manualExposureEnabled) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, proSettings.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proSettings.exposureTimeNanos)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, proSettings.exposureTimeNanos)
        } else {
            val aeMode = if (flashMode == FlashMode.ON) {
                CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            } else {
                CameraMetadata.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, proSettings.exposureCompensation)
        }

        if (proSettings.manualFocusEnabled) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, proSettings.focusDistance)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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
        zoomRatio = ratio.coerceIn(1f, maxZoomRatio())
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
        val cropWidth = activeArray.width() / zoomRatio.coerceAtLeast(1f)
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
        if (!proSettings.manualFocusEnabled) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        }

        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (_: IllegalStateException) {
            // Session torn down concurrently; nothing to apply the metering region to anymore.
        }
    }

    private fun sensorToJpegOrientation(deviceRotationDegrees: Int): Int {
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facingFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        return if (facingFront) {
            (sensorOrientation + deviceRotationDegrees) % 360
        } else {
            (sensorOrientation - deviceRotationDegrees + 360) % 360
        }
    }

    suspend fun capturePhoto(deviceRotationDegrees: Int, includeRaw: Boolean): CapturedPhoto =
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
    suspend fun startVideoRecording(outputFile: File, deviceRotationDegrees: Int): Unit =
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
            captureSession?.close()
            captureSession = null
            jpegReader?.close()
            jpegReader = null
            rawReader?.close()
            rawReader = null

            val recorder = MediaRecorder()
            val camIdInt = currentCameraId.toIntOrNull() ?: 0
            val profile = when {
                CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_1080P) ->
                    CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_1080P)
                CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_720P) ->
                    CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_720P)
                else -> CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_HIGH)
            }

            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setProfile(profile)
                recorder.setOrientationHint(sensorToJpegOrientation(deviceRotationDegrees))
                recorder.setOutputFile(outputFile.absolutePath)
                recorder.prepare()
            } catch (e: Exception) {
                recorder.release()
                cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            }

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
