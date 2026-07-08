package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.ln

/** A handful of round frequencies labeled along the bottom, matching the Spectrum view's axis. */
private val FREQ_LABELS_HZ = listOf(60f, 250f, 1000f, 4000f, 16000f)
private const val REFERENCE_FRACTION = 0.35f

/**
 * A smooth, long-averaged spectral curve rather than fast-moving bars: shows the overall tonal
 * balance of what's playing -- bass-heavy, bright, scooped mids, and so on -- against a flat
 * dashed reference line, the way a mastering engineer would eyeball a track's EQ curve.
 */
@Composable
fun TonalBalanceScreen(engine: TonalBalanceEngine) {
    val textMeasurer = rememberTextMeasurer()
    val freqLabels = remember(textMeasurer) {
        FREQ_LABELS_HZ.map { hz ->
            val label = if (hz >= 1000f) "${(hz / 1000f).toInt()}k" else "${hz.toInt()}"
            hz to textMeasurer.measure(
                label,
                style = TextStyle(fontSize = 10.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace),
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        drawRect(color = VisualizerTheme.BACKGROUND)

        val paddingX = size.width * 0.06f
        val usableWidth = size.width - paddingX * 2f
        val baseline = size.height * 0.82f
        val maxHeight = size.height * 0.62f

        val referenceY = baseline - REFERENCE_FRACTION * maxHeight
        drawLine(
            color = VisualizerTheme.TEXT_SECONDARY,
            start = Offset(paddingX, referenceY),
            end = Offset(size.width - paddingX, referenceY),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )

        val n = engine.smoothedBands.size
        val pitch = usableWidth / (n - 1)
        val points = (0 until n).map { i ->
            val level = engine.smoothedBands[i].coerceIn(0f, 1f)
            Offset(paddingX + i * pitch, baseline - level * maxHeight)
        }

        val fillPath = Path().apply {
            addSmoothedCurve(points)
            lineTo(points.last().x, baseline)
            lineTo(points.first().x, baseline)
            close()
        }
        drawPath(fillPath, color = VisualizerTheme.ACCENT.copy(alpha = 0.22f))

        val strokePath = Path().apply { addSmoothedCurve(points) }
        drawPath(strokePath, color = VisualizerTheme.ACCENT, style = Stroke(width = 3f))

        for ((hz, label) in freqLabels) {
            val x = paddingX + xFractionForFrequency(hz) * usableWidth
            drawText(label, topLeft = Offset(x - label.size.width / 2f, baseline + 8f))
        }
    }
}

/** Appends a quadratic midpoint-smoothed curve through [points] to this path, starting a new subpath. */
private fun Path.addSmoothedCurve(points: List<Offset>) {
    if (points.isEmpty()) return
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2f
        val midY = (prev.y + curr.y) / 2f
        quadraticBezierTo(prev.x, prev.y, midX, midY)
    }
    lineTo(points.last().x, points.last().y)
}

/** Where along the log-spaced band axis [hz] falls, matching [SpectrumAnalyzer]'s band layout. */
private fun xFractionForFrequency(hz: Float): Float {
    val logMin = ln(SpectrumAnalyzer.MIN_FREQ_HZ)
    val logMax = ln(SpectrumAnalyzer.MAX_FREQ_HZ)
    return ((ln(hz) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
}
