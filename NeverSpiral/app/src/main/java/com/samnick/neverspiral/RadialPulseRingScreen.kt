package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.SweepGradient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Angular resolution -- how many spike positions ring the base circle. Denser than
 * [SpectrumEngine]'s own band count (wrapped with interpolation, same idea as Neon Cyan Pulse's
 * denser bar row), so the sunburst reads as a fine needle pattern rather than a handful of fat
 * spokes. */
private const val POINT_COUNT = 96

/** The fixed base circle's radius as a fraction of the canvas's shorter dimension -- this is the
 * one thing in the whole mode that audio data never touches. */
private const val BASE_RADIUS_FRACTION = 0.30f

/** How far a spike can extend beyond the base circle's edge at full level, before [BarSpectrumSettings.height]. */
private const val MAX_PUSH_FRACTION = 0.34f

/** Exponent applied to each spike's amplitude before it drives length: <1 lifts quiet content so
 * the ring visibly breathes even at moderate volume, matching the other bar-spectrum modes. */
private const val AMPLITUDE_SENSITIVITY_GAMMA = 0.85f

/** Fast push-out, expressed as a time-based rate (a decay/rise "tau", not a flat per-frame
 * multiplier) so it converts correctly via delta time regardless of the display's refresh rate --
 * see MainActivity's refresh-rate handling and every engine's step(dt, ...) for the same pattern. */
private const val ATTACK_TAU_SECONDS = 0.05f

/** Slower settle back to zero length once a transient passes -- roughly 8x [ATTACK_TAU_SECONDS]. */
private const val DECAY_TAU_SECONDS = 0.4f

private const val STROKE_WIDTH_FRACTION = 0.014f

/** One full turn every 60 seconds -- slow enough to read as ambient motion, not a spin. Like every
 * other rate in this mode, expressed per-second and applied via delta time, so it turns at the
 * same real-world speed regardless of the display's refresh rate. The base circle looks identical
 * at any rotation (it's a perfect circle), so only the spike pattern's slow spin is visible. */
private const val ROTATION_DEGREES_PER_SECOND = 6f

/** Soft, wide falloff behind the crisp line -- the "softer outer glow falloff" half of the bloom. */
private const val GLOW_OUTER_RADIUS_FRACTION = 0.05f
private const val GLOW_OUTER_ALPHA = 110

/** Tight, bright halo hugging the line -- the "brighter hot edge" half of the bloom. */
private const val GLOW_INNER_RADIUS_FRACTION = 0.016f
private const val GLOW_INNER_ALPHA = 200

/** Hue stops for the ring's circumference gradient -- see [fullHueSweepColors] for why this needs
 * more than a handful of stops to read as a clean rainbow rather than muddy blended off-hues. */
private const val HUE_STEPS = 12

/** The static center note glyph's own bounding box, as a fraction of the base circle's diameter --
 * comfortably inside the ring, clear of the spikes at rest. */
private const val NOTE_SIZE_FRACTION = 0.45f
private val NOTE_COLOR = Color(0xFFF2F2F2)

/**
 * A sunburst radial waveform -- Specterr-style -- built around a fixed, perfectly round base
 * circle that audio never deforms, with thin needle spikes shooting outward from its edge. Each of
 * [POINT_COUNT] angular positions gets its own target length (bands interpolated with wraparound,
 * since the ring has no start/end seam), independently smoothed with a fast attack / slower decay
 * so bass hits punch spikes outward quickly and they ease back to zero length rather than
 * snapping -- reusing exactly [SpectrumEngine]'s bands, just with a second, spike-specific
 * smoothing pass on top for how punchy the spikes themselves feel, same as every other
 * bar-spectrum mode's own rendering-level tuning.
 *
 * The base circle and every spike share one `SweepGradient` shader, so color always matches
 * canvas-space angle -- a spike picks up whatever hue sits at its current position, which is what
 * makes the slow constant rotation (applied as a degrees-per-second offset added to each spike's
 * angle, converted via delta time like everything else) actually visible without needing to touch
 * the color logic at all.
 *
 * Glow is two blurred copies of the same solid ring+spikes layer composited underneath the crisp
 * one -- a wide soft outer pass and a tight bright inner pass -- for a bright hot edge with a
 * softer outer falloff. A small static music-note glyph sits in the open center as the fixed
 * visual anchor, drawn last (after the glow and crisp layers) with Compose's own draw calls since
 * it needs no blur and never moves -- unlike the ring, it doesn't react to level or rotation.
 */
@Composable
fun RadialPulseRingScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val ringHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowOuterHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowInnerHolder = remember { arrayOfNulls<Bitmap>(1) }
    val pushLevels = remember { FloatArray(POINT_COUNT) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val rotationHolder = remember { floatArrayOf(0f) }
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
        rotationHolder[0] = (rotationHolder[0] + ROTATION_DEGREES_PER_SECOND * dt) % 360f

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
        val ringStrokeWidth = size.minDimension * STROKE_WIDTH_FRACTION * settings.strokeWeight
        val sweepShader = SweepGradient(center.x, center.y, hueColors, huePositions)

        val ringCanvas = AndroidCanvas(ring)
        ringCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val strokePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = ringStrokeWidth
            shader = sweepShader
        }

        // The base ring is a plain, perfectly round circle that audio data never touches --
        // drawn once as its own path, entirely independent of the spike loop below.
        ringCanvas.drawCircle(center.x, center.y, baseRadius, strokePaint)

        for (i in 0 until POINT_COUNT) {
            val target = (circularInterpolatedBand(engine.bands, i, POINT_COUNT)
                .pow(AMPLITUDE_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val tau = if (target > pushLevels[i]) ATTACK_TAU_SECONDS else DECAY_TAU_SECONDS
            val smoothingAlpha = 1f - exp(-dt / tau)
            pushLevels[i] += (target - pushLevels[i]) * smoothingAlpha

            val angle = Math.toRadians((i.toFloat() / POINT_COUNT) * 360.0 - 90.0 + rotationHolder[0])
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val spikeLength = pushLevels[i] * maxPush
            val inner = Offset(center.x + baseRadius * dx, center.y + baseRadius * dy)
            val outer = Offset(center.x + (baseRadius + spikeLength) * dx, center.y + (baseRadius + spikeLength) * dy)
            ringCanvas.drawLine(inner.x, inner.y, outer.x, outer.y, strokePaint)
        }

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
        drawMusicNote(center, baseRadius)
    }
}

/**
 * Same idea as Neon Cyan Pulse's `interpolatedBand`, but wraps around instead of clamping at the
 * ends -- point 0 and point [totalPoints] - 1 are angular neighbors on a closed ring, not the two
 * unrelated edges of a bar row, so the interpolation has to be circular or there'd be a visible
 * seam where the spike pattern meets itself.
 */
private fun circularInterpolatedBand(bands: FloatArray, i: Int, totalPoints: Int): Float {
    if (bands.isEmpty()) return 0f
    val sourcePos = i.toFloat() * bands.size / totalPoints
    val lowIndex = sourcePos.toInt() % bands.size
    val highIndex = (lowIndex + 1) % bands.size
    val frac = sourcePos - sourcePos.toInt()
    return bands[lowIndex] + (bands[highIndex] - bands[lowIndex]) * frac
}

/**
 * A simple static eighth-note glyph (filled head, stem, curved flag) centered on [center] and
 * sized off [baseRadius] -- the fixed anchor the ring and spikes surround. Deliberately drawn with
 * Compose's own draw calls rather than through the bitmap/blur pipeline above: it never moves and
 * never blurs, so it doesn't need to be part of that composited layer at all.
 */
private fun DrawScope.drawMusicNote(center: Offset, baseRadius: Float) {
    val s = 2f * baseRadius * NOTE_SIZE_FRACTION
    val headRadius = s * 0.16f
    val headCenter = Offset(center.x - s * 0.14f, center.y + s * 0.22f)
    val stemX = headCenter.x + headRadius * 0.92f
    val stemTopY = center.y - s * 0.32f

    drawCircle(color = NOTE_COLOR, radius = headRadius, center = headCenter)
    drawLine(
        color = NOTE_COLOR,
        start = Offset(stemX, headCenter.y),
        end = Offset(stemX, stemTopY),
        strokeWidth = headRadius * 0.55f,
        cap = StrokeCap.Round,
    )

    val flag = Path().apply {
        moveTo(stemX, stemTopY)
        cubicTo(
            stemX + s * 0.30f, stemTopY + s * 0.02f,
            stemX + s * 0.26f, stemTopY + s * 0.20f,
            stemX + s * 0.05f, stemTopY + s * 0.30f,
        )
        cubicTo(
            stemX + s * 0.15f, stemTopY + s * 0.17f,
            stemX + s * 0.14f, stemTopY + s * 0.07f,
            stemX, stemTopY + s * 0.05f,
        )
        close()
    }
    drawPath(flag, color = NOTE_COLOR)
}
