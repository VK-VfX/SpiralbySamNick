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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Angular resolution -- how many points make up the ring. Needs to comfortably resolve
 * [WAVE_LOBES] individual ripples around the full circumference (roughly `POINT_COUNT /
 * WAVE_LOBES` points per lobe) as well as [SpectrumEngine]'s own band count (wrapped with
 * interpolation, same idea as Neon Cyan Pulse's denser bar row), so the bezier-smoothed outline
 * reads as many distinct small waves rather than a faceted polygon or one blurred-out lobe. */
private const val POINT_COUNT = 96

/** The ring's rest radius as a fraction of the canvas's shorter dimension -- the curve oscillates
 * around this, it isn't a separate fixed shape of its own. */
private const val BASE_RADIUS_FRACTION = 0.30f

/** How far a wave crest can push outward (or a trough pull inward) at full reactive level, before
 * [BarSpectrumSettings.height]. */
private const val MAX_PUSH_FRACTION = 0.34f

/** Exponent applied to each point's amplitude before it drives its wave contribution: <1 lifts
 * quiet content so the ring visibly breathes even at moderate volume, matching the other
 * bar-spectrum modes. */
private const val AMPLITUDE_SENSITIVITY_GAMMA = 0.85f

/** Fast push-out, expressed as a time-based rate (a decay/rise "tau", not a flat per-frame
 * multiplier) so it converts correctly via delta time regardless of the display's refresh rate --
 * see MainActivity's refresh-rate handling and every engine's step(dt, ...) for the same pattern. */
private const val ATTACK_TAU_SECONDS = 0.05f

/** Slower settle back to rest once a transient passes -- roughly 8x [ATTACK_TAU_SECONDS]. */
private const val DECAY_TAU_SECONDS = 0.4f

private const val STROKE_WIDTH_FRACTION = 0.014f

/** One full turn every 60 seconds -- slow enough to read as ambient motion, not a spin. Like every
 * other rate in this mode, expressed per-second and applied via delta time, so it turns at the
 * same real-world speed regardless of the display's refresh rate. Applied to both the wave pattern
 * and the idle ripple's phase (see [WAVE_LOBES]) so the whole shape spins as one coherent unit. */
private const val ROTATION_DEGREES_PER_SECOND = 6f

/** How many ripple cycles wrap the full circumference -- the "gear/scalloped flower" tooth count.
 * Deliberately a fixed geometric parameter, not derived from which specific bands happen to be
 * loud: real spectra concentrate most of their energy in just a few bands at any instant, and
 * mapping band index straight to angle let one or two loud bands dominate the whole outline as one
 * or two big lobes. A fixed multi-lobe carrier, amplitude-modulated by the ring's own average
 * level each frame, guarantees many small waves are visible everywhere around the ring regardless
 * of the moment's spectral shape. */
private const val WAVE_LOBES = 18

/** Idle ripple depth, as a fraction of [MAX_PUSH_FRACTION], present even in near-silence -- the
 * curve never flattens to a perfectly bare circle. */
private const val IDLE_RIPPLE_BASE_FRACTION = 0.05f

/** Additional idle-ripple depth that scales with overall level (0 at silence, this much added at
 * full level) -- this is what makes the waves visibly deepen as the music gets louder. */
private const val IDLE_RIPPLE_REACTIVE_FRACTION = 0.4f

/** Gain on each point's level relative to the ring's own average level that frame. Demeaning
 * (rather than mapping raw level straight to outward push) is what turns "one loud band" into a
 * genuine peak-and-trough pattern -- above-average points push out, below-average points pull in
 * -- instead of every point only ever bulging outward from zero. */
private const val REACTIVE_GAIN = 1.5f

/** Light circular blur across neighboring points' levels before they become wave offsets --
 * enough to keep transitions smooth and organic, deliberately not wide enough to merge separate
 * lobes back into one blurred hill (that would undo [WAVE_LOBES]'s whole purpose). */
private const val SPATIAL_BLUR_RADIUS = 1

private const val WAVE_OFFSET_MIN = -0.65f
private const val WAVE_OFFSET_MAX = 1.1f

/** Soft, wide falloff behind the crisp line -- the "softer outer glow falloff" half of the bloom. */
private const val GLOW_OUTER_RADIUS_FRACTION = 0.05f
private const val GLOW_OUTER_ALPHA = 110

/** Tight, bright halo hugging the line -- the "brighter hot edge" half of the bloom. */
private const val GLOW_INNER_RADIUS_FRACTION = 0.016f
private const val GLOW_INNER_ALPHA = 200

/** Hue stops for the ring's circumference gradient -- see [fullHueSweepColors] for why this needs
 * more than a handful of stops to read as a clean rainbow rather than muddy blended off-hues. */
private const val HUE_STEPS = 12

/** The static center note glyph's own bounding box, as a fraction of the ring's rest diameter --
 * comfortably inside it, clear of the waves even at full amplitude. */
private const val NOTE_SIZE_FRACTION = 0.45f
private val NOTE_COLOR = Color(0xFFF2F2F2)

/**
 * A single, continuously undulating closed ring -- Specterr-style -- not discrete spikes off a
 * separate fixed circle. Each of [POINT_COUNT] angular positions gets its own target level (bands
 * interpolated with wraparound, since the ring has no start/end seam), independently smoothed with
 * a fast attack / slower decay so bass hits push their section of the ring out quickly and it eases
 * back to rest rather than snapping -- reusing exactly [SpectrumEngine]'s bands, just with a
 * second, ring-specific smoothing pass on top, same as every other bar-spectrum mode's own
 * rendering-level tuning.
 *
 * Turning that per-point level into a *wave offset* (rather than a straight outward push) is what
 * keeps the ring from being dominated by whichever one or two bands happen to be loudest at a given
 * instant, which is what a straight level-to-radius mapping produces (real spectra concentrate
 * energy in a few bands, not evenly across all of them): each point's level is compared against the
 * ring's own average level that frame ([REACTIVE_GAIN]), so above-average points bulge out and
 * below-average points pull in -- genuine peaks *and* troughs -- and a fixed-frequency idle ripple
 * ([WAVE_LOBES]) is layered underneath, amplitude-modulated by that same average level, so many
 * small waves are visible everywhere around the ring even when the music's energy happens to sit in
 * only one or two bands, and the ring still has a subtle ripple rather than going perfectly flat at
 * rest. The outline itself is a closed quadratic-bezier-through-midpoints path (moveTo the midpoint
 * before point 0, then quadTo each point with the following midpoint as the endpoint) so it reads
 * as one fluid curve, not a jagged polygon.
 *
 * The ring shares one `SweepGradient` shader across its whole length, so color always matches
 * canvas-space angle -- any point picks up whatever hue sits at its current position, which is what
 * makes the slow constant rotation (applied as a degrees-per-second offset added to every point's
 * angle -- and to the idle ripple's phase, so the two stay in lockstep -- converted via delta time
 * like everything else) actually visible without needing to touch the color logic at all.
 *
 * Glow is two blurred copies of the same solid ring layer composited underneath the crisp one -- a
 * wide soft outer pass and a tight bright inner pass -- for a bright hot edge with a softer outer
 * falloff. A small static music-note glyph sits in the open center as the fixed visual anchor,
 * drawn last (after the glow and crisp layers) with Compose's own draw calls since it needs no blur
 * and never moves -- unlike the ring, it doesn't react to level or rotation.
 */
@Composable
fun RadialPulseRingScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val ringHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowOuterHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowInnerHolder = remember { arrayOfNulls<Bitmap>(1) }
    val pushLevels = remember { FloatArray(POINT_COUNT) }
    val blurredLevels = remember { FloatArray(POINT_COUNT) }
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

        // Pass 1: each point's own level, temporally smoothed with a fast attack / slower decay --
        // the same per-point ballistics as before, just no longer drawn as an independent spike.
        for (i in 0 until POINT_COUNT) {
            val target = (circularInterpolatedBand(engine.bands, i, POINT_COUNT)
                .pow(AMPLITUDE_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val tau = if (target > pushLevels[i]) ATTACK_TAU_SECONDS else DECAY_TAU_SECONDS
            val smoothingAlpha = 1f - exp(-dt / tau)
            pushLevels[i] += (target - pushLevels[i]) * smoothingAlpha
        }

        // Pass 2: a light circular blur across neighbors so the curve's transitions are smooth
        // rather than jagged, without smearing separate lobes into one another.
        for (i in 0 until POINT_COUNT) {
            var sum = 0f
            var weight = 0f
            for (k in -SPATIAL_BLUR_RADIUS..SPATIAL_BLUR_RADIUS) {
                val tap = (i + k + POINT_COUNT) % POINT_COUNT
                val tapWeight = if (k == 0) 2f else 1f
                sum += pushLevels[tap] * tapWeight
                weight += tapWeight
            }
            blurredLevels[i] = sum / weight
        }

        // The ring's own average level this frame stands in for "how loud is it right now" --
        // used both to demean each point (turning raw level into peaks *and* troughs) and to scale
        // the idle ripple, without needing a separate overall-loudness input.
        var meanLevel = 0f
        for (i in 0 until POINT_COUNT) meanLevel += blurredLevels[i]
        meanLevel /= POINT_COUNT
        val idleRippleDepth = IDLE_RIPPLE_BASE_FRACTION + IDLE_RIPPLE_REACTIVE_FRACTION * meanLevel

        val points = Array(POINT_COUNT) { i ->
            val angle = Math.toRadians((i.toFloat() / POINT_COUNT) * 360.0 - 90.0 + rotationHolder[0])
            val reactive = (blurredLevels[i] - meanLevel) * REACTIVE_GAIN
            val idleRipple = sin(WAVE_LOBES * angle).toFloat() * idleRippleDepth
            val offset = (reactive + idleRipple).coerceIn(WAVE_OFFSET_MIN, WAVE_OFFSET_MAX)
            val radius = baseRadius + offset * maxPush
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

        val ringCanvas = AndroidCanvas(ring)
        ringCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val ringPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            strokeWidth = ringStrokeWidth
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
        drawMusicNote(center, baseRadius)
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

/**
 * A simple static eighth-note glyph (filled head, stem, curved flag) centered on [center] and
 * sized off [baseRadius] -- the fixed anchor the ring surrounds. Deliberately drawn with Compose's
 * own draw calls rather than through the bitmap/blur pipeline above: it never moves and never
 * blurs, so it doesn't need to be part of that composited layer at all.
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
