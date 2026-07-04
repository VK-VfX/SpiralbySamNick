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

private val SCOPE_COLOR = Color(0xFF39FF6A)
private val GRID_COLOR = Color(0xFF16241A)

/**
 * X/Y "oscilloscope music" view: the left channel drives the horizontal position and the right
 * channel the vertical position, exactly like feeding two channels into a scope's X/Y mode --
 * a sine on both channels traces a circle, matched square waves trace geometric shapes.
 */
@Composable
fun OscilloscopeScreen(engine: OscilloscopeEngine) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual trace data lives in plain arrays that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        drawRect(color = Color(0xFF060A07))

        val center = Offset(size.width / 2f, size.height / 2f)
        val scale = minOf(size.width, size.height) / 2f * 0.85f

        // Faint crosshair, reminiscent of a real oscilloscope graticule.
        drawLine(GRID_COLOR, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2f)
        drawLine(GRID_COLOR, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 2f)

        val path = Path()
        val n = engine.pointsX.size
        for (i in 0 until n) {
            val x = center.x + engine.pointsX[i] * scale
            // Flip Y so a positive right-channel value draws upward, matching a real scope.
            val y = center.y - engine.pointsY[i] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Soft glow pass underneath a bright core stroke, mimicking phosphor persistence.
        drawPath(
            path,
            color = SCOPE_COLOR.copy(alpha = 0.35f),
            style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path,
            color = SCOPE_COLOR,
            style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
