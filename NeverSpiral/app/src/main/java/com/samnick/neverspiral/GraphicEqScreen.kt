package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.ln
import kotlin.math.pow

private const val SEGMENT_COUNT = 18
private const val SEGMENT_GAP_FRACTION = 0.24f
private const val COLUMN_GAP_FRACTION = 0.18f
private const val PEAK_SEGMENT_HEIGHT_FRACTION = 0.4f

/** dB reference lines and frequency labels, matching the Spectrum view's axes. */
private val GRID_DB_LINES = listOf(0f, -12f, -24f, -36f, -48f, -60f)
private val FREQ_LABELS_HZ = listOf(60f, 250f, 1000f, 4000f, 16000f)

/**
 * A classic discrete-LED graphic-equalizer bank -- the kind of spectrum display built into
 * receivers and separates -- rather than smooth continuous bars: each band lights up a stack of
 * individual segments bottom-to-top, with a bright peak-hold segment riding above them. Reuses
 * the same [SpectrumEngine] data as the Spectrum view (fast-rise/slow-fall bands, peak-hold caps)
 * so this is a different rendering treatment of already-proven data, not new DSP. Lit segments get
 * a soft blurred glow -- a single blurred composite of just the lit segments, drawn once before the
 * crisp LED columns -- the same single-bitmap-blur technique the bar spectrum modes use.
 *
 * Color is either the classic green/amber/red-by-height LED bank look, or (per [settings]) the
 * same frequency-reactive treatment Neon Cyan Pulse and Spectrum use: a column's lit segments rest
 * near cyan and blend toward a color keyed to that column's own frequency band as its level rises,
 * though the very top segment always hard-flashes red as a clip warning regardless of scheme.
 */
@Composable
fun GraphicEqScreen(engine: SpectrumEngine, settings: GraphicEqSettings) {
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
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

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)

        val bandCount = engine.bands.size
        val paddingX = size.width * 0.075f
        val usableWidth = size.width - paddingX * 2f
        val columnPitch = usableWidth / bandCount
        val columnWidth = columnPitch * (1f - COLUMN_GAP_FRACTION)

        val top = size.height * 0.08f
        val bottom = size.height * 0.82f
        val totalHeight = bottom - top
        val segmentPitch = totalHeight / SEGMENT_COUNT
        val segmentHeight = segmentPitch * (1f - SEGMENT_GAP_FRACTION)

        // A single blurred composite of just the lit segments, drawn once before the crisp LED
        // columns -- far cheaper than blurring each lit segment individually every frame.
        var glow = glowHolder[0]
        if (glow == null || glow.width != size.width.toInt() || glow.height != size.height.toInt()) {
            glow = Bitmap.createBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = 140
            maskFilter = BlurMaskFilter(size.minDimension * 0.02f, BlurMaskFilter.Blur.NORMAL)
        }
        for (i in 0 until bandCount) {
            val level = engine.bands[i].coerceIn(0f, 1f)
            val litSegments = (level * SEGMENT_COUNT).toInt().coerceIn(0, SEGMENT_COUNT)
            val x = paddingX + i * columnPitch + (columnPitch - columnWidth) / 2f
            val positionT = i.toFloat() / (bandCount - 1).coerceAtLeast(1)
            for (s in 0 until litSegments) {
                val y = bottom - (s + 1) * segmentPitch + (segmentPitch - segmentHeight)
                glowPaint.color = colorForSegment(s.toFloat() / SEGMENT_COUNT, settings.colorScheme, positionT, level).toArgb()
                glowCanvas.drawRect(x, y, x + columnWidth, y + segmentHeight, glowPaint)
            }
        }
        drawImage(glow.asImageBitmap())

        // dB reference grid, drawn first so the LED columns sit on top of it.
        for ((db, label) in gridLabels) {
            val frac = ((db - SpectrumAnalyzer.FLOOR_DB) / -SpectrumAnalyzer.FLOOR_DB).coerceIn(0f, 1f)
            val y = bottom - frac * totalHeight
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
            val litSegments = (level * SEGMENT_COUNT).toInt().coerceIn(0, SEGMENT_COUNT)
            val peakSegment = (peak * SEGMENT_COUNT).toInt().coerceIn(0, SEGMENT_COUNT - 1)
            val x = paddingX + i * columnPitch + (columnPitch - columnWidth) / 2f
            val positionT = i.toFloat() / (bandCount - 1).coerceAtLeast(1)

            for (s in 0 until SEGMENT_COUNT) {
                val y = bottom - (s + 1) * segmentPitch + (segmentPitch - segmentHeight)
                val lit = s < litSegments
                val color = if (lit) colorForSegment(s.toFloat() / SEGMENT_COUNT, settings.colorScheme, positionT, level) else VisualizerTheme.PANEL_RAISED
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

        // Frequency labels along the bottom for orientation across the audible range.
        for ((hz, label) in freqLabels) {
            val x = paddingX + xFractionForFrequency(hz) * usableWidth
            drawText(label, topLeft = Offset(x - label.size.width / 2f, bottom + 8f))
        }
    }
}

/** Where along the log-spaced band axis [hz] falls, matching [SpectrumAnalyzer]'s band layout. */
private fun xFractionForFrequency(hz: Float): Float {
    val logMin = ln(SpectrumAnalyzer.MIN_FREQ_HZ)
    val logMax = ln(SpectrumAnalyzer.MAX_FREQ_HZ)
    return ((ln(hz) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
}

private fun colorForSegment(fracFromBottom: Float, scheme: GraphicEqColorScheme, positionT: Float, level: Float): Color = when (scheme) {
    GraphicEqColorScheme.CLASSIC -> classicColorForSegment(fracFromBottom)
    GraphicEqColorScheme.FREQUENCY -> frequencyColorForSegment(fracFromBottom, positionT, level)
}

/** Green/cyan for the lower two-thirds, amber approaching the top, red for the last segment -- a clip warning. */
private fun classicColorForSegment(fracFromBottom: Float): Color = when {
    fracFromBottom < 0.6f -> VisualizerTheme.ACCENT
    fracFromBottom < 0.89f -> VisualizerTheme.WARN
    else -> VisualizerTheme.CRITICAL
}

/** Blends from resting cyan toward [frequencyZoneColor] at [positionT] as the column's own [level]
 * rises -- same treatment as Neon Cyan Pulse and Spectrum's Frequency scheme -- but the top segment
 * still hard-flashes red as a clip warning regardless of which frequency zone it's in. */
private fun frequencyColorForSegment(fracFromBottom: Float, positionT: Float, level: Float): Color {
    if (fracFromBottom >= 0.89f) return VisualizerTheme.CRITICAL
    val zone = frequencyZoneColor(positionT)
    val mix = level.pow(0.55f) * 0.92f
    return lerpGradientColor(NEON_CYAN, zone, mix)
}
