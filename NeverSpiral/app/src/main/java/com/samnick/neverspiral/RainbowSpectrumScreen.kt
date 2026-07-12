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
private const val GLOW_ALPHA = 150

/**
 * A mirrored FFT bar spectrum: each of [SpectrumEngine]'s bands is a bar reflecting top and bottom
 * off a horizontal center axis, rather than growing from the bottom only, colored by a fixed
 * horizontal rainbow gradient across the row, with a soft glow behind each bar on a pure black
 * background. Reuses [SpectrumEngine] directly -- the same fast-rise/slower-fall smoothed bands
 * [SpectrumScreen] and [GraphicEqScreen] draw -- so this is a different rendering treatment of
 * already-proven data, not new DSP.
 *
 * Bars are drawn once, solid, into their own bitmap; the glow is a *single* blurred copy of that
 * whole composited layer, not a per-bar blur. `BlurMaskFilter`'s cost is dominated by per-call
 * overhead, so blurring the whole row once is far cheaper than blurring 28 bars individually while
 * looking effectively identical -- this is what keeps the mode smooth on mid-range devices.
 */
@Composable
fun RainbowSpectrumScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
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
        val n = engine.bands.size.coerceAtMost(BAR_COUNT)
        val pitch = size.width / n
        val barWidth = pitch * BAR_WIDTH_FRACTION * settings.strokeWeight

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
        }

        for (i in 0 until n) {
            val level = (engine.bands[i].coerceIn(0f, 1f).pow(SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val half = (level * maxHalf).coerceAtLeast(barWidth * 0.3f)
            val cx = (i + 0.5f) * pitch
            barPaint.color = rainbowColor(i.toFloat() / (n - 1).coerceAtLeast(1)).toArgb()
            barsCanvas.drawLine(cx, centerY - half, cx, centerY + half, barPaint)
        }

        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(size.minDimension * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawBitmap(bars, 0f, 0f, glowPaint)

        drawRect(color = Color.Black)
        drawImage(glow.asImageBitmap())
        drawImage(bars.asImageBitmap())
    }
}
