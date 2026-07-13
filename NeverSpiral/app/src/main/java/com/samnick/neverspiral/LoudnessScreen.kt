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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private val SCALE_TICKS_LUFS = listOf(0f, -6f, -12f, -18f, -23f, -30f, -40f)

/**
 * A modern loudness meter: a momentary-loudness bar (the fast-moving one), a short-term marker
 * line riding on top of it, digital readouts for all three BS.1770-style time windows plus
 * loudness range, and a scrolling history trend line -- the metric streaming platforms actually
 * normalize to, as a complement to the vintage-ballistics VU meter.
 */
@Composable
fun LoudnessScreen(engine: LoudnessEngine, settings: LoudnessSettings) {
    val textMeasurer = rememberTextMeasurer()
    val tickLabels = remember(textMeasurer) {
        SCALE_TICKS_LUFS.map { lufs ->
            lufs to textMeasurer.measure(
                lufs.toInt().toString(),
                style = TextStyle(fontSize = 10.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace),
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = VisualizerTheme.BACKGROUND)

        val barLeft = size.width * 0.14f
        val barRight = size.width * 0.42f
        val barTop = size.height * 0.08f
        val barBottom = size.height * 0.62f
        val barHeight = barBottom - barTop

        fun yForLufs(lufs: Float): Float {
            val frac = ((lufs - LoudnessEngine.DISPLAY_FLOOR_LUFS) / (LoudnessEngine.DISPLAY_CEILING_LUFS - LoudnessEngine.DISPLAY_FLOOR_LUFS)).coerceIn(0f, 1f)
            return barBottom - frac * barHeight
        }

        drawRoundRect(
            color = VisualizerTheme.PANEL_RAISED,
            topLeft = Offset(barLeft, barTop),
            size = Size(barRight - barLeft, barHeight),
            cornerRadius = CornerRadius(6f, 6f),
        )

        val momentaryY = yForLufs(engine.momentaryLufs)
        val momentaryColor = colorForLufs(engine.momentaryLufs)
        drawRoundRect(
            color = momentaryColor,
            topLeft = Offset(barLeft, momentaryY),
            size = Size(barRight - barLeft, barBottom - momentaryY),
            cornerRadius = CornerRadius(6f, 6f),
        )

        val shortTermY = yForLufs(engine.shortTermLufs)
        drawLine(
            color = VisualizerTheme.TEXT_PRIMARY,
            start = Offset(barLeft - 6f, shortTermY),
            end = Offset(barRight + 6f, shortTermY),
            strokeWidth = 2.5f,
        )

        for ((lufs, label) in tickLabels) {
            val y = yForLufs(lufs)
            drawLine(
                color = VisualizerTheme.HAIRLINE,
                start = Offset(barLeft, y),
                end = Offset(barRight, y),
                strokeWidth = 1f,
            )
            drawText(label, topLeft = Offset(barRight + 12f, y - label.size.height / 2f))
        }

        // Dashed marker at the selected normalization target.
        val targetLufs = settings.target.targetLufs
        val targetY = yForLufs(targetLufs)
        drawLine(
            color = VisualizerTheme.WARN,
            start = Offset(barLeft - 6f, targetY),
            end = Offset(barRight + 6f, targetY),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
        )

        // Digital readouts to the right of the bar.
        val readoutX = size.width * 0.58f
        var readoutY = size.height * 0.10f
        readoutY = drawReadout(textMeasurer, "MOMENTARY", "%.1f LUFS".format(engine.momentaryLufs), momentaryColor, readoutX, readoutY)
        readoutY = drawReadout(textMeasurer, "SHORT-TERM", "%.1f LUFS".format(engine.shortTermLufs), VisualizerTheme.ACCENT, readoutX, readoutY + 18f)
        readoutY = drawReadout(textMeasurer, "INTEGRATED", "%.1f LUFS".format(engine.integratedLufs), VisualizerTheme.TEXT_PRIMARY, readoutX, readoutY + 18f)
        readoutY = drawReadout(textMeasurer, "LOUDNESS RANGE", "%.1f LU".format(engine.loudnessRange), VisualizerTheme.TEXT_SECONDARY, readoutX, readoutY + 18f)

        val delta = engine.integratedLufs - targetLufs
        val deltaColor = if (abs(delta) <= 1f) VisualizerTheme.ACCENT else VisualizerTheme.WARN
        drawReadout(
            textMeasurer,
            "VS ${settings.target.label.uppercase()} (${targetLufs.toInt()})",
            (if (delta >= 0f) "+" else "") + "%.1f LU".format(delta),
            deltaColor,
            readoutX,
            readoutY + 18f,
        )

        // Scrolling short-term history trend line along the bottom.
        val historyTop = size.height * 0.72f
        val historyBottom = size.height * 0.92f
        val historyLeft = size.width * 0.08f
        val historyRight = size.width * 0.92f
        drawLine(
            color = VisualizerTheme.HAIRLINE,
            start = Offset(historyLeft, historyBottom),
            end = Offset(historyRight, historyBottom),
            strokeWidth = 1f,
        )
        val count = LoudnessEngine.HISTORY_SIZE
        val pitch = (historyRight - historyLeft) / (count - 1)
        var previous: Offset? = null
        for (i in 0 until count) {
            val lufs = engine.history[i]
            val frac = ((lufs - LoudnessEngine.DISPLAY_FLOOR_LUFS) / (LoudnessEngine.DISPLAY_CEILING_LUFS - LoudnessEngine.DISPLAY_FLOOR_LUFS)).coerceIn(0f, 1f)
            val point = Offset(historyLeft + i * pitch, historyBottom - frac * (historyBottom - historyTop))
            val prev = previous
            if (prev != null) {
                drawLine(color = VisualizerTheme.ACCENT.copy(alpha = 0.8f), start = prev, end = point, strokeWidth = 2f, cap = StrokeCap.Round)
            }
            previous = point
        }
    }
}

private fun DrawScope.drawReadout(
    textMeasurer: TextMeasurer,
    label: String,
    value: String,
    valueColor: Color,
    x: Float,
    y: Float,
): Float {
    val labelLayout = textMeasurer.measure(
        label,
        style = TextStyle(fontSize = 10.sp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
    )
    val valueLayout = textMeasurer.measure(
        value,
        style = TextStyle(fontSize = 18.sp, color = valueColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
    )
    drawText(labelLayout, topLeft = Offset(x, y))
    drawText(valueLayout, topLeft = Offset(x, y + labelLayout.size.height + 2f))
    return y + labelLayout.size.height + valueLayout.size.height + 2f
}

private fun colorForLufs(lufs: Float): Color = when {
    lufs > -6f -> VisualizerTheme.CRITICAL
    lufs > -18f -> VisualizerTheme.WARN
    else -> VisualizerTheme.ACCENT
}
