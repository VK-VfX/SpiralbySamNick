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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/** dB reference lines drawn behind the bars, matching a studio spectrum analyzer's grid. */
private val GRID_DB_LINES = listOf(0f, -12f, -24f, -36f, -48f, -60f)

/** A handful of round frequencies labeled along the bottom, for orientation across the range. */
private val FREQ_LABELS_HZ = listOf(60f, 250f, 1000f, 4000f, 16000f)
private val DEEP_BLUE = Color(0xFF1C4E66)
private val CYAN = VisualizerTheme.ACCENT
private val NEAR_WHITE = Color(0xFFEAF6F8)
private val CLASSIC_GREEN = Color(0xFF3DDC5A)
private val CLASSIC_YELLOW = Color(0xFFE8E23D)
private val CLASSIC_ORANGE = Color(0xFFF08A2E)

/** Renders [engine]'s smoothed frequency bands as a bar spectrum, in the cool blue-to-white studio
 * palette, a classic green-yellow-red gradient, or Neon Cyan Pulse's frequency-reactive coloring
 * (each bar rests at cyan and blends toward a color keyed to its own frequency band as its level
 * rises), per [settings]. Bars get a soft blurred glow, a single blurred copy of the whole row
 * composited before the crisp bars rather than a per-bar blur -- the same technique the mirrored
 * bar spectrum modes use, since blurring 28 bars individually every frame is far more expensive
 * than blurring one composited layer once. */
@Composable
fun SpectrumScreen(engine: SpectrumEngine, settings: SpectrumSettings) {
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
        val gap = usableWidth * 0.012f
        val barWidth = (usableWidth - gap * (bandCount - 1)) / bandCount
        val baseline = size.height * 0.82f
        val maxBarHeight = size.height * 0.62f

        // A single blurred composite of every bar, drawn once before the crisp bars themselves --
        // far cheaper than blurring each of the bandCount bars individually every frame.
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
            val barHeight = (level * maxBarHeight).coerceAtLeast(barWidth * 0.3f)
            val x = paddingX + i * (barWidth + gap)
            val positionT = i.toFloat() / (bandCount - 1).coerceAtLeast(1)
            glowPaint.color = colorForLevel(level, settings.colorScheme, positionT).toArgb()
            glowCanvas.drawRect(x, baseline - barHeight, x + barWidth, baseline, glowPaint)
        }
        drawImage(glow.asImageBitmap())

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
            val positionT = i.toFloat() / (bandCount - 1).coerceAtLeast(1)

            drawRoundRect(
                color = colorForLevel(level, settings.colorScheme, positionT),
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
            val x = paddingX + SpectrumAnalyzer.xFractionForFrequency(hz) * usableWidth
            drawText(label, topLeft = Offset(x - label.size.width / 2f, baseline + 8f))
        }
    }
}

private fun colorForLevel(level: Float, scheme: SpectrumColorScheme, positionT: Float): Color = when (scheme) {
    SpectrumColorScheme.COOL -> coolColorForLevel(level)
    SpectrumColorScheme.CLASSIC -> classicColorForLevel(level)
    SpectrumColorScheme.FREQUENCY -> frequencyReactiveColorForLevel(level, positionT)
}

/** Cool blue at low level through cyan and near-white, with red reserved for the clip zone. */
private fun coolColorForLevel(level: Float): Color = when {
    level < 0.75f -> lerpColor(DEEP_BLUE, CYAN, level / 0.75f)
    level < 0.92f -> lerpColor(CYAN, NEAR_WHITE, (level - 0.75f) / 0.17f)
    else -> lerpColor(NEAR_WHITE, VisualizerTheme.CRITICAL, (level - 0.92f) / 0.08f)
}

/** Green at low level, sweeping through yellow and orange to red at high level -- the classic look. */
private fun classicColorForLevel(level: Float): Color = when {
    level < 0.5f -> lerpColor(CLASSIC_GREEN, CLASSIC_YELLOW, level / 0.5f)
    level < 0.8f -> lerpColor(CLASSIC_YELLOW, CLASSIC_ORANGE, (level - 0.5f) / 0.3f)
    else -> lerpColor(CLASSIC_ORANGE, VisualizerTheme.CRITICAL, (level - 0.8f) / 0.2f)
}

/** Blends from resting cyan toward [frequencyZoneColor] at [positionT] as [level] rises -- the
 * same per-bar frequency-reactive treatment Neon Cyan Pulse uses (bass reads red, treble violet),
 * applied to Spectrum's own upward-growing bars instead of a mirrored pulse. */
private fun frequencyReactiveColorForLevel(level: Float, positionT: Float): Color {
    val zone = frequencyZoneColor(positionT)
    val mix = level.pow(0.55f) * 0.92f
    return lerpGradientColor(NEON_CYAN, zone, mix)
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
