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

/** How many times the base petal repeats around the full circle -- the mandala's fold count. */
private const val SYMMETRY_COUNT = 8

/** Points computed per half-petal before mirroring -- the mirrored full petal has twice this many. */
private const val HALF_POINT_COUNT = 20

private const val BASE_RADIUS_FRACTION = 0.06f
private const val MAX_PUSH_FRACTION = 0.42f
private const val AMPLITUDE_SENSITIVITY_GAMMA = 0.8f
private const val ATTACK_TAU_SECONDS = 0.05f
private const val DECAY_TAU_SECONDS = 0.3f

/** Slow constant spin, converted through delta time like every rate in this app. */
private const val ROTATION_DEGREES_PER_SECOND = 4f

private const val STROKE_WIDTH_FRACTION = 0.010f

/** The filled petal body is a faint wash under the crisp stroked outline, not a fully opaque
 * shape -- keeps the layered wedges from turning into a single solid disc as they overlap. */
private const val PETAL_FILL_ALPHA = 90

private const val GLOW_RADIUS_FRACTION = 0.022f
private const val GLOW_ALPHA = 165
private const val HUE_STEPS = 12

/**
 * A rotationally-symmetric mandala -- ornamental rather than a meter, a scatter, or a scrolling
 * trend, the three shapes every other mode in the app already covers. One petal shape is built
 * once per frame from [SpectrumEngine]'s bands (a straight sweep across the spectrum condensed
 * into [HALF_POINT_COUNT] points spanning half the petal's angular width, each independently
 * smoothed with the usual fast-attack/slower-decay pattern), then mirrored across its own center
 * line for bilateral symmetry -- an authentic kaleidoscope's "one wedge, reflected" look rather
 * than [SYMMETRY_COUNT] wedges each showing different content. That single symmetric petal is
 * then drawn [SYMMETRY_COUNT] times, each copy just rotated by an additional `360 /
 * [SYMMETRY_COUNT]` degrees -- the underlying radius profile is computed once per frame and reused
 * for every copy, not recomputed per copy.
 *
 * A slow constant rotation ([ROTATION_DEGREES_PER_SECOND], delta-time based) is added to every
 * copy's placement angle each frame, and color comes from one `SweepGradient` (the same true
 * closed 360-degree hue wheel Radial Pulse Ring used, [fullHueSweepColors]) centered on the bloom
 * -- since the shader is keyed to canvas-space angle rather than petal-local position, the whole
 * rainbow visibly rotates along with the pattern for free, with no color logic of its own to keep
 * in sync. Glow is the same draw-solid-then-blur-once technique used everywhere else: all
 * [SYMMETRY_COUNT] petal copies drawn solid into one bitmap, blurred a single time.
 */
@Composable
fun KaleidoscopeBloomScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val pushLevels = remember { FloatArray(HALF_POINT_COUNT) }
    val bloomHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val rotationHolder = remember { floatArrayOf(0f) }
    val hueColors = remember { fullHueSweepColors(HUE_STEPS) }
    val huePositions = remember { fullHueSweepPositions(HUE_STEPS) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed
        rotationHolder[0] = (rotationHolder[0] + ROTATION_DEGREES_PER_SECOND * dt) % 360f

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var bloom = bloomHolder[0]
        if (bloom == null || bloom.width != widthPx || bloom.height != heightPx) {
            bloom = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bloomHolder[0] = bloom
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        val center = Offset(size.width / 2f, size.height / 2f)
        val minDim = size.minDimension
        val baseRadius = minDim * BASE_RADIUS_FRACTION
        val maxPush = minDim * MAX_PUSH_FRACTION * (settings.height / BarSpectrumSettings.HEIGHT_MAX)
        val wedgeAngleDeg = 360f / SYMMETRY_COUNT
        val halfWedgeAngleDeg = wedgeAngleDeg / 2f
        val bandCount = engine.bands.size

        // Pass 1: one half-petal's worth of radii, smoothed frame to frame.
        for (i in 0 until HALF_POINT_COUNT) {
            val t = i.toFloat() / (HALF_POINT_COUNT - 1)
            val bandPos = t * (bandCount - 1)
            val lowIndex = bandPos.toInt().coerceIn(0, bandCount - 1)
            val highIndex = (lowIndex + 1).coerceAtMost(bandCount - 1)
            val frac = bandPos - lowIndex
            val rawLevel = engine.bands[lowIndex] + (engine.bands[highIndex] - engine.bands[lowIndex]) * frac
            val target = (rawLevel.coerceIn(0f, 1f).pow(AMPLITUDE_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val tau = if (target > pushLevels[i]) ATTACK_TAU_SECONDS else DECAY_TAU_SECONDS
            val alpha = 1f - exp(-dt / tau)
            pushLevels[i] += (target - pushLevels[i]) * alpha
        }

        // Mirror the half-petal into a full, bilaterally-symmetric petal spanning -halfWedge to
        // +halfWedge, local to the wedge's own center line.
        val petalLocalAngles = FloatArray(HALF_POINT_COUNT * 2)
        val petalRadii = FloatArray(HALF_POINT_COUNT * 2)
        for (i in 0 until HALF_POINT_COUNT) {
            val t = i.toFloat() / (HALF_POINT_COUNT - 1)
            val localAngle = t * halfWedgeAngleDeg
            val radius = baseRadius + pushLevels[i] * maxPush
            // Left half: mirrored angle, filled back-to-front so the whole sequence sweeps
            // continuously from -halfWedge to +halfWedge.
            petalLocalAngles[HALF_POINT_COUNT - 1 - i] = -localAngle
            petalRadii[HALF_POINT_COUNT - 1 - i] = radius
            petalLocalAngles[HALF_POINT_COUNT + i] = localAngle
            petalRadii[HALF_POINT_COUNT + i] = radius
        }

        val bloomCanvas = AndroidCanvas(bloom)
        bloomCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val sweepShader = SweepGradient(center.x, center.y, hueColors, huePositions)
        val petalStrokeWidth = minDim * STROKE_WIDTH_FRACTION * settings.strokeWeight
        val petalPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            strokeWidth = petalStrokeWidth
            shader = sweepShader
        }
        val petalFillPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            alpha = PETAL_FILL_ALPHA
            shader = sweepShader
        }

        for (copy in 0 until SYMMETRY_COUNT) {
            val copyOffsetDeg = copy * wedgeAngleDeg + rotationHolder[0] - 90f
            val path = AndroidPath()
            path.moveTo(center.x, center.y)
            for (i in petalLocalAngles.indices) {
                val angleRad = Math.toRadians((petalLocalAngles[i] + copyOffsetDeg).toDouble())
                val x = center.x + petalRadii[i] * cos(angleRad).toFloat()
                val y = center.y + petalRadii[i] * sin(angleRad).toFloat()
                path.lineTo(x, y)
            }
            path.close()
            bloomCanvas.drawPath(path, petalFillPaint)
            bloomCanvas.drawPath(path, petalPaint)
        }

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(bloom, 0f, 0f, glowPaint)

        drawRect(color = Color.Black)
        drawImage(glow.asImageBitmap())
        drawImage(bloom.asImageBitmap())
    }
}
