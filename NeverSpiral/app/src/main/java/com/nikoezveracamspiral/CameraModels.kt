package com.nikoezveracamspiral

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.Image

/** The modes the bottom mode carousel switches between, mirroring the ones Google Camera and
 * most manual camera apps expose. [PORTRAIT], [PANORAMA], and [SLOW_MOTION] are wired into the
 * carousel in this first build but their capture pipelines ship in a follow-up round (software
 * background blur, multi-frame stitching, and constrained-high-speed capture all need more room
 * than the core Photo/Pro/Video pipeline). */
enum class CaptureMode(val label: String) {
    NIGHT("Night"),
    PHOTO("Photo"),
    PRO("Pro"),
    VIDEO("Video"),
    PORTRAIT("Portrait"),
    MACRO("Macro"),
    PANORAMA("Pano"),
    SLOW_MOTION("Slo-mo");

    /** Video modes share the bottom Photo/Video toggle's "video" side and record instead of
     * taking stills. */
    val isVideo: Boolean get() = this == VIDEO || this == SLOW_MOTION

    /** Modes whose dedicated capture pipeline isn't built yet; they fall back to a standard photo
     * (or standard video, for slow-motion) and the viewfinder says so. */
    val isComingSoon: Boolean get() = this == PANORAMA || this == SLOW_MOTION
}

enum class FlashMode(val glyph: String) {
    OFF("⚡🚫"),
    AUTO("A"),
    ON("⚡"),
    TORCH("🔦"),
}

/** Android's manual-exposure white balance is simplest to drive through the sensor's own
 * calibrated AWB presets (CONTROL_AWB_MODE) rather than hand-computing color-correction gains
 * from a Kelvin value -- the presets are per-device calibrated, a guessed Kelvin-to-RGB formula
 * isn't. */
enum class WhiteBalanceMode(val label: String, val awbMode: Int) {
    AUTO("AUTO", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("DAYLIGHT", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("CLOUDY", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("SHADE", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    TWILIGHT("TWILIGHT", CameraMetadata.CONTROL_AWB_MODE_TWILIGHT),
    TUNGSTEN("TUNGSTEN", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("FLUORESCENT", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    WARM_FLUORESCENT("WARM FLUOR.", CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT),
}

/**
 * Pro mode's manual capture parameters. Exposure (ISO + shutter) and focus are each
 * independently toggleable between manual and automatic -- a real manual camera lets you, say,
 * lock focus while exposure still rides auto, so this mirrors that rather than an all-or-nothing
 * manual switch. [exposureCompensation] only applies while [manualExposureEnabled] is false
 * (manual exposure sets absolute ISO/shutter directly, so EV compensation on top of it is
 * meaningless -- CONTROL_AE_EXPOSURE_COMPENSATION is simply not read by the camera HAL once
 * CONTROL_AE_MODE is OFF).
 */
data class ProSettings(
    val manualExposureEnabled: Boolean = false,
    val iso: Int = 100,
    val exposureTimeNanos: Long = 1_000_000_000L / 60,
    val manualFocusEnabled: Boolean = false,
    val focusDistance: Float = 0f,
    val exposureCompensation: Int = 0,
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
)

/** The raw result of one still capture, handed off to [CaptureStore] to actually write out --
 * [rawImage] is only non-null when RAW capture was requested and the device supports it, and the
 * caller owns closing it (DngCreator.writeImage consumes it). */
data class CapturedPhoto(
    val jpegBytes: ByteArray,
    val rawImage: Image?,
    val captureResult: TotalCaptureResult,
    val characteristics: CameraCharacteristics,
)

enum class VideoRes(val label: String, val quality: Int) {
    P720("720p", CamcorderProfile.QUALITY_720P),
    P1080("1080p", CamcorderProfile.QUALITY_1080P),
    P2160("4K", CamcorderProfile.QUALITY_2160P),
}

/** Video capture settings. [fps] of 60 is only offered when the camera advertises it; [hevc]
 * trades compatibility for roughly 30% smaller files at the same quality. */
data class VideoConfig(val res: VideoRes = VideoRes.P1080, val fps: Int = 30, val hevc: Boolean = false)
