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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Not private: [AppSettingsScreen] reads/writes this directly for the "Bass Drop Vibration"
 * toggle -- colocated here rather than in a shared settings file since this mode is the only
 * reader. */
internal const val KEY_BURST_HAPTICS = "radial_burst_haptics"

/** Only the lowest bands feed the onset trigger -- sub-bass/kick energy specifically, not the
 * whole spectrum's average the way a general onset detector would use. The rays themselves (see
 * [RadialSpectrumBurstScreen]) still read every band; only the *trigger* is bass-scoped. */
private const val BASS_BAND_COUNT = 4

/** How fast the rolling bass-energy baseline adapts, as a time constant -- short enough that a
 * sustained bassline stops re-triggering once the baseline catches up, so this reads as "hits
 * relative to what's been playing" rather than "loud in an absolute sense." */
private const val ENERGY_BASELINE_TAU_SECONDS = 1.2f

/** A drop fires when bass energy exceeds the rolling baseline by at least this much, divided by
 * [BarSpectrumSettings.scale] -- a higher Scale is more sensitive (more triggers). This is an
 * additive delta, not a ratio: [SpectrumEngine.bands] is already a dB-normalized value hard-clamped
 * to [0, 1] (see [SpectrumAnalyzer.computeBands]), and real music rarely leaves the bass bands near
 * silence, so a ratio threshold like "1.6x the baseline" becomes unreachable the moment the rolling
 * baseline climbs past 1f / 1.6 -- at that point `baseline * 1.6` exceeds the maximum possible
 * bassEnergy of 1f, and the mode can never trigger again. An additive delta stays reachable no
 * matter where the baseline sits in the bounded range. Carried over unchanged from Bass Drop
 * Shockwave, the mode this one replaces -- the onset detector itself was never the bug. */
private const val BASE_ONSET_DELTA_THRESHOLD = 0.15f

private const val MIN_ABSOLUTE_ENERGY = 0.06f

/** Debounce -- kicks are naturally spaced further apart than general transients, so this is wider
 * than a generic onset detector would need. */
private const val MIN_RETRIGGER_SECONDS = 0.2f

/** Ray geometry. Every band gets a ray regardless of level, so the resting shape already reads as
 * a full 360-degree burst rather than nothing until a band lights up. */
private const val BASE_RAY_LENGTH_FRACTION = 0.06f
private const val MAX_RAY_LENGTH_FRACTION = 0.42f
private const val RAY_AMPLITUDE_GAMMA = 0.75f
private const val STROKE_WIDTH_FRACTION = 0.010f

/** One-shot ripple fired on a genuine bass-drop trigger -- pure function of time-since-trigger,
 * not a spring: this mode's primary motion already lives in the continuously-live rays, so the
 * drop flourish only needs to read as a single clean pulse layered on top, not its own physics. */
private const val RIPPLE_DURATION_SECONDS = 0.7f
private const val RIPPLE_MAX_RADIUS_FRACTION = 0.55f
private const val RIPPLE_STROKE_WIDTH_FRACTION = 0.012f

/** Full-screen tint on a hit -- brief and subtle, shorter than the ripple so it reads as a flash
 * rather than a fade. */
private const val FLASH_DURATION_SECONDS = 0.15f
private const val FLASH_ALPHA_MAX = 70

private const val GLOW_RADIUS_FRACTION = 0.030f
private const val GLOW_ALPHA = 170

/** Whether a bass-drop onset fires this frame -- pure and side-effect-free (no holder mutation)
 * so the trigger condition itself can be unit tested independent of the Compose Canvas rendering
 * below. Identical logic to Bass Drop Shockwave's `bassDropShouldTrigger`, just renamed: this was
 * never the part of that mode that was broken. */
internal fun radialBurstShouldTrigger(
    bassEnergy: Float,
    baseline: Float,
    timeSinceTriggerSeconds: Float,
    scale: Float,
): Boolean {
    val threshold = BASE_ONSET_DELTA_THRESHOLD / scale
    return timeSinceTriggerSeconds >= MIN_RETRIGGER_SECONDS &&
        bassEnergy >= MIN_ABSOLUTE_ENERGY &&
        (bassEnergy - baseline) >= threshold
}

/**
 * Replaces Bass Drop Shockwave. That mode's ring only ever reacted to [BASS_BAND_COUNT] bass
 * bands and otherwise sat still (aside from a small continuous term added after the fact to
 * paper over exactly this) -- it read as a bass-only novelty rather than a real spectrum
 * visualizer. This mode inverts the balance: the *primary* visual is a full 360-degree burst of
 * rays, one per [SpectrumEngine] band (all of them, not just the bass ones), radiating from
 * center like sun rays -- every band is continuously, visibly live at all times, the same
 * always-on principle every other mode in this app already follows. Band 0 sits at 12 o'clock
 * and rays sweep clockwise, matching the low-to-high left-to-right convention every other mode
 * uses.
 *
 * A genuine bass drop still gets its own moment, layered on top rather than replacing the rays:
 * the exact same onset detector Bass Drop Shockwave used ([radialBurstShouldTrigger], scoped to
 * only the lowest [BASS_BAND_COUNT] bands) fires a single one-shot expanding ring plus a brief
 * full-screen flash, both pure functions of time-since-trigger rather than a spring simulation --
 * this mode doesn't need a spring's overshoot-and-settle physics because the rays already carry
 * all the continuous motion; the ripple only has to read as one clean pulse, not its own physical
 * object.
 *
 * [BarSpectrumSettings.scale] ("Sensitivity") does double duty: it drives both the onset
 * threshold (as before) *and* the rays' amplitude response, so turning it up makes the whole mode
 * visibly more reactive in one coherent motion instead of only affecting an invisible trigger
 * threshold the way Bass Drop Shockwave's Scale did.
 */
@Composable
fun RadialSpectrumBurstScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val context = LocalContext.current
    val rayHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val energyBaselineHolder = remember { floatArrayOf(0f) }
    val timeSinceTriggerHolder = remember { floatArrayOf(Float.MAX_VALUE) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var rays = rayHolder[0]
        if (rays == null || rays.width != widthPx || rays.height != heightPx) {
            rays = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            rayHolder[0] = rays
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        val bandCount = engine.bands.size.coerceAtLeast(1)
        val bassBandCount = bandCount.coerceAtMost(BASS_BAND_COUNT)
        var bassEnergy = 0f
        for (b in 0 until bassBandCount) bassEnergy += engine.bands[b].coerceIn(0f, 1f)
        bassEnergy /= bassBandCount

        val baselineAlpha = 1f - exp(-dt / ENERGY_BASELINE_TAU_SECONDS)
        energyBaselineHolder[0] += (bassEnergy - energyBaselineHolder[0]) * baselineAlpha
        timeSinceTriggerHolder[0] += dt

        val canTrigger = radialBurstShouldTrigger(bassEnergy, energyBaselineHolder[0], timeSinceTriggerHolder[0], settings.scale)
        if (canTrigger) {
            timeSinceTriggerHolder[0] = 0f
            // Read here rather than every frame -- a SharedPreferences lookup only on an actual
            // trigger (a sparse event, not per-frame) costs nothing worth avoiding.
            if (SettingsStore.getBoolean(context, KEY_BURST_HAPTICS, true)) {
                HapticPulse.fire(context)
            }
        }

        val minDim = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val heightScale = settings.height / BarSpectrumSettings.HEIGHT_MAX
        val accentArgb = VisualizerTheme.ACCENT.toArgb()

        val rayCanvas = AndroidCanvas(rays)
        rayCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val rayPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = minDim * STROKE_WIDTH_FRACTION * settings.strokeWeight
            strokeCap = AndroidPaint.Cap.ROUND
        }
        for (b in 0 until bandCount) {
            val level = (engine.bands[b].coerceIn(0f, 1f).pow(RAY_AMPLITUDE_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val rayLength = minDim * (BASE_RAY_LENGTH_FRACTION + MAX_RAY_LENGTH_FRACTION * level) * heightScale
            val angleDeg = (b.toFloat() / bandCount) * 360f - 90f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val endX = center.x + (cos(angleRad) * rayLength).toFloat()
            val endY = center.y + (sin(angleRad) * rayLength).toFloat()
            rayPaint.color = frequencyZoneColor(b.toFloat() / (bandCount - 1).coerceAtLeast(1)).toArgb()
            rayCanvas.drawLine(center.x, center.y, endX, endY, rayPaint)
        }

        val ripplePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = minDim * RIPPLE_STROKE_WIDTH_FRACTION * settings.strokeWeight
            color = accentArgb
        }
        val rippleT = (timeSinceTriggerHolder[0] / RIPPLE_DURATION_SECONDS).coerceIn(0f, 1f)
        if (timeSinceTriggerHolder[0] < RIPPLE_DURATION_SECONDS) {
            ripplePaint.alpha = ((1f - rippleT) * 255).toInt().coerceIn(0, 255)
            rayCanvas.drawCircle(center.x, center.y, minDim * RIPPLE_MAX_RADIUS_FRACTION * rippleT, ripplePaint)
        }

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(rays, 0f, 0f, glowPaint)

        val flashT = (timeSinceTriggerHolder[0] / FLASH_DURATION_SECONDS).coerceIn(0f, 1f)
        val flashAlpha = if (timeSinceTriggerHolder[0] < FLASH_DURATION_SECONDS) {
            ((1f - flashT) * FLASH_ALPHA_MAX).toInt().coerceIn(0, 255)
        } else {
            0
        }

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        if (flashAlpha > 0) {
            drawRect(color = VisualizerTheme.ACCENT.copy(alpha = flashAlpha / 255f))
        }
        drawImage(glow.asImageBitmap())
        drawImage(rays.asImageBitmap())
    }
}
