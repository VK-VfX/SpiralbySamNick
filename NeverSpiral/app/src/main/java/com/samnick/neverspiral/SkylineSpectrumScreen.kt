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
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.pow

private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT
private const val BAR_WIDTH_FRACTION = 0.62f
private const val SENSITIVITY_GAMMA = 0.85f

private const val GLOW_RADIUS_FRACTION = 0.024f
private const val GLOW_ALPHA = 160

/** Back-to-front layers: [scale] shrinks each layer's max height, [alpha] and [brightnessFraction]
 * both dim it, so the back layers read as distant, hazier buildings and the front layer as the
 * nearest, brightest, tallest one -- a simple parallax-depth cue from three static draws of the
 * same data rather than actual depth or offset. */
private data class SkylineLayer(val scale: Float, val alpha: Int, val brightnessFraction: Float)

private val LAYERS = listOf(
    SkylineLayer(scale = 0.55f, alpha = 90, brightnessFraction = 0.45f),
    SkylineLayer(scale = 0.78f, alpha = 160, brightnessFraction = 0.7f),
    SkylineLayer(scale = 1f, alpha = 255, brightnessFraction = 1f),
)

/**
 * A single-hue FFT bar spectrum (color via [ColorWheelPicker] / [CustomColorSettings], like
 * [HorizonSpectrumScreen] and [DotSpectrumScreen]) drawn three times at decreasing scale, alpha,
 * and brightness ([LAYERS]) rather than once -- a city-skyline read, where the back layers sit
 * behind and below the front one like hazier, more distant buildings, instead of every mode's
 * usual single solid row. Bars grow from the bottom edge only, like [SpectrumScreen], not mirrored
 * -- a skyline's buildings all share one ground line.
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

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
        }

        for (layer in LAYERS) {
            barPaint.alpha = layer.alpha
            for (i in 0 until BAR_COUNT) {
                val rawLevel = engine.bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                val level = (rawLevel.pow(SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
                val barHeight = (level * maxHeight * layer.scale).coerceAtLeast(barWidth * 0.3f)
                val left = i * pitch + (pitch - barWidth) / 2f

                barPaint.color = android.graphics.Color.HSVToColor(
                    floatArrayOf(colorSettings.hue, colorSettings.saturation, colorSettings.value * layer.brightnessFraction),
                )
                barsCanvas.drawRect(left, baseY - barHeight, left + barWidth, baseY, barPaint)
            }
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
