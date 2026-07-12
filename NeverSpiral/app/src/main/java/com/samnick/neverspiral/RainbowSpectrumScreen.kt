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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/** How many of [SpectrumEngine]'s FFT bands to draw -- one bar per band. */
private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT

/** Fraction of each bar's column pitch actually drawn -- the rest is the visible gap between bars. */
private const val BAR_WIDTH_FRACTION = 0.62f

/** Exponent applied to each band's level before it becomes height: <1 lifts quiet bands so the row reads livelier. */
private const val SENSITIVITY_GAMMA = 0.85f

private const val GLOW_RADIUS_FRACTION = 0.018f
private const val GLOW_ALPHA = 120

/** Left-to-right color stops: blue/purple through magenta/pink and orange to yellow. */
private val RAINBOW_STOPS = listOf(
    0.00f to Color(0xFF3A3BE0),
    0.28f to Color(0xFF9330D9),
    0.55f to Color(0xFFE43A9E),
    0.78f to Color(0xFFFF7A2E),
    1.00f to Color(0xFFFFD400),
)

/**
 * A mirrored FFT bar spectrum: each of [SpectrumEngine]'s bands is a bar reflecting top and bottom
 * off a horizontal center axis, rather than growing from the bottom only, colored by a fixed
 * horizontal rainbow gradient across the row, with a soft glow behind each bar on a pure black
 * background. Reuses [SpectrumEngine] directly -- the same fast-rise/slower-fall smoothed bands
 * [SpectrumScreen] and [GraphicEqScreen] draw -- so this is a different rendering treatment of
 * already-proven data, not new DSP. Bars are rendered into a bitmap cleared (not faded) every
 * frame, purely so [BlurMaskFilter] has a software canvas to blur against -- Android silently
 * ignores mask filters on the hardware-accelerated Compose canvas.
 */
@Composable
fun RainbowSpectrumScreen(engine: SpectrumEngine) {
    val trailHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual band data lives in a plain array that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var trail = trailHolder[0]
        if (trail == null || trail.width != widthPx || trail.height != heightPx) {
            trail = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            trailHolder[0] = trail
        }
        val trailCanvas = AndroidCanvas(trail)
        trailCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val centerY = size.height / 2f
        val maxHalf = size.height * 0.46f
        val n = engine.bands.size.coerceAtMost(BAR_COUNT)
        val pitch = size.width / n
        val barWidth = pitch * BAR_WIDTH_FRACTION
        val glowRadius = size.minDimension * GLOW_RADIUS_FRACTION

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
        }

        for (i in 0 until n) {
            val level = engine.bands[i].coerceIn(0f, 1f).pow(SENSITIVITY_GAMMA)
            val half = (level * maxHalf).coerceAtLeast(barWidth * 0.3f)
            val cx = (i + 0.5f) * pitch
            val color = rainbowColor(i.toFloat() / (n - 1).coerceAtLeast(1))

            barPaint.color = color.toArgb()
            barPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
            barPaint.alpha = GLOW_ALPHA
            trailCanvas.drawLine(cx, centerY - half, cx, centerY + half, barPaint)

            barPaint.maskFilter = null
            barPaint.alpha = 255
            trailCanvas.drawLine(cx, centerY - half, cx, centerY + half, barPaint)
        }

        drawRect(color = Color.Black)
        drawImage(trail.asImageBitmap())
    }
}

/** Piecewise-lerps through [RAINBOW_STOPS] at position [t] (0 = leftmost bar, 1 = rightmost). */
private fun rainbowColor(t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    for (i in 0 until RAINBOW_STOPS.size - 1) {
        val (t0, c0) = RAINBOW_STOPS[i]
        val (t1, c1) = RAINBOW_STOPS[i + 1]
        if (clamped <= t1 || i == RAINBOW_STOPS.size - 2) {
            val localT = ((clamped - t0) / (t1 - t0)).coerceIn(0f, 1f)
            return lerpColor(c0, c1, localT)
        }
    }
    return RAINBOW_STOPS.last().second
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
