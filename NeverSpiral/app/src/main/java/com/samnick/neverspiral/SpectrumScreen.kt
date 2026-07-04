package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/** Renders [engine]'s smoothed frequency bands as a classic green-to-red bar spectrum. */
@Composable
fun SpectrumScreen(engine: SpectrumEngine) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual bar/peak data lives in plain arrays that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        drawRect(color = Color(0xFF0B0B0E))

        val bandCount = engine.bands.size
        val paddingX = size.width * 0.04f
        val usableWidth = size.width - paddingX * 2f
        val gap = usableWidth * 0.012f
        val barWidth = (usableWidth - gap * (bandCount - 1)) / bandCount
        val baseline = size.height * 0.82f
        val maxBarHeight = size.height * 0.62f

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
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(x, peakY),
                end = Offset(x + barWidth, peakY),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Green at low level, sweeping through yellow and orange to red at high level. */
private fun colorForLevel(level: Float): Color = when {
    level < 0.5f -> lerpColor(Color(0xFF3DDC5A), Color(0xFFE8E23D), level / 0.5f)
    level < 0.8f -> lerpColor(Color(0xFFE8E23D), Color(0xFFF08A2E), (level - 0.5f) / 0.3f)
    else -> lerpColor(Color(0xFFF08A2E), Color(0xFFE8342B), (level - 0.8f) / 0.2f)
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
