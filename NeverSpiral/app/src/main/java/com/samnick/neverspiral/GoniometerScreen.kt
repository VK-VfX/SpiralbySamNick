package com.samnick.neverspiral

import android.graphics.Bitmap
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.min

private const val DOT_SAMPLE_STRIDE = 4

/**
 * A goniometer, plotted on the mid/side axes rather than raw L/R: mono content collapses to a
 * vertical line, and drift to either side shows genuine stereo width or phase trouble -- the same
 * convention hardware phase scopes use. Dots are stamped into a persistent, fading bitmap trail
 * only when a new buffer actually arrives (tracked via [GoniometerEngine.generation]), same idea
 * as the waveform view's afterglow but gated per-buffer instead of per-frame since we're plotting
 * raw samples, not a fixed small point count.
 */
@Composable
fun GoniometerScreen(engine: GoniometerEngine, settings: GoniometerSettings) {
    val trailHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastGenerationHolder = remember { intArrayOf(-1) }
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame.
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

        val fadeAlpha = ((1f - settings.trailPersistence).coerceIn(0.04f, 1f) * 255).toInt()
        val fadeColor = (fadeAlpha shl 24) or (VisualizerTheme.BACKGROUND.toArgb() and 0x00FFFFFF)
        trailCanvas.drawColor(fadeColor, PorterDuff.Mode.SRC_OVER)

        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = min(size.width, size.height) * 0.46f

        val gridPaint = AndroidPaint().apply {
            color = VisualizerTheme.HAIRLINE.toArgb()
            alpha = 140
            strokeWidth = 1.5f
        }
        trailCanvas.drawLine(cx, cy - scale, cx, cy + scale, gridPaint) // mono (M) axis
        trailCanvas.drawLine(cx - scale, cy, cx + scale, cy, gridPaint) // side (S) axis
        trailCanvas.drawCircle(cx, cy, scale, gridPaint)

        if (engine.generation != lastGenerationHolder[0]) {
            lastGenerationHolder[0] = engine.generation
            val left = engine.latestLeft
            val right = engine.latestRight
            val dotPaint = AndroidPaint().apply {
                isAntiAlias = true
                color = VisualizerTheme.ACCENT.toArgb()
                alpha = 200
            }
            var i = 0
            while (i < left.size) {
                val l = left[i]
                val r = right[i]
                val s = (l - r) * 0.5f
                val m = (l + r) * 0.5f
                trailCanvas.drawCircle(cx + s * scale, cy - m * scale, 2.5f, dotPaint)
                i += DOT_SAMPLE_STRIDE
            }
        }

        drawRect(color = VisualizerTheme.BACKGROUND)
        drawImage(trail.asImageBitmap())

        val corr = engine.correlation
        val corrColor = when {
            corr > 0.5f -> VisualizerTheme.ACCENT
            corr > -0.3f -> VisualizerTheme.WARN
            else -> VisualizerTheme.CRITICAL
        }
        val corrLabel = (if (corr >= 0f) "+" else "") + "%.2f".format(corr)
        val corrLayout = textMeasurer.measure(
            "PHASE  $corrLabel",
            style = TextStyle(fontSize = 14.sp, color = corrColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
        )
        drawText(corrLayout, topLeft = Offset(12f, size.height - corrLayout.size.height - 12f))
    }
}
