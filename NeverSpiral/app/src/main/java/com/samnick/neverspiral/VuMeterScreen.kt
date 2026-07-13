package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * dB-VU values that get a tick and a label, matching a classic analog VU face. These are laid
 * out with *even angular spacing between adjacent entries* (see [leanForDbVu]) rather than
 * spacing proportional to their dB gap -- which is what a real VU meter's dial looks like too:
 * -20 to -10 (a 10 dB gap) takes up the same arc as 0 to 1 (a 1 dB gap). Spacing by dB value
 * instead crushes the busy -3..+3 region into a sliver and was why labels were overlapping.
 */
private val TICK_VALUES = listOf(-20f, -10f, -7f, -5f, -3f, -2f, -1f, 0f, 1f, 2f, 3f)
private const val LEAN_MIN_DEG = -55f
private const val LEAN_MAX_DEG = 55f

private val FACE_COLOR = VisualizerTheme.PANEL_RAISED
private val NEEDLE_COLOR = VisualizerTheme.ACCENT
private val TICK_COLOR = VisualizerTheme.TEXT_SECONDARY
private val REDLINE_COLOR = VisualizerTheme.CRITICAL
private val LED_OFF_COLOR = Color(0xFF2A1214)
private val LED_ON_COLOR = VisualizerTheme.CRITICAL

/**
 * Tick labels used to be a fixed 13sp/22sp regardless of how big the meter itself actually
 * rendered -- fine on a typical tall portrait phone, but the meter shrinks a lot in landscape (and
 * on some smaller/narrower portrait screens too), while the fixed-size text didn't, so the widest
 * labels ("-20", "-10") could crowd or overlap neighbors. These fractions instead size the tick
 * font relative to the meter's own rendered height, so the fit stays consistent at any size: with
 * labels spaced at even angles (see [TICK_VALUES]) around labelRadius = 0.70*meterHeight, adjacent
 * label centers are 0.70*meterHeight*(110°/10 in radians) ≈ 0.134*meterHeight of arc apart, and a
 * 4-character monospace label like "-20" is roughly 2.4*fontSize wide -- so keeping fontSize under
 * about 0.056*meterHeight (0.134/2.4) leaves adjacent labels just touching at worst; this uses a
 * comfortably smaller fraction than that bound.
 */
private const val TICK_FONT_HEIGHT_FRACTION = 0.048f
private const val VU_LABEL_FONT_HEIGHT_FRACTION = 0.081f
private val TICK_FONT_SIZE_RANGE = 9f..17f
private val VU_LABEL_FONT_SIZE_RANGE = 14f..26f

/**
 * Pure rendering of [meter]'s current reading; stepping happens in the shared frame loop. The
 * needle gets a soft blurred glow behind it, the same single-bitmap-blur technique the mirrored
 * bar spectrum modes use.
 */
@Composable
fun VuMeterScreen(meter: VuMeterEngine) {
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Derived from the actual rendered canvas size so tick/label font sizes track the meter's own
    // size (see TICK_FONT_HEIGHT_FRACTION) instead of staying fixed while the meter shrinks.
    val meterHeightPx = remember(canvasSize) {
        val meterWidth = min(canvasSize.width * 0.84f, canvasSize.height * 0.72f)
        meterWidth * 0.62f
    }
    val tickFontSizeSp = remember(meterHeightPx, density) {
        with(density) { (meterHeightPx * TICK_FONT_HEIGHT_FRACTION).toDp().toSp() }
            .value.coerceIn(TICK_FONT_SIZE_RANGE.start, TICK_FONT_SIZE_RANGE.endInclusive).sp
    }
    val vuLabelFontSizeSp = remember(meterHeightPx, density) {
        with(density) { (meterHeightPx * VU_LABEL_FONT_HEIGHT_FRACTION).toDp().toSp() }
            .value.coerceIn(VU_LABEL_FONT_SIZE_RANGE.start, VU_LABEL_FONT_SIZE_RANGE.endInclusive).sp
    }

    val tickLayouts = remember(textMeasurer, tickFontSizeSp) {
        TICK_VALUES.map { value ->
            val label = if (value == 0f) "0" else if (value > 0f) "+${value.toInt()}" else value.toInt().toString()
            val color = if (value > 0f) REDLINE_COLOR else TICK_COLOR
            Triple(
                value,
                color,
                textMeasurer.measure(
                    label,
                    style = TextStyle(fontSize = tickFontSizeSp, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                ),
            )
        }
    }
    val vuLabelLayout = remember(textMeasurer, vuLabelFontSizeSp) {
        textMeasurer.measure(
            "VU",
            style = TextStyle(fontSize = vuLabelFontSizeSp, color = VisualizerTheme.TEXT_SECONDARY, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it },
    ) {
        drawRect(color = VisualizerTheme.BACKGROUND)

        val meterWidth = min(size.width * 0.84f, size.height * 0.72f)
        val meterHeight = meterWidth * 0.62f
        val topLeft = Offset(
            (size.width - meterWidth) / 2f,
            (size.height - meterHeight) / 2f,
        )

        // A single blurred copy of the needle, composited before the crisp native drawing below,
        // so it reads as a soft glow behind the needle -- BlurMaskFilter needs a software canvas
        // (Android silently ignores mask filters on Compose's hardware-accelerated one), which is
        // why this goes through an offscreen bitmap cleared fresh every frame.
        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val pivot = Offset(topLeft.x + meterWidth / 2f, topLeft.y + meterHeight * 1.05f)
        val needleLength = meterHeight * 0.90f
        val needleAngle = Math.toRadians(90.0 - leanForDbVu(meter.dbVu))
        val tip = Offset(
            pivot.x + needleLength * cos(needleAngle).toFloat(),
            pivot.y - needleLength * sin(needleAngle).toFloat(),
        )
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = 6f
            color = NEEDLE_COLOR.toArgb()
            alpha = 190
            maskFilter = BlurMaskFilter(size.minDimension * 0.022f, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawLine(pivot.x, pivot.y, tip.x, tip.y, glowPaint)
        drawImage(glow.asImageBitmap())

        drawVuMeter(textMeasurer, tickLayouts, vuLabelLayout, topLeft, meterWidth, meterHeight, meter.dbVu, meter.peakLedBrightness())
    }
}

private fun DrawScope.drawVuMeter(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    tickLayouts: List<Triple<Float, Color, TextLayoutResult>>,
    vuLabelLayout: TextLayoutResult,
    topLeft: Offset,
    width: Float,
    height: Float,
    dbVu: Float,
    peakBrightness: Float,
) {
    // Flat panel with a thin hairline border instead of a thick bezel -- the "modern mastering
    // suite" look reads as a recessed instrument in the panel rather than a boxed-in gauge.
    drawRoundRect(
        color = FACE_COLOR,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRoundRect(
        color = VisualizerTheme.HAIRLINE,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(width = 1.5f),
    )

    drawText(vuLabelLayout, topLeft = Offset(topLeft.x + width * 0.05f, topLeft.y + height * 0.07f))

    val ledCenter = Offset(topLeft.x + width * 0.92f, topLeft.y + height * 0.11f)
    val ledRadius = height * 0.045f
    if (peakBrightness > 0.02f) {
        drawCircle(color = LED_ON_COLOR.copy(alpha = peakBrightness * 0.4f), radius = ledRadius * 2.6f, center = ledCenter)
        drawCircle(color = LED_ON_COLOR.copy(alpha = peakBrightness * 0.75f), radius = ledRadius * 1.6f, center = ledCenter)
    }
    drawCircle(color = lerpColor(LED_OFF_COLOR, LED_ON_COLOR, peakBrightness), radius = ledRadius, center = ledCenter)
    drawCircle(color = VisualizerTheme.HAIRLINE, radius = ledRadius, center = ledCenter, style = Stroke(width = 1.5f))

    val pivot = Offset(topLeft.x + width / 2f, topLeft.y + height * 1.05f)
    val tickOuterRadius = height * 0.96f
    val tickInnerRadius = height * 0.84f
    val labelRadius = height * 0.70f
    val needleLength = height * 0.90f

    for ((value, color, layout) in tickLayouts) {
        val lean = leanForDbVu(value)
        val mathAngle = Math.toRadians(90.0 - lean)
        val dx = cos(mathAngle).toFloat()
        val dy = sin(mathAngle).toFloat()

        val inner = Offset(pivot.x + tickInnerRadius * dx, pivot.y - tickInnerRadius * dy)
        val outer = Offset(pivot.x + tickOuterRadius * dx, pivot.y - tickOuterRadius * dy)
        drawLine(color = color, start = inner, end = outer, strokeWidth = 2.5f, cap = StrokeCap.Round)

        val labelPos = Offset(
            pivot.x + labelRadius * dx - layout.size.width / 2f,
            pivot.y - labelRadius * dy - layout.size.height / 2f,
        )
        drawText(layout, topLeft = labelPos)
    }

    val needleLean = leanForDbVu(dbVu)
    val needleAngle = Math.toRadians(90.0 - needleLean)
    val tip = Offset(
        pivot.x + needleLength * cos(needleAngle).toFloat(),
        pivot.y - needleLength * sin(needleAngle).toFloat(),
    )
    drawLine(color = NEEDLE_COLOR.copy(alpha = 0.3f), start = pivot, end = tip, strokeWidth = 12f, cap = StrokeCap.Round)
    drawLine(color = NEEDLE_COLOR, start = pivot, end = tip, strokeWidth = 5f, cap = StrokeCap.Round)
    drawCircle(color = VisualizerTheme.PANEL, radius = height * 0.05f, center = pivot)
    drawCircle(color = NEEDLE_COLOR, radius = height * 0.03f, center = pivot)

    // A digital readout alongside the analog needle -- pairing both is a hallmark of serious
    // studio metering, where the needle gives a fast visual read of program energy and the
    // digits give an exact number.
    val readoutText = (if (dbVu >= 0f) "+" else "") + "%.1f".format(dbVu)
    val readoutColor = if (dbVu >= VuMeterEngine.SCALE_MAX_DB_VU - 0.15f) REDLINE_COLOR else VisualizerTheme.ACCENT
    val readoutLayout = textMeasurer.measure(
        readoutText,
        style = TextStyle(fontSize = 15.sp, color = readoutColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
    )
    drawText(
        readoutLayout,
        topLeft = Offset(
            topLeft.x + width * 0.05f,
            topLeft.y + height * 0.07f + vuLabelLayout.size.height + height * 0.02f,
        ),
    )
}

/**
 * Maps a dB-VU value to a lean angle in degrees from vertical, using the *position* of [value]
 * within [TICK_VALUES] rather than its raw magnitude -- so every adjacent pair of ticks gets an
 * equal slice of the sweep, and the needle interpolates smoothly between whichever two ticks
 * bracket its current reading.
 */
private fun leanForDbVu(value: Float): Float {
    val clamped = value.coerceIn(TICK_VALUES.first(), TICK_VALUES.last())
    var lowerIndex = 0
    for (i in 0 until TICK_VALUES.size - 1) {
        if (clamped >= TICK_VALUES[i] && clamped <= TICK_VALUES[i + 1]) {
            lowerIndex = i
            break
        }
    }
    val lowerValue = TICK_VALUES[lowerIndex]
    val upperValue = TICK_VALUES[lowerIndex + 1]
    val fraction = if (upperValue > lowerValue) (clamped - lowerValue) / (upperValue - lowerValue) else 0f
    val position = lowerIndex + fraction
    val t = position / (TICK_VALUES.size - 1)
    return LEAN_MIN_DEG + t * (LEAN_MAX_DEG - LEAN_MIN_DEG)
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
