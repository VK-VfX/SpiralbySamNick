package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.exp

/** Only the lowest bands feed the trigger -- sub-bass/kick energy specifically, not the whole
 * spectrum's average the way a general onset detector would use. */
private const val BASS_BAND_COUNT = 4

/** How fast the rolling bass-energy baseline adapts, as a time constant -- short enough that a
 * sustained bassline stops re-triggering once the baseline catches up, so this reads as "hits
 * relative to what's been playing" rather than "loud in an absolute sense." */
private const val ENERGY_BASELINE_TAU_SECONDS = 1.2f

/** A drop fires when bass energy exceeds the rolling baseline by this ratio, divided by
 * [BarSpectrumSettings.scale] -- a higher Scale is more sensitive (more triggers). */
private const val BASE_ONSET_RATIO_THRESHOLD = 1.6f

private const val MIN_ABSOLUTE_ENERGY = 0.06f

/** Debounce -- kicks are naturally spaced further apart than general transients, so this is wider
 * than a generic onset detector would need. */
private const val MIN_RETRIGGER_SECONDS = 0.2f

/** Damped-spring constants for the shockwave's response -- an underdamped oscillator (damping
 * ratio well under 1) so a hit visibly overshoots past its resting size and springs back rather
 * than just easing out to zero, the "snap" this mode is built around. Tuned for a couple of
 * visible bounces before settling, not a slow wobble. */
private const val SPRING_STIFFNESS = 80f
private const val SPRING_DAMPING = 6f

private const val RESTING_RADIUS_FRACTION = 0.10f
private const val MAX_RADIUS_FRACTION = 0.55f
private const val STROKE_WIDTH_FRACTION = 0.014f

/** Full-screen tint on a hit -- subtle, not a blinding flash. */
private const val FLASH_ALPHA_MAX = 90

private const val GLOW_RADIUS_FRACTION = 0.035f
private const val GLOW_ALPHA = 180

/**
 * A sparse, event-driven mode -- deliberately quiet between hits rather than continuously busy the
 * way every other mode in the app is. A dedicated onset detector reads only [BASS_BAND_COUNT] of
 * [SpectrumEngine]'s lowest bands (not the whole-spectrum average a general transient detector
 * would use), comparing that against its own rolling baseline so a genuine kick/bass hit fires a
 * "drop" independent of how loud the rest of the mix is.
 *
 * Each drop drives a real damped-spring simulation rather than an eased fade: `displacement` snaps
 * to 1 on trigger, and every frame `acceleration = -stiffness * displacement - damping * velocity`,
 * `velocity += acceleration * dt`, `displacement += velocity * dt` -- semi-implicit Euler
 * integration, converted through real per-frame delta time like everything else in this app.
 * Tuned underdamped ([SPRING_STIFFNESS], [SPRING_DAMPING]), so a hit visibly overshoots past its
 * resting size, springs back, and rings out over a couple of bounces rather than easing straight to
 * zero the way Radial Pulse Ring or Radar Ripples did -- the whole point of this mode is that a hit
 * feels like a real physical thump, not a decay curve. The spring runs continuously (not just while
 * a drop is active), so it's always either mid-bounce or settled at rest -- there's no separate
 * "idle" branch to keep in sync.
 *
 * Rendering is deliberately minimal: a full-screen flash tinted with the app's own
 * [VisualizerTheme.ACCENT] (only on the positive half of the bounce, not the overshoot-past-zero
 * part) plus a single glowing ring whose radius directly tracks the spring's displacement --
 * allowing displacement to go slightly negative lets the ring visibly contract below its resting
 * size before springing back out, rather than clamping the bounce away.
 */
@Composable
fun BassDropShockwaveScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val ringHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val energyBaselineHolder = remember { floatArrayOf(0f) }
    val timeSinceTriggerHolder = remember { floatArrayOf(Float.MAX_VALUE) }
    val displacementHolder = remember { floatArrayOf(0f) }
    val velocityHolder = remember { floatArrayOf(0f) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var ring = ringHolder[0]
        if (ring == null || ring.width != widthPx || ring.height != heightPx) {
            ring = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            ringHolder[0] = ring
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        val bassBandCount = engine.bands.size.coerceAtMost(BASS_BAND_COUNT).coerceAtLeast(1)
        var bassEnergy = 0f
        for (b in 0 until bassBandCount) bassEnergy += engine.bands[b].coerceIn(0f, 1f)
        bassEnergy /= bassBandCount

        val baselineAlpha = 1f - exp(-dt / ENERGY_BASELINE_TAU_SECONDS)
        energyBaselineHolder[0] += (bassEnergy - energyBaselineHolder[0]) * baselineAlpha
        timeSinceTriggerHolder[0] += dt

        val threshold = BASE_ONSET_RATIO_THRESHOLD / settings.scale
        val canTrigger = timeSinceTriggerHolder[0] >= MIN_RETRIGGER_SECONDS &&
            bassEnergy >= MIN_ABSOLUTE_ENERGY &&
            bassEnergy >= energyBaselineHolder[0] * threshold
        if (canTrigger) {
            displacementHolder[0] = 1f
            velocityHolder[0] = 0f
            timeSinceTriggerHolder[0] = 0f
        }

        // Damped-spring integration -- runs every frame regardless of whether a trigger just
        // happened, so a hit's bounce plays out smoothly over subsequent frames on its own.
        val acceleration = -SPRING_STIFFNESS * displacementHolder[0] - SPRING_DAMPING * velocityHolder[0]
        velocityHolder[0] += acceleration * dt
        displacementHolder[0] += velocityHolder[0] * dt

        val minDim = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val heightScale = settings.height / BarSpectrumSettings.HEIGHT_MAX
        val restingRadius = minDim * RESTING_RADIUS_FRACTION
        val maxRadius = minDim * MAX_RADIUS_FRACTION * heightScale
        val radius = (restingRadius + displacementHolder[0] * maxRadius).coerceAtLeast(0f)
        val accentArgb = VisualizerTheme.ACCENT.toArgb()

        val ringCanvas = AndroidCanvas(ring)
        ringCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val ringPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = minDim * STROKE_WIDTH_FRACTION * settings.strokeWeight
            color = accentArgb
        }
        ringCanvas.drawCircle(center.x, center.y, radius, ringPaint)

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(ring, 0f, 0f, glowPaint)

        val flashAlpha = (displacementHolder[0].coerceAtLeast(0f) * FLASH_ALPHA_MAX).toInt().coerceIn(0, 255)

        drawRect(color = Color.Black)
        if (flashAlpha > 0) {
            drawRect(color = VisualizerTheme.ACCENT.copy(alpha = flashAlpha / 255f))
        }
        drawImage(glow.asImageBitmap())
        drawImage(ring.asImageBitmap())
    }
}
