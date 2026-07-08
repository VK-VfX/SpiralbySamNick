package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.ln

/** dB reference lines drawn behind the bars, matching a studio spectrum analyzer's grid. */
private val GRID_DB_LINES = listOf(0f, -12f, -24f, -36f, -48f, -60f)

/** A handful of round frequencies labeled along the bottom, for orientation across the range. */
private val FREQ_LABELS_HZ = listOf(60f, 250f, 1000f, 4000f, 16000f)
private val DEEP_BLUE = Color(0xFF1C4E66)
private val CYAN = VisualizerTheme.ACCENT
private val NEAR_WHITE = Color(0xFFEAF6F8)

/** Renders [engine]'s smoothed frequency bands as a cool blue-to-white studio spectrum, with a
 * red flash reserved for the clip zone right at the top -- rather than a green-to-red gradient
 * spread across the whole range. */
@Composable
fun SpectrumScreen(engine: SpectrumEngine) {
    val textMeasurer = rememberTextMeasurer()
    val gridLabels = remember(textMeasurer) {
        GRID_DB_LINES.map { db ->
            db to textMeasurer.measure(
                if (db == 0f) "0" else db.toInt().toString(),
                style = TextStyle(fontSize = 10.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace),
            )
        }
    }
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
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual bar/peak data lives in plain arrays that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        drawRect(color = VisualizerTheme.BACKGROUND)

        val bandCount = engine.bands.size
        val paddingX = size.width * 0.075f
        val usableWidth = size.width - paddingX * 2f
        val gap = usableWidth * 0.012f
        val barWidth = (usableWidth - gap * (bandCount - 1)) / bandCount
        val baseline = size.height * 0.82f
        val maxBarHeight = size.height * 0.62f

        // dB reference grid, drawn first so bars sit on top of it.
        for ((db, label) in gridLabels) {
            val frac = ((db - SpectrumAnalyzer.FLOOR_DB) / -SpectrumAnalyzer.FLOOR_DB).coerceIn(0f, 1f)
            val y = baseline - frac * maxBarHeight
            drawLine(
                color = VisualizerTheme.HAIRLINE,
                start = Offset(paddingX, y),
                end = Offset(size.width - paddingX, y),
                strokeWidth = 1f,
            )
            drawText(label, topLeft = Offset(paddingX - label.size.width - 6f, y - label.size.height / 2f))
        }

        for (i in 0 until bandCount) {
            val level = engine.bands[i].coerceIn(0f, 1f)
            val peak = engine.peaks[i].coerceIn(0f, 1f)
            val barHeight = (level * maxBarHeight).coerceAtLeast(barWidth * 0.3f)
            val x = paddingX + i * (barWidth + gap)

            drawRoundRect(
                color = colorForLevel(level),
                topLeft = Offset(x, baseline - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.25f, barWidth * 0.25f),
            )

            val peakY = baseline - peak * maxBarHeight
            drawLine(
                color = NEAR_WHITE.copy(alpha = 0.9f),
                start = Offset(x, peakY),
                end = Offset(x + barWidth, peakY),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }

        // Frequency labels along the bottom for orientation across the audible range.
        for ((hz, label) in freqLabels) {
            val x = paddingX + xFractionForFrequency(hz) * usableWidth
            drawText(label, topLeft = Offset(x - label.size.width / 2f, baseline + 8f))
        }
    }
}

/** Where along the log-spaced band axis [hz] falls, matching [SpectrumAnalyzer]'s band layout. */
private fun xFractionForFrequency(hz: Float): Float {
    val logMin = ln(SpectrumAnalyzer.MIN_FREQ_HZ)
    val logMax = ln(SpectrumAnalyzer.MAX_FREQ_HZ)
    return ((ln(hz) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
}

/** Cool blue at low level through cyan and near-white, with red reserved for the clip zone. */
private fun colorForLevel(level: Float): Color = when {
    level < 0.75f -> lerpColor(DEEP_BLUE, CYAN, level / 0.75f)
    level < 0.92f -> lerpColor(CYAN, NEAR_WHITE, (level - 0.75f) / 0.17f)
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
