package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

private val SCOPE_COLOR = Color(0xFF39FF14)
private val GRID_COLOR = Color(0xFF163318)

private const val GRID_COLUMNS = 8
private const val GRID_ROWS = 4

/**
 * Classic Y-T oscilloscope view: amplitude on the vertical axis, time flowing left to right, one
 * vertical min/max envelope bar per column -- the same technique real waveform displays use to
 * compress many audio cycles into limited pixels without it turning into aliased noise.
 */
@Composable
fun OscilloscopeScreen(engine: OscilloscopeEngine) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual trace data lives in plain arrays that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        drawRect(color = Color(0xFF060A07))

        // Graticule, reminiscent of a real oscilloscope screen.
        for (col in 1 until GRID_COLUMNS) {
            val x = size.width * col / GRID_COLUMNS
            drawLine(GRID_COLOR, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
        }
        for (row in 1 until GRID_ROWS) {
            val y = size.height * row / GRID_ROWS
            drawLine(GRID_COLOR, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
        }
        val centerY = size.height / 2f
        drawLine(GRID_COLOR, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2f)

        val amplitude = size.height * 0.42f
        val n = engine.columnMin.size
        val columnWidth = size.width / n
        val strokeWidth = (columnWidth * 0.9f).coerceAtLeast(1.5f)

        for (i in 0 until n) {
            val x = (i + 0.5f) * columnWidth
            val top = Offset(x, centerY - engine.columnMax[i] * amplitude)
            val bottom = Offset(x, centerY - engine.columnMin[i] * amplitude)

            // Soft glow pass underneath a bright core stroke, mimicking phosphor persistence.
            drawLine(SCOPE_COLOR.copy(alpha = 0.35f), top, bottom, strokeWidth = strokeWidth + 3f, cap = StrokeCap.Round)
            drawLine(SCOPE_COLOR, top, bottom, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
    }
}
