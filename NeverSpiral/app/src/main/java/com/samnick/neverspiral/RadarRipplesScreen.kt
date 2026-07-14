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
import kotlin.math.pow

private const val MAX_RIPPLES = 8

/** How fast the rolling energy baseline adapts to the music's actual current loudness, expressed
 * as a time constant. Short enough that a sustained loud passage stops re-triggering once the
 * baseline catches up (this is what makes triggers read as "transients relative to what's been
 * playing" rather than "loud in an absolute sense"), long enough that a single beat doesn't drag
 * the baseline up with it. */
private const val ENERGY_BASELINE_TAU_SECONDS = 1.8f

/** A ripple fires when the current frame's band energy exceeds the rolling baseline by this
 * ratio, divided by [BarSpectrumSettings.scale] -- a higher Scale setting lowers the bar (more
 * sensitive, more ripples), matching Scale's role as an amplitude/sensitivity multiplier
 * everywhere else in the app. */
private const val BASE_ONSET_RATIO_THRESHOLD = 1.5f

/** Floor on absolute energy before a ripple can fire at all, regardless of the ratio -- without
 * this, near-silence's tiny fluctuations can spuriously exceed an equally tiny baseline. */
private const val MIN_ABSOLUTE_ENERGY = 0.05f

/** Debounce: the minimum gap between two ripples, so one loud transient can't spawn several
 * overlapping rings before the baseline has a chance to catch up. */
private const val MIN_RETRIGGER_SECONDS = 0.14f

private const val RIPPLE_LIFE_SECONDS = 1.1f
private const val RIPPLE_BASE_RADIUS_FRACTION = 0.04f
private const val RIPPLE_MAX_RADIUS_FRACTION = 0.62f
private const val STROKE_WIDTH_FRACTION = 0.012f
private const val GLOW_RADIUS_FRACTION = 0.03f
private const val GLOW_ALPHA = 160

/**
 * A sonar/radar-style mode -- deliberately event-driven rather than continuously reactive every
 * frame like every other mode in the app. A lightweight onset detector (a rolling exponential
 * average of [SpectrumEngine]'s total band energy, [ENERGY_BASELINE_TAU_SECONDS]) fires a new
 * expanding ring whenever the current frame's energy jumps well above that recent baseline -- a
 * real transient/beat detector, not full tempo tracking, but enough to read as "the ring pulses on
 * the beat" rather than "the ring pulses every frame like a bar meter." Debounced
 * ([MIN_RETRIGGER_SECONDS]) so one loud hit can't spawn a stack of overlapping rings.
 *
 * Each ripple is colored by a rough spectral centroid of the bands at the instant it fired
 * (energy-weighted average band position, mapped through [frequencyZoneColor]) -- a bass-heavy hit
 * reads warm, a treble-heavy hit reads cool, without needing any data beyond the bands already
 * being read for onset detection. Ripples are a small fixed-size pool ([MAX_RIPPLES], plenty since
 * they're comparatively rare events with a visible lifetime), each expanding with an ease-out
 * curve (fast at first, slowing as it grows, like a real shockwave losing energy) and fading out
 * over [RIPPLE_LIFE_SECONDS], both derived directly from each ripple's own age rather than
 * accumulated per-frame, so a ripple's size and fade are always an exact function of elapsed time
 * regardless of how many frames rendered while it was alive. Glow is the same
 * draw-solid-then-blur-once technique used everywhere else in the app.
 */
@Composable
fun RadarRipplesScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val ripples = remember { Array(MAX_RIPPLES) { Ripple() } }
    val ringHolder = remember { arrayOfNulls<Bitmap>(1) }
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

        val bandCount = engine.bands.size
        var totalEnergy = 0f
        var weightedIndex = 0f
        for (b in 0 until bandCount) {
            val level = engine.bands[b].coerceIn(0f, 1f)
            totalEnergy += level
            weightedIndex += level * b
        }
        val currentEnergy = totalEnergy / bandCount

        val baselineAlpha = 1f - exp(-dt / ENERGY_BASELINE_TAU_SECONDS)
        energyBaselineHolder[0] += (currentEnergy - energyBaselineHolder[0]) * baselineAlpha
        timeSinceTriggerHolder[0] += dt

        val threshold = BASE_ONSET_RATIO_THRESHOLD / settings.scale
        val canTrigger = timeSinceTriggerHolder[0] >= MIN_RETRIGGER_SECONDS &&
            currentEnergy >= MIN_ABSOLUTE_ENERGY &&
            currentEnergy >= energyBaselineHolder[0] * threshold
        if (canTrigger) {
            // Prefer a genuinely free slot; if the pool is full, steal whichever ripple is
            // closest to finishing (largest age/life ratio) rather than interrupting a young one.
            val slot = ripples.firstOrNull { !it.alive } ?: ripples.maxByOrNull { it.age / it.life.coerceAtLeast(0.001f) }
            if (slot != null) {
                val centroid = if (totalEnergy > 0f) weightedIndex / totalEnergy else 0f
                slot.age = 0f
                slot.life = RIPPLE_LIFE_SECONDS
                slot.alive = true
                slot.color = frequencyZoneColor(centroid / (bandCount - 1).coerceAtLeast(1)).toArgb()
            }
            timeSinceTriggerHolder[0] = 0f
        }

        val center = Offset(size.width / 2f, size.height / 2f)
        val minDim = size.minDimension
        val maxRadius = minDim * RIPPLE_MAX_RADIUS_FRACTION * (settings.height / BarSpectrumSettings.HEIGHT_MAX)
        val baseRadius = minDim * RIPPLE_BASE_RADIUS_FRACTION
        val ringStrokeWidth = minDim * STROKE_WIDTH_FRACTION * settings.strokeWeight

        val ringCanvas = AndroidCanvas(ring)
        ringCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val ringPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = ringStrokeWidth
        }

        for (r in ripples) {
            if (!r.alive) continue
            r.age += dt
            if (r.age >= r.life) {
                r.alive = false
                continue
            }
            val progress = (r.age / r.life).coerceIn(0f, 1f)
            val eased = 1f - (1f - progress).pow(2)
            val radius = baseRadius + (maxRadius - baseRadius) * eased
            val alpha = (1f - progress).pow(1.5f)

            ringPaint.color = r.color
            ringPaint.alpha = (alpha * 255).toInt()
            ringCanvas.drawCircle(center.x, center.y, radius, ringPaint)
        }

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(ring, 0f, 0f, glowPaint)

        drawRect(color = Color.Black)
        drawImage(glow.asImageBitmap())
        drawImage(ring.asImageBitmap())
    }
}

/** One reusable ripple slot -- plain mutable fields so the pool is allocated once and mutated in
 * place, the same reasoning as [Firefly] in AudioFirefliesScreen. */
private class Ripple {
    var alive = false
    var age = 0f
    var life = 0f
    var color = 0
}
