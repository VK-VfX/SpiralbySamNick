package com.samnick.neverspiral

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.isActive

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

private val FRAME_COLOR = Color(0xFF17151A)
private val BODY_COLOR = Color(0xFFE8DAB0)
private val NEEDLE_COLOR = Color(0xFF17151A)
private val REDLINE_COLOR = Color(0xFFB6342F)

@Composable
fun VuMeterScreen() {
    val meter = remember { VuMeterEngine() }
    val context = LocalContext.current
    var visualizerOn by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioCaptureService.start(context, result.resultCode, data)
            visualizerOn = true
        } else {
            visualizerOn = false
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    LaunchedEffect(meter) {
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                meter.step(dt, AudioAnalyzer.snapshots.value.raw)
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val tickLayouts = remember(textMeasurer) {
        TICK_VALUES.map { value ->
            val label = if (value == 0f) "0" else if (value > 0f) "+${value.toInt()}" else value.toInt().toString()
            val color = if (value > 0f) REDLINE_COLOR else FRAME_COLOR
            Triple(value, color, textMeasurer.measure(label, style = TextStyle(fontSize = 14.sp, color = color, fontWeight = FontWeight.Medium)))
        }
    }
    val vuLabelLayout = remember(textMeasurer) {
        textMeasurer.measure("VU", style = TextStyle(fontSize = 26.sp, color = FRAME_COLOR, fontWeight = FontWeight.Bold))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color(0xFF0B0B0E))

            val meterWidth = min(size.width * 0.84f, size.height * 0.72f)
            val meterHeight = meterWidth * 0.62f
            val topLeft = Offset(
                (size.width - meterWidth) / 2f,
                (size.height - meterHeight) / 2f,
            )

            drawVuMeter(tickLayouts, vuLabelLayout, topLeft, meterWidth, meterHeight, meter.dbVu)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Button(
                onClick = {
                    if (visualizerOn) {
                        AudioCaptureService.stop(context)
                        meter.reset()
                        visualizerOn = false
                    } else {
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            ) {
                Text(if (visualizerOn) "Stop visualizer" else "Visualize music")
            }
        }
    }
}

private fun DrawScope.drawVuMeter(
    tickLayouts: List<Triple<Float, Color, TextLayoutResult>>,
    vuLabelLayout: TextLayoutResult,
    topLeft: Offset,
    width: Float,
    height: Float,
    dbVu: Float,
) {
    drawRoundRect(
        color = FRAME_COLOR,
        topLeft = Offset(topLeft.x - 10f, topLeft.y - 10f),
        size = Size(width + 20f, height + 20f),
        cornerRadius = CornerRadius(24f, 24f),
    )
    drawRoundRect(
        color = BODY_COLOR,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(18f, 18f),
    )

    drawText(vuLabelLayout, topLeft = Offset(topLeft.x + width * 0.05f, topLeft.y + height * 0.07f))

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
        drawLine(color = color, start = inner, end = outer, strokeWidth = 3f, cap = StrokeCap.Round)

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
    drawLine(color = NEEDLE_COLOR, start = pivot, end = tip, strokeWidth = 4f, cap = StrokeCap.Round)
    drawCircle(color = NEEDLE_COLOR, radius = height * 0.035f, center = pivot)
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
