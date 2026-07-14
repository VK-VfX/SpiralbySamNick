package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/** Denser than Rainbow Spectrum's one-bar-per-band: linearly interpolates between [SpectrumEngine]'s bands. */
private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT * 2

/** Thinner and tighter than Rainbow Spectrum's 0.62 -- a dense LED-wall look. */
private const val BAR_WIDTH_FRACTION = 0.42f

/** Exponent applied to each band's level before it becomes height: <1 lifts quiet bands so the row reads livelier. */
private const val SENSITIVITY_GAMMA = 0.85f

/** Larger radius and alpha than Rainbow Spectrum's glow -- the "stronger glow" nightclub LED-wall feel. */
private const val GLOW_RADIUS_FRACTION = 0.028f
private const val GLOW_ALPHA = 190

/** How much a bar's color leans into its frequency-zone color at full level, vs. staying resting
 * cyan -- <1 so even a maxed-out bar keeps a little of the mode's namesake cyan in the blend. */
private const val ZONE_COLOR_MAX_MIX = 0.92f

/** Exponent applied to level before it drives the color blend: <1 so bars visibly tint well before
 * they're maxed out, matching "the bar flashes color when that frequency hits" rather than only
 * at the very loudest instant. */
private const val COLOR_REACTIVITY_GAMMA = 0.55f

/**
 * A mirrored FFT bar spectrum, denser and thinner than [RainbowSpectrumScreen]: each bar is its
 * own gradient running from the center axis out to its tip on both halves, with a stronger glow,
 * on a pure black background. Bar count is doubled past [SpectrumEngine]'s own band count by
 * linearly interpolating between adjacent bands -- a rendering-only choice, not new DSP.
 *
 * Color is frequency-reactive: each bar rests at the mode's namesake cyan when quiet, then blends
 * toward a color drawn from [frequencyZoneColor] -- keyed to that bar's position in the row, which
 * stands in for its frequency band -- as its own level rises. A bass hit flashes its bars red, a
 * treble hit flashes its bars violet, and so on, rather than the whole row sharing one color.
 *
 * Bars are drawn once, solid (each with its own gradient), into their own bitmap; the glow is a
 * *single* blurred copy of that whole composited layer, not a per-bar blur -- the same technique
 * [RainbowSpectrumScreen] uses, since `BlurMaskFilter`'s cost is dominated by per-call overhead and
 * this mode already draws twice as many bars.
 */
@Composable
fun NeonCyanPulseScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val barsHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual band data lives in a plain array that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var bars = barsHolder[0]
        if (bars == null || bars.width != widthPx || bars.height != heightPx) {
            bars = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            barsHolder[0] = bars
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }
        val barsCanvas = AndroidCanvas(bars)
        barsCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val centerY = size.height / 2f
        val maxHalf = size.height * settings.height
        val pitch = size.width / BAR_COUNT
        val barWidth = pitch * BAR_WIDTH_FRACTION * settings.strokeWeight

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
        }

        for (i in 0 until BAR_COUNT) {
            val rawLevel = interpolatedBand(engine.bands, i, BAR_COUNT).coerceIn(0f, 1f)
            val level = (rawLevel.pow(SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val half = (level * maxHalf).coerceAtLeast(barWidth * 0.3f)
            val cx = (i + 0.5f) * pitch
            val topY = centerY - half
            val bottomY = centerY + half

            val zoneColor = frequencyZoneColor(i.toFloat() / (BAR_COUNT - 1).coerceAtLeast(1))
            val colorMix = rawLevel.pow(COLOR_REACTIVITY_GAMMA) * ZONE_COLOR_MAX_MIX
            val barColor = lerpGradientColor(NEON_CYAN, zoneColor, colorMix)

            barPaint.shader = LinearGradient(
                cx, topY, cx, bottomY,
                intArrayOf(NEON_WHITE_HOT.toArgb(), barColor.toArgb(), NEON_WHITE_HOT.toArgb()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            barsCanvas.drawLine(cx, topY, cx, bottomY, barPaint)
        }

        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(size.minDimension * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawBitmap(bars, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(bars.asImageBitmap())
    }
}

/** Linearly interpolates [bands] up to [totalBars] positions, denser than the source band count. */
private fun interpolatedBand(bands: FloatArray, i: Int, totalBars: Int): Float {
    if (bands.isEmpty()) return 0f
    val sourcePos = i.toFloat() * (bands.size - 1) / (totalBars - 1).coerceAtLeast(1)
    val lowIndex = sourcePos.toInt().coerceIn(0, bands.size - 1)
    val highIndex = (lowIndex + 1).coerceAtMost(bands.size - 1)
    val frac = sourcePos - lowIndex
    return bands[lowIndex] + (bands[highIndex] - bands[lowIndex]) * frac
}
