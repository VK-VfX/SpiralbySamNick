package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

private val SCOPE_COLOR = Color(0xFF39FF14)

private const val GAP_FRACTION = 0.38f
private const val BAND_HEIGHT_FRACTION = 0.3f
private const val MIN_BAR_HEIGHT_FRACTION = 0.06f

/**
 * Modern, minimalist waveform bars -- mirrored symmetrically around the centerline, like a
 * podcast/SoundCloud waveform -- rather than a raw electrical scope trace. Confined to a slim
 * band instead of the full screen height, so it reads as a clean strip rather than a dense wall.
 */
@Composable
fun OscilloscopeScreen(engine: OscilloscopeEngine) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual bar data lives in a plain array that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        drawRect(color = Color(0xFF0B0B0E))

        val centerY = size.height / 2f
        val halfBand = size.height * BAND_HEIGHT_FRACTION / 2f
        val minHalfBar = size.height * MIN_BAR_HEIGHT_FRACTION / 2f

        val n = engine.columnPeak.size
        val pitch = size.width / n
        val barWidth = (pitch * (1f - GAP_FRACTION)).coerceAtLeast(2f)

        for (i in 0 until n) {
            val x = (i + 0.5f) * pitch
            val half = (engine.columnPeak[i] * halfBand).coerceAtLeast(minHalfBar)
            drawLine(
                color = SCOPE_COLOR,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
