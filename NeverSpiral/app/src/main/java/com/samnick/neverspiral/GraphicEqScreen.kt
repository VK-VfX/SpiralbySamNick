package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

private const val SEGMENT_COUNT = 18
private const val SEGMENT_GAP_FRACTION = 0.24f
private const val COLUMN_GAP_FRACTION = 0.18f
private const val PEAK_SEGMENT_HEIGHT_FRACTION = 0.4f

/**
 * A classic discrete-LED graphic-equalizer bank -- the kind of spectrum display built into
 * receivers and separates -- rather than smooth continuous bars: each band lights up a stack of
 * individual segments bottom-to-top, with a bright peak-hold segment riding above them. Reuses
 * the same [SpectrumEngine] data as the Spectrum view (fast-rise/slow-fall bands, peak-hold caps)
 * so this is a different rendering treatment of already-proven data, not new DSP.
 */
@Composable
fun GraphicEqScreen(engine: SpectrumEngine) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual bar/peak data lives in plain arrays that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        drawRect(color = VisualizerTheme.BACKGROUND)

        val bandCount = engine.bands.size
        val paddingX = size.width * 0.03f
        val usableWidth = size.width - paddingX * 2f
        val columnPitch = usableWidth / bandCount
        val columnWidth = columnPitch * (1f - COLUMN_GAP_FRACTION)

        val top = size.height * 0.08f
        val bottom = size.height * 0.86f
        val totalHeight = bottom - top
        val segmentPitch = totalHeight / SEGMENT_COUNT
        val segmentHeight = segmentPitch * (1f - SEGMENT_GAP_FRACTION)

        for (i in 0 until bandCount) {
            val level = engine.bands[i].coerceIn(0f, 1f)
            val peak = engine.peaks[i].coerceIn(0f, 1f)
            val litSegments = (level * SEGMENT_COUNT).toInt().coerceIn(0, SEGMENT_COUNT)
            val peakSegment = (peak * SEGMENT_COUNT).toInt().coerceIn(0, SEGMENT_COUNT - 1)
            val x = paddingX + i * columnPitch + (columnPitch - columnWidth) / 2f

            for (s in 0 until SEGMENT_COUNT) {
                val y = bottom - (s + 1) * segmentPitch + (segmentPitch - segmentHeight)
                val lit = s < litSegments
                val color = if (lit) colorForSegment(s.toFloat() / SEGMENT_COUNT) else VisualizerTheme.PANEL_RAISED
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(columnWidth, segmentHeight),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }

            val peakY = bottom - (peakSegment + 1) * segmentPitch + (segmentPitch - segmentHeight)
            drawRoundRect(
                color = VisualizerTheme.TEXT_PRIMARY,
                topLeft = Offset(x, peakY),
                size = Size(columnWidth, segmentHeight * PEAK_SEGMENT_HEIGHT_FRACTION),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

/** Green/cyan for the lower two-thirds, amber approaching the top, red for the last segment -- a clip warning. */
private fun colorForSegment(fracFromBottom: Float): Color = when {
    fracFromBottom < 0.6f -> VisualizerTheme.ACCENT
    fracFromBottom < 0.89f -> VisualizerTheme.WARN
    else -> VisualizerTheme.CRITICAL
}
