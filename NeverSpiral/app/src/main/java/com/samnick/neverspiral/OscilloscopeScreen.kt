package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

private val SCOPE_COLOR = Color(0xFF39FF14)
private val GRID_COLOR = Color(0xFF163318)

private const val GRID_COLUMNS = 8
private const val GRID_ROWS = 4

/**
 * Classic Y-T oscilloscope view: amplitude on the vertical axis, time flowing left to right,
 * same as a benchtop scope's normal (non X/Y) mode -- the shape traces the waveform itself, not
 * a stereo Lissajous figure.
 */
@Composable
fun OscilloscopeScreen(engine: OscilloscopeEngine) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual trace data lives in a plain array that Compose can't observe on its own.
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
        val n = engine.samples.size
        val path = Path()
        for (i in 0 until n) {
            val x = size.width * i / (n - 1).coerceAtLeast(1)
            val y = centerY - engine.samples[i] * amplitude
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Soft glow pass underneath a bright core stroke, mimicking phosphor persistence.
        drawPath(
            path,
            color = SCOPE_COLOR.copy(alpha = 0.35f),
            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path,
            color = SCOPE_COLOR,
            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
