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
import kotlin.math.pow

private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT
private const val BAR_WIDTH_FRACTION = 0.62f
private const val SENSITIVITY_GAMMA = 0.85f

private const val GLOW_RADIUS_FRACTION = 0.024f
private const val GLOW_ALPHA = 160

/** How dark the base of each bar goes, as a fraction of the picked color's own brightness -- the
 * tip stays the full picked color, the base fades nearly to black, so a single bar reads as a
 * vertical gradient of shaded bands rather than one flat fill. */
private const val BASE_BRIGHTNESS_FRACTION = 0.12f

/**
 * A single-hue FFT bar spectrum (color via [ColorWheelPicker] / [CustomColorSettings], like
 * [DotSpectrumScreen] and [ShadowWaveformScreen]) where each bar is its own vertical gradient --
 * the full picked color at the tip fading down to near-black at the base -- rather than one flat
 * fill, the shaded-segment look this mode is based on. Unlike every other bar mode, quiet bands are
 * never floored to a minimum visible height: a genuinely silent band draws at zero height, so real
 * gaps of silence between clusters of activity appear on their own from the live audio rather than
 * a bar mass that never fully empties. Bars grow from the bottom edge only, like [SpectrumScreen],
 * not mirrored.
 */
@Composable
fun SkylineSpectrumScreen(engine: SpectrumEngine, settings: BarSpectrumSettings, colorSettings: CustomColorSettings) {
    val barsHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
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

        val minDim = size.minDimension
        val baseY = size.height
        val maxHeight = size.height * settings.height * 2f
        val pitch = size.width / BAR_COUNT
        val barWidth = pitch * BAR_WIDTH_FRACTION * settings.strokeWeight

        val tipColor = android.graphics.Color.HSVToColor(
            floatArrayOf(colorSettings.hue, colorSettings.saturation, colorSettings.value),
        )
        val baseColor = android.graphics.Color.HSVToColor(
            floatArrayOf(colorSettings.hue, colorSettings.saturation, colorSettings.value * BASE_BRIGHTNESS_FRACTION),
        )

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
        }

        for (i in 0 until BAR_COUNT) {
            val rawLevel = engine.bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val level = (rawLevel.pow(SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val barHeight = level * maxHeight
            if (barHeight <= 0f) continue
            val left = i * pitch + (pitch - barWidth) / 2f
            val top = baseY - barHeight

            barPaint.shader = LinearGradient(
                left, top, left, baseY,
                tipColor, baseColor,
                Shader.TileMode.CLAMP,
            )
            barsCanvas.drawRect(left, top, left + barWidth, baseY, barPaint)
        }

        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawBitmap(bars, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(bars.asImageBitmap())
    }
}
