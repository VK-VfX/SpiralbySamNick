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

private const val SAMPLE_COUNT = 140

/** Front-layer scroll speed, as a fraction of the canvas's shorter dimension per second --
 * converted through delta time like every rate in this app. */
private const val FRONT_SCROLL_SPEED_FRACTION = 0.13f

/** Back layer scrolls slower than the front -- the classic parallax cue for "further away" -- and,
 * since both layers sample the same [SAMPLE_COUNT] points across the same screen width, a slower
 * scroll means each of the back layer's samples spans a longer stretch of real time, so its
 * silhouette naturally reads as calmer and slower-changing than the front's, with no separate
 * smoothing tuned for it. */
private const val BACK_SCROLL_SPEED_FRACTION = 0.05f

private const val FRONT_MAX_HEIGHT_FRACTION = 0.46f
private const val BACK_MAX_HEIGHT_FRACTION = 0.58f
private const val BACK_ALPHA = 80

private const val HEIGHT_SENSITIVITY_GAMMA = 0.8f
private const val LEVEL_ATTACK_TAU_SECONDS = 0.06f
private const val LEVEL_DECAY_TAU_SECONDS = 0.3f

private const val OUTLINE_WIDTH_FRACTION = 0.008f
private const val GLOW_RADIUS_FRACTION = 0.02f
private const val GLOW_ALPHA = 140

/**
 * A scrolling skyline -- landscape/atmospheric rather than the bars or rings everywhere else in
 * the app. Two independent scrolling silhouettes, both filled from the bottom of the screen up to
 * a height curve built from [SpectrumEngine]'s bands averaged into a single overall-level scalar
 * each frame (the same technique as Waveform Ribbon's history, just rendered as a filled ground
 * shape instead of a floating ribbon): a dimmer, taller, slower-scrolling back layer for parallax
 * depth, and a brighter, glowing front layer with a crisp lit outline on top of it.
 *
 * Both layers read the *same* smoothed level value, so there's no separate audio processing to
 * keep in sync -- what makes the back layer read as "distant" is purely that it scrolls slower
 * ([BACK_SCROLL_SPEED_FRACTION] vs [FRONT_SCROLL_SPEED_FRACTION]) and renders at low alpha
 * ([BACK_ALPHA]), both real, simple differences rather than a second data pipeline. Each layer
 * keeps its own history ring buffer and scroll accumulator (real accumulated pixels, converted
 * through delta time, not a frame count -- the same pattern as every other scrolling or rotating
 * element in this app), so the two silhouettes drift at genuinely different, delta-time-correct
 * rates regardless of the display's refresh rate.
 *
 * Color is a vertical gradient from near-black at the ground up to [VisualizerTheme.ACCENT] at
 * full height, reusing the app's own (user-customizable) accent color rather than an unrelated
 * palette, so Frequency Terrain automatically follows whatever accent color is set in Appearance
 * settings the same way nearly every other mode already does. Glow is the same
 * draw-solid-then-blur-once technique used everywhere else, applied only to the front layer's
 * outline.
 */
@Composable
fun FrequencyTerrainScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val frontHistory = remember { FloatArray(SAMPLE_COUNT) }
    val backHistory = remember { FloatArray(SAMPLE_COUNT) }
    val frontHeadIndex = remember { intArrayOf(0) }
    val backHeadIndex = remember { intArrayOf(0) }
    val frontScrollAccumulator = remember { floatArrayOf(0f) }
    val backScrollAccumulator = remember { floatArrayOf(0f) }
    val levelSmoothedHolder = remember { floatArrayOf(0f) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val terrainHolder = remember { arrayOfNulls<Bitmap>(1) }
    val outlineHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var terrain = terrainHolder[0]
        if (terrain == null || terrain.width != widthPx || terrain.height != heightPx) {
            terrain = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            terrainHolder[0] = terrain
        }
        var outline = outlineHolder[0]
        if (outline == null || outline.width != widthPx || outline.height != heightPx) {
            outline = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            outlineHolder[0] = outline
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        var meanBand = 0f
        for (v in engine.bands) meanBand += v
        meanBand = (meanBand / engine.bands.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val target = (meanBand.pow(HEIGHT_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
        val levelTau = if (target > levelSmoothedHolder[0]) LEVEL_ATTACK_TAU_SECONDS else LEVEL_DECAY_TAU_SECONDS
        val levelAlpha = 1f - exp(-dt / levelTau)
        levelSmoothedHolder[0] += (target - levelSmoothedHolder[0]) * levelAlpha

        val minDim = size.minDimension
        val samplePitch = size.width / (SAMPLE_COUNT - 1)

        frontScrollAccumulator[0] += minDim * FRONT_SCROLL_SPEED_FRACTION * dt
        while (frontScrollAccumulator[0] >= samplePitch) {
            frontHeadIndex[0] = (frontHeadIndex[0] + 1) % SAMPLE_COUNT
            frontHistory[frontHeadIndex[0]] = levelSmoothedHolder[0]
            frontScrollAccumulator[0] -= samplePitch
        }
        backScrollAccumulator[0] += minDim * BACK_SCROLL_SPEED_FRACTION * dt
        while (backScrollAccumulator[0] >= samplePitch) {
            backHeadIndex[0] = (backHeadIndex[0] + 1) % SAMPLE_COUNT
            backHistory[backHeadIndex[0]] = levelSmoothedHolder[0]
            backScrollAccumulator[0] -= samplePitch
        }

        val baselineY = size.height
        val heightScale = settings.height / BarSpectrumSettings.HEIGHT_MAX
        val accentArgb = VisualizerTheme.ACCENT.toArgb()
        val groundArgb = Color(red = VisualizerTheme.ACCENT.red * 0.08f, green = VisualizerTheme.ACCENT.green * 0.08f, blue = VisualizerTheme.ACCENT.blue * 0.08f, alpha = 1f).toArgb()
        val verticalGradient = LinearGradient(
            0f, baselineY, 0f, baselineY - minDim * FRONT_MAX_HEIGHT_FRACTION,
            intArrayOf(groundArgb, accentArgb),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )

        val canvasWidth = size.width

        fun buildSkylinePath(history: FloatArray, headIndex: Int, scrollAccumulator: Float, maxHeightFraction: Float): AndroidPath {
            val path = AndroidPath()
            val maxHeight = minDim * maxHeightFraction * heightScale
            path.moveTo(-samplePitch, baselineY)
            for (i in 0 until SAMPLE_COUNT) {
                val sampleIndex = (headIndex + 1 + i) % SAMPLE_COUNT
                val x = i * samplePitch - scrollAccumulator
                val y = baselineY - history[sampleIndex] * maxHeight
                path.lineTo(x, y)
            }
            path.lineTo(canvasWidth + samplePitch, baselineY)
            path.close()
            return path
        }

        val terrainCanvas = AndroidCanvas(terrain)
        terrainCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val backPaint = AndroidPaint().apply {
            isAntiAlias = true
            shader = verticalGradient
            alpha = BACK_ALPHA
        }
        terrainCanvas.drawPath(
            buildSkylinePath(backHistory, backHeadIndex[0], backScrollAccumulator[0], BACK_MAX_HEIGHT_FRACTION),
            backPaint,
        )

        val frontFillPaint = AndroidPaint().apply {
            isAntiAlias = true
            shader = verticalGradient
        }
        val frontPath = buildSkylinePath(frontHistory, frontHeadIndex[0], frontScrollAccumulator[0], FRONT_MAX_HEIGHT_FRACTION)
        terrainCanvas.drawPath(frontPath, frontFillPaint)

        val outlineCanvas = AndroidCanvas(outline)
        outlineCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val outlinePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = minDim * OUTLINE_WIDTH_FRACTION * settings.strokeWeight
            color = accentArgb
        }
        outlineCanvas.drawPath(frontPath, outlinePaint)

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(outline, 0f, 0f, glowPaint)

        drawRect(color = Color.Black)
        drawImage(glow.asImageBitmap())
        drawImage(terrain.asImageBitmap())
        drawImage(outline.asImageBitmap())
    }
}
