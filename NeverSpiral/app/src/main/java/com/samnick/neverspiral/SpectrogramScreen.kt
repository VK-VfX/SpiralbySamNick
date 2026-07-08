package com.samnick.neverspiral

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp

private val DEEP_BLUE = Color(0xFF1C4E66)
private val NEAR_WHITE = Color(0xFFEAF6F8)

/**
 * A scrolling time/frequency heatmap: the same log-spaced FFT bands as the Spectrum view, but
 * showing history instead of just the instant, so harmonic content and decay/reverb tails read as
 * shape over time rather than a single flickering frame.
 *
 * Rendered into a tiny [SpectrogramEngine.COLUMN_COUNT] x bandCount bitmap -- one pixel per
 * time/frequency cell -- rebuilt via a single bulk [Bitmap.setPixels] call whenever a new FFT
 * frame arrives (tracked via [SpectrogramEngine.generation]), then scaled up to fill the canvas.
 * That keeps the cost independent of screen resolution: a handful of bulk pixel writes at ~20Hz,
 * not thousands of individual draw calls.
 */
@Composable
fun SpectrogramScreen(engine: SpectrogramEngine, bandCount: Int) {
    val bitmapHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastGenerationHolder = remember { intArrayOf(-1) }
    val textMeasurer = rememberTextMeasurer()
    val lowLabel = remember(textMeasurer) {
        textMeasurer.measure("40Hz", style = TextStyle(fontSize = 10.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace))
    }
    val highLabel = remember(textMeasurer) {
        textMeasurer.measure("16kHz", style = TextStyle(fontSize = 10.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace))
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        var bitmap = bitmapHolder[0]
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(SpectrogramEngine.COLUMN_COUNT, bandCount, Bitmap.Config.ARGB_8888)
            bitmapHolder[0] = bitmap
        }

        if (engine.generation != lastGenerationHolder[0]) {
            lastGenerationHolder[0] = engine.generation
            val pixels = IntArray(SpectrogramEngine.COLUMN_COUNT * bandCount)
            for (col in 0 until SpectrogramEngine.COLUMN_COUNT) {
                val bands = engine.columnAt(col)
                for (row in 0 until bandCount) {
                    val level = bands[row].coerceIn(0f, 1f)
                    // Row 0 is the lowest frequency band; flip so low frequencies sit at the bottom.
                    val y = bandCount - 1 - row
                    pixels[y * SpectrogramEngine.COLUMN_COUNT + col] = heatColor(level).toArgb()
                }
            }
            bitmap.setPixels(pixels, 0, SpectrogramEngine.COLUMN_COUNT, 0, 0, SpectrogramEngine.COLUMN_COUNT, bandCount)
        }

        drawRect(color = VisualizerTheme.BACKGROUND)
        drawImage(image = bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))

        drawText(highLabel, topLeft = Offset(8f, 8f))
        drawText(lowLabel, topLeft = Offset(8f, size.height - lowLabel.size.height - 8f))
    }
}

/** Near-black (silent) through deep blue and cyan to near-white, with red reserved for the clip zone -- matching the Spectrum view's palette. */
private fun heatColor(level: Float): Color = when {
    level < 0.35f -> lerpColor(VisualizerTheme.BACKGROUND, DEEP_BLUE, level / 0.35f)
    level < 0.7f -> lerpColor(DEEP_BLUE, VisualizerTheme.ACCENT, (level - 0.35f) / 0.35f)
    level < 0.92f -> lerpColor(VisualizerTheme.ACCENT, NEAR_WHITE, (level - 0.7f) / 0.22f)
    else -> lerpColor(NEAR_WHITE, VisualizerTheme.CRITICAL, (level - 0.92f) / 0.08f)
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * c,
        green = a.green + (b.green - a.green) * c,
        blue = a.blue + (b.blue - a.blue) * c,
        alpha = 1f,
    )
}
