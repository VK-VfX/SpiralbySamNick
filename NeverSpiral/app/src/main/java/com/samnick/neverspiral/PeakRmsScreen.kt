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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

private val SCALE_TICKS_DB = listOf(0f, -3f, -6f, -9f, -12f, -18f, -24f, -36f, -48f, -60f)

/**
 * A hardware-style dual bar meter: peak (fast, with a latched hold cap) next to RMS (the same
 * ~300ms window the VU meter uses), plus a crest-factor readout -- the gap between the two tells
 * you how dynamic or squashed a master is, which a single meter can't show on its own.
 */
@Composable
fun PeakRmsScreen(engine: PeakRmsEngine) {
    val textMeasurer = rememberTextMeasurer()
    val tickLabels = remember(textMeasurer) {
        SCALE_TICKS_DB.map { db ->
            db to textMeasurer.measure(
                db.toInt().toString(),
                style = TextStyle(fontSize = 10.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace),
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = VisualizerTheme.BACKGROUND)

        val barTop = size.height * 0.08f
        val barBottom = size.height * 0.78f
        val barHeight = barBottom - barTop

        val peakBarLeft = size.width * 0.30f
        val peakBarRight = size.width * 0.46f
        val rmsBarLeft = size.width * 0.54f
        val rmsBarRight = size.width * 0.70f

        fun yForDb(db: Float): Float {
            val frac = ((db - PeakRmsEngine.FLOOR_DB) / (PeakRmsEngine.CEILING_DB - PeakRmsEngine.FLOOR_DB)).coerceIn(0f, 1f)
            return barBottom - frac * barHeight
        }

        for ((db, label) in tickLabels) {
            val y = yForDb(db)
            drawLine(
                color = VisualizerTheme.HAIRLINE,
                start = Offset(peakBarLeft, y),
                end = Offset(rmsBarRight, y),
                strokeWidth = 1f,
            )
            drawText(label, topLeft = Offset(peakBarLeft - label.size.width - 10f, y - label.size.height / 2f))
        }

        drawRoundRect(
            color = VisualizerTheme.PANEL_RAISED,
            topLeft = Offset(peakBarLeft, barTop),
            size = Size(peakBarRight - peakBarLeft, barHeight),
            cornerRadius = CornerRadius(4f, 4f),
        )
        drawRoundRect(
            color = VisualizerTheme.PANEL_RAISED,
            topLeft = Offset(rmsBarLeft, barTop),
            size = Size(rmsBarRight - rmsBarLeft, barHeight),
            cornerRadius = CornerRadius(4f, 4f),
        )

        val peakY = yForDb(engine.peakDb)
        drawRoundRect(
            color = colorForDb(engine.peakDb),
            topLeft = Offset(peakBarLeft, peakY),
            size = Size(peakBarRight - peakBarLeft, barBottom - peakY),
            cornerRadius = CornerRadius(4f, 4f),
        )
        val peakHoldY = yForDb(engine.peakHoldDb)
        drawLine(
            color = VisualizerTheme.TEXT_PRIMARY,
            start = Offset(peakBarLeft - 4f, peakHoldY),
            end = Offset(peakBarRight + 4f, peakHoldY),
            strokeWidth = 2.5f,
        )

        val rmsY = yForDb(engine.rmsDb)
        drawRoundRect(
            color = colorForDb(engine.rmsDb),
            topLeft = Offset(rmsBarLeft, rmsY),
            size = Size(rmsBarRight - rmsBarLeft, barBottom - rmsY),
            cornerRadius = CornerRadius(4f, 4f),
        )

        val peakLabel = textMeasurer.measure("PEAK", style = TextStyle(fontSize = 11.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp))
        drawText(peakLabel, topLeft = Offset(peakBarLeft + (peakBarRight - peakBarLeft - peakLabel.size.width) / 2f, barBottom + 8f))
        val rmsLabel = textMeasurer.measure("RMS", style = TextStyle(fontSize = 11.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp))
        drawText(rmsLabel, topLeft = Offset(rmsBarLeft + (rmsBarRight - rmsBarLeft - rmsLabel.size.width) / 2f, barBottom + 8f))

        val crestLabel = textMeasurer.measure(
            "CREST FACTOR  %.1f dB".format(engine.crestFactorDb),
            style = TextStyle(fontSize = 15.sp, color = VisualizerTheme.ACCENT, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
        )
        drawText(crestLabel, topLeft = Offset((size.width - crestLabel.size.width) / 2f, size.height * 0.90f))
    }
}

private fun colorForDb(db: Float): Color = when {
    db > -3f -> VisualizerTheme.CRITICAL
    db > -12f -> VisualizerTheme.WARN
    else -> VisualizerTheme.ACCENT
}
