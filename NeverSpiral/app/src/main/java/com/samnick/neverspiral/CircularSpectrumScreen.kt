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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** How many of [SpectrumEngine]'s FFT bands to draw -- one bar per band, same as Rainbow Spectrum. */
private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT

/** Fraction of each bar's angular pitch actually drawn -- the rest is the visible gap between bars. */
private const val BAR_WIDTH_FRACTION = 0.62f

/** Exponent applied to each band's level before it becomes length: <1 lifts quiet bands so the ring reads livelier. */
private const val SENSITIVITY_GAMMA = 0.85f

/** Fraction of the canvas's shorter side left empty at the center, where bars start growing outward from. */
private const val INNER_RADIUS_FRACTION = 0.20f

private const val GLOW_RADIUS_FRACTION = 0.018f
private const val GLOW_ALPHA = 150

/**
 * [RainbowSpectrumScreen] bent into a ring: the same [SpectrumEngine] bands, the same
 * [rainbowColor] gradient and single-composited-bitmap glow, but bars grow radially outward from
 * a circle instead of reflecting top/bottom off a horizontal axis. Band 0 (bass) starts at 12
 * o'clock and the ring sweeps clockwise through to the highest band, so the fixed rainbow gradient
 * reads the same low-to-high direction it does across Rainbow Spectrum's row.
 */
@Composable
fun CircularSpectrumScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
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

        val center = Offset(size.width / 2f, size.height / 2f)
        val innerRadius = size.minDimension * INNER_RADIUS_FRACTION
        val maxBarLength = size.minDimension * settings.height
        val n = engine.bands.size.coerceAtMost(BAR_COUNT)
        val arcPitch = (2.0 * PI * innerRadius / n).toFloat()
        val barWidth = arcPitch * BAR_WIDTH_FRACTION * settings.strokeWeight

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
        }

        for (i in 0 until n) {
            val level = (engine.bands[i].coerceIn(0f, 1f).pow(SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val barLength = (level * maxBarLength).coerceAtLeast(barWidth * 0.3f)
            // -90 degrees so band 0 starts at 12 o'clock rather than 3 o'clock, then sweeps
            // clockwise around the full circle.
            val angle = Math.toRadians((i.toFloat() / n) * 360.0 - 90.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val start = Offset(center.x + innerRadius * dx, center.y + innerRadius * dy)
            val end = Offset(center.x + (innerRadius + barLength) * dx, center.y + (innerRadius + barLength) * dy)

            barPaint.color = rainbowColor(i.toFloat() / (n - 1).coerceAtLeast(1)).toArgb()
            barsCanvas.drawLine(start.x, start.y, end.x, end.y, barPaint)
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
