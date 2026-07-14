package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.exp
import kotlin.math.pow

/** How many samples make up the visible history -- together with [SCROLL_SPEED_FRACTION] this
 * sets how many seconds of music the ribbon holds on screen at once (roughly canvas width divided
 * by scroll speed; sample count itself only affects resolution, not duration). */
private const val SAMPLE_COUNT = 160

/** Scroll speed as a fraction of the canvas's shorter dimension per second -- converted through
 * delta time like every rate in this app, so the ribbon flows at the same real-world speed
 * regardless of the display's refresh rate. */
private const val SCROLL_SPEED_FRACTION = 0.16f

private const val MAX_THICKNESS_FRACTION = 0.34f
private const val MIN_THICKNESS_FRACTION = 0.02f
private const val AMPLITUDE_SENSITIVITY_GAMMA = 0.8f

private const val LEVEL_ATTACK_TAU_SECONDS = 0.05f
private const val LEVEL_DECAY_TAU_SECONDS = 0.25f

private const val GLOW_RADIUS_FRACTION = 0.02f
private const val GLOW_ALPHA = 150

/**
 * A scrolling ribbon -- deliberately about *motion through time* rather than an instantaneous
 * snapshot like every bar/ring mode in the app: instead of redrawing a fixed set of positions each
 * frame from the current instant's data, a fixed-size ring buffer of [SAMPLE_COUNT] past samples
 * scrolls continuously across the screen, so what's on screen at any moment is the last several
 * seconds of the music's overall level, not just "right now."
 *
 * [SpectrumEngine]'s bands are averaged into a single overall-level scalar each frame, itself
 * smoothed with a fast attack / slower decay (same dt-based exponential pattern as every engine's
 * own `step(dt, ...)`) before being sampled into the history buffer -- a real accumulated distance
 * in pixels (`scrollAccumulatorPx += scrollSpeed * dt`) is what decides when a new sample is
 * pushed, not a frame count, so both the scroll motion itself and how often new samples are
 * captured stay tied to real elapsed time rather than the display's refresh rate. Between new
 * samples, every point on the ribbon is rendered shifted left by the accumulator's current
 * fractional remainder, which is what keeps the scroll looking continuous frame to frame instead
 * of only moving in visible jumps whenever a new sample lands.
 *
 * The ribbon itself is a filled shape between a top and bottom envelope (thickness at each sample
 * driven by that sample's level, `coerceAtLeast`-floored so it never fully vanishes to a bare
 * line), filled with a rainbow gradient that's fixed in screen space -- the same [RAINBOW_STOPS]
 * every other rainbow-colored mode uses, just oriented horizontally, so the ribbon's shape flows
 * through a static rainbow backdrop rather than the color scrolling along with it. Glow is the
 * same draw-solid-then-blur-once technique used everywhere else.
 */
@Composable
fun WaveformRibbonScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val history = remember { FloatArray(SAMPLE_COUNT) }
    val ribbonHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val headIndexHolder = remember { intArrayOf(0) }
    val scrollAccumulatorHolder = remember { floatArrayOf(0f) }
    val levelSmoothedHolder = remember { floatArrayOf(0f) }
    val gradientColors = remember { RAINBOW_STOPS.map { it.second.toArgb() }.toIntArray() }
    val gradientPositions = remember { RAINBOW_STOPS.map { it.first }.toFloatArray() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var ribbon = ribbonHolder[0]
        if (ribbon == null || ribbon.width != widthPx || ribbon.height != heightPx) {
            ribbon = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            ribbonHolder[0] = ribbon
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        var meanBand = 0f
        for (v in engine.bands) meanBand += v
        meanBand = (meanBand / engine.bands.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val target = (meanBand.pow(AMPLITUDE_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
        val levelTau = if (target > levelSmoothedHolder[0]) LEVEL_ATTACK_TAU_SECONDS else LEVEL_DECAY_TAU_SECONDS
        val levelAlpha = 1f - exp(-dt / levelTau)
        levelSmoothedHolder[0] += (target - levelSmoothedHolder[0]) * levelAlpha

        val minDim = size.minDimension
        val samplePitch = size.width / (SAMPLE_COUNT - 1)
        val scrollSpeedPx = minDim * SCROLL_SPEED_FRACTION
        scrollAccumulatorHolder[0] += scrollSpeedPx * dt
        while (scrollAccumulatorHolder[0] >= samplePitch) {
            headIndexHolder[0] = (headIndexHolder[0] + 1) % SAMPLE_COUNT
            history[headIndexHolder[0]] = levelSmoothedHolder[0]
            scrollAccumulatorHolder[0] -= samplePitch
        }

        val centerY = size.height / 2f
        val maxHalfThickness = minDim * MAX_THICKNESS_FRACTION * (settings.height / BarSpectrumSettings.HEIGHT_MAX) / 2f
        val minHalfThickness = minDim * MIN_THICKNESS_FRACTION * settings.strokeWeight / 2f

        val topPath = AndroidPath()
        val bottomXY = FloatArray(SAMPLE_COUNT * 2)
        for (i in 0 until SAMPLE_COUNT) {
            val sampleIndex = (headIndexHolder[0] + 1 + i) % SAMPLE_COUNT
            val x = i * samplePitch - scrollAccumulatorHolder[0]
            val halfThickness = (history[sampleIndex] * maxHalfThickness).coerceAtLeast(minHalfThickness)
            val topY = centerY - halfThickness
            if (i == 0) topPath.moveTo(x, topY) else topPath.lineTo(x, topY)
            bottomXY[i * 2] = x
            bottomXY[i * 2 + 1] = centerY + halfThickness
        }
        for (i in SAMPLE_COUNT - 1 downTo 0) {
            topPath.lineTo(bottomXY[i * 2], bottomXY[i * 2 + 1])
        }
        topPath.close()

        val ribbonCanvas = AndroidCanvas(ribbon)
        ribbonCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val ribbonPaint = AndroidPaint().apply {
            isAntiAlias = true
            shader = LinearGradient(0f, 0f, size.width, 0f, gradientColors, gradientPositions, Shader.TileMode.CLAMP)
        }
        ribbonCanvas.drawPath(topPath, ribbonPaint)

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(ribbon, 0f, 0f, glowPaint)

        drawRect(color = Color.Black)
        drawImage(glow.asImageBitmap())
        drawImage(ribbon.asImageBitmap())
    }
}
