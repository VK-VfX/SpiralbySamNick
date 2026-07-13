package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.SweepGradient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Angular resolution -- how many points make up the ring. Denser than [SpectrumEngine]'s own
 * band count (wrapped with interpolation, same idea as Neon Cyan Pulse's denser bar row) so the
 * bezier-smoothed outline reads as a fluid curve rather than a visibly faceted polygon. */
private const val POINT_COUNT = 96

/** Resting ring radius as a fraction of the canvas's shorter dimension -- leaves the whole center
 * open for a focal point (album art, a logo) to sit inside. */
private const val BASE_RADIUS_FRACTION = 0.30f

/** How far a point can push outward from the base radius at full level, before [BarSpectrumSettings.height]. */
private const val MAX_PUSH_FRACTION = 0.34f

/** Exponent applied to each point's amplitude before it drives radius: <1 lifts quiet content so
 * the ring visibly breathes even at moderate volume, matching the other bar-spectrum modes. */
private const val AMPLITUDE_SENSITIVITY_GAMMA = 0.85f

/** Fast push-out, expressed as a time-based rate (a decay/rise "tau", not a flat per-frame
 * multiplier) so it converts correctly via delta time regardless of the display's refresh rate --
 * see MainActivity's refresh-rate handling and every engine's step(dt, ...) for the same pattern. */
private const val ATTACK_TAU_SECONDS = 0.05f

/** Slower settle back to the resting radius once a transient passes -- roughly 8x [ATTACK_TAU_SECONDS]. */
private const val DECAY_TAU_SECONDS = 0.4f

private const val STROKE_WIDTH_FRACTION = 0.014f

/** Soft, wide falloff behind the crisp line -- the "softer outer glow falloff" half of the bloom. */
private const val GLOW_OUTER_RADIUS_FRACTION = 0.05f
private const val GLOW_OUTER_ALPHA = 110

/** Tight, bright halo hugging the line -- the "brighter hot edge" half of the bloom. */
private const val GLOW_INNER_RADIUS_FRACTION = 0.016f
private const val GLOW_INNER_ALPHA = 200

/** Hue stops for the ring's circumference gradient -- see [fullHueSweepColors] for why this needs
 * more than a handful of stops to read as a clean rainbow rather than muddy blended off-hues. */
private const val HUE_STEPS = 12

/**
 * A closed, deformed ring wrapping an open center -- Specterr-style radial waveform -- built from
 * the same [SpectrumEngine] bands every other bar-spectrum mode already reads, just mapped around
 * a circle instead of laid out in a row. Each of [POINT_COUNT] angular points gets its own target
 * level (bands interpolated with wraparound, since the ring has no start/end seam), independently
 * smoothed with a fast attack / slower decay so bass hits punch the ring outward quickly and it
 * eases back to resting radius rather than snapping -- deliberately a second, ring-specific
 * smoothing pass on top of [SpectrumEngine]'s own fast-rise/slow-fall band smoothing, since the
 * user-tunable "how punchy the ring itself feels" is a property of this mode's rendering, not the
 * shared band data underneath it.
 *
 * The outline is a closed quadratic-bezier-through-midpoints path (moveTo the midpoint before
 * point 0, then quadTo each point with the following midpoint as the endpoint) rather than a
 * jagged point-to-point polygon, so the ring reads as a fluid, organic blob instead of a spiky
 * star even with a comparatively low point count.
 *
 * Color is a true closed 360-degree hue sweep ([fullHueSweepColors]) applied as a `SweepGradient`
 * centered on the ring -- unlike [RAINBOW_STOPS], which is a deliberately non-looping gradient
 * tuned for a straight bar row, a closed ring needs a hue wheel that wraps back to its own start
 * with no seam. Glow is two blurred copies of the same solid line composited underneath the crisp
 * one: a wide soft outer pass and a tight bright inner pass, for the "brighter hot edge + softer
 * outer glow falloff" look rather than one uniform blur radius.
 */
@Composable
fun RadialPulseRingScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val ringHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowOuterHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowInnerHolder = remember { arrayOfNulls<Bitmap>(1) }
    val pushLevels = remember { FloatArray(POINT_COUNT) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val hueColors = remember { fullHueSweepColors(HUE_STEPS) }
    val huePositions = remember { fullHueSweepPositions(HUE_STEPS) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual band data lives in a plain array that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        // engine.elapsed already accumulates real per-frame delta time (see SpectrumEngine.step,
        // driven by MainScreen's withFrameNanos loop), so diffing it here gives this mode its own
        // genuine dt without needing a separate Choreographer callback of its own.
        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var ring = ringHolder[0]
        if (ring == null || ring.width != widthPx || ring.height != heightPx) {
            ring = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            ringHolder[0] = ring
        }
        var glowOuter = glowOuterHolder[0]
        if (glowOuter == null || glowOuter.width != widthPx || glowOuter.height != heightPx) {
            glowOuter = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowOuterHolder[0] = glowOuter
        }
        var glowInner = glowInnerHolder[0]
        if (glowInner == null || glowInner.width != widthPx || glowInner.height != heightPx) {
            glowInner = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowInnerHolder[0] = glowInner
        }

        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * BASE_RADIUS_FRACTION
        val maxPush = size.minDimension * MAX_PUSH_FRACTION * (settings.height / BarSpectrumSettings.HEIGHT_MAX)

        val points = Array(POINT_COUNT) { i ->
            val target = (circularInterpolatedBand(engine.bands, i, POINT_COUNT)
                .pow(AMPLITUDE_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val tau = if (target > pushLevels[i]) ATTACK_TAU_SECONDS else DECAY_TAU_SECONDS
            val alpha = 1f - exp(-dt / tau)
            pushLevels[i] += (target - pushLevels[i]) * alpha

            val radius = baseRadius + pushLevels[i] * maxPush
            val angle = Math.toRadians((i.toFloat() / POINT_COUNT) * 360.0 - 90.0)
            Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
        }

        val path = AndroidPath()
        val firstMid = midpoint(points[0], points[POINT_COUNT - 1])
        path.moveTo(firstMid.x, firstMid.y)
        for (i in 0 until POINT_COUNT) {
            val current = points[i]
            val next = points[(i + 1) % POINT_COUNT]
            val mid = midpoint(current, next)
            path.quadTo(current.x, current.y, mid.x, mid.y)
        }
        path.close()

        val strokeWidth = size.minDimension * STROKE_WIDTH_FRACTION * settings.strokeWeight
        val sweepShader = SweepGradient(center.x, center.y, hueColors, huePositions)

        val ringCanvas = AndroidCanvas(ring)
        ringCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val ringPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            strokeWidth = strokeWidth
            shader = sweepShader
        }
        ringCanvas.drawPath(path, ringPaint)

        val glowOuterCanvas = AndroidCanvas(glowOuter)
        glowOuterCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowOuterPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_OUTER_ALPHA
            maskFilter = BlurMaskFilter(size.minDimension * GLOW_OUTER_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowOuterCanvas.drawBitmap(ring, 0f, 0f, glowOuterPaint)

        val glowInnerCanvas = AndroidCanvas(glowInner)
        glowInnerCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowInnerPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_INNER_ALPHA
            maskFilter = BlurMaskFilter(size.minDimension * GLOW_INNER_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowInnerCanvas.drawBitmap(ring, 0f, 0f, glowInnerPaint)

        drawRect(color = Color.Black)
        drawImage(glowOuter.asImageBitmap())
        drawImage(glowInner.asImageBitmap())
        drawImage(ring.asImageBitmap())
    }
}

private fun midpoint(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

/**
 * Same idea as Neon Cyan Pulse's `interpolatedBand`, but wraps around instead of clamping at the
 * ends -- point 0 and point [totalPoints] - 1 are angular neighbors on a closed ring, not the two
 * unrelated edges of a bar row, so the interpolation has to be circular or there'd be a visible
 * seam where the ring meets itself.
 */
private fun circularInterpolatedBand(bands: FloatArray, i: Int, totalPoints: Int): Float {
    if (bands.isEmpty()) return 0f
    val sourcePos = i.toFloat() * bands.size / totalPoints
    val lowIndex = sourcePos.toInt() % bands.size
    val highIndex = (lowIndex + 1) % bands.size
    val frac = sourcePos - sourcePos.toInt()
    return bands[lowIndex] + (bands[highIndex] - bands[lowIndex]) * frac
}
