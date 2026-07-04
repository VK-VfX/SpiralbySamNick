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
import kotlin.math.sin
import kotlinx.coroutines.isActive

/** dB-VU values that get a tick and a label on the scale, matching a classic analog VU face. */
private val TICK_VALUES = listOf(-20f, -10f, -7f, -5f, -3f, -2f, -1f, 0f, 1f, 2f, 3f)
private const val LEAN_MIN_DEG = -50f
private const val LEAN_MAX_DEG = 50f

private val FRAME_COLOR = Color(0xFF17151A)
private val BODY_COLOR = Color(0xFFE8DAB0)
private val NEEDLE_COLOR = Color(0xFF17151A)
private val REDLINE_COLOR = Color(0xFFB6342F)

@Composable
fun VuMeterScreen() {
    val leftMeter = remember { VuMeterEngine() }
    val rightMeter = remember { VuMeterEngine() }
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

    LaunchedEffect(leftMeter, rightMeter) {
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                val snapshot = AudioAnalyzer.snapshots.value
                leftMeter.step(dt, snapshot.rawLeft)
                rightMeter.step(dt, snapshot.rawRight)
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val tickLayouts = remember(textMeasurer) {
        TICK_VALUES.map { value ->
            val label = if (value == 0f) "0" else if (value > 0f) "+${value.toInt()}" else value.toInt().toString()
            val color = if (value > 0f) REDLINE_COLOR else FRAME_COLOR
            Triple(value, color, textMeasurer.measure(label, style = TextStyle(fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)))
        }
    }
    val vuLabelLayout = remember(textMeasurer) {
        textMeasurer.measure("VU", style = TextStyle(fontSize = 22.sp, color = FRAME_COLOR, fontWeight = FontWeight.Bold))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color(0xFF0B0B0E))

            val meterWidth = size.width * 0.42f
            val meterHeight = meterWidth * 0.62f
            val gap = size.width * 0.03f
            val totalWidth = meterWidth * 2f + gap
            val startX = (size.width - totalWidth) / 2f
            val top = size.height / 2f - meterHeight / 2f

            drawVuMeter(tickLayouts, vuLabelLayout, Offset(startX, top), meterWidth, meterHeight, leftMeter.dbVu)
            drawVuMeter(tickLayouts, vuLabelLayout, Offset(startX + meterWidth + gap, top), meterWidth, meterHeight, rightMeter.dbVu)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Button(
                onClick = {
                    if (visualizerOn) {
                        AudioCaptureService.stop(context)
                        leftMeter.reset()
                        rightMeter.reset()
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
        topLeft = Offset(topLeft.x - 8f, topLeft.y - 8f),
        size = Size(width + 16f, height + 16f),
        cornerRadius = CornerRadius(20f, 20f),
    )
    drawRoundRect(
        color = BODY_COLOR,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(16f, 16f),
    )

    drawText(vuLabelLayout, topLeft = Offset(topLeft.x + width * 0.06f, topLeft.y + height * 0.08f))

    val pivot = Offset(topLeft.x + width / 2f, topLeft.y + height * 1.05f)
    val tickOuterRadius = height * 0.95f
    val tickInnerRadius = height * 0.82f
    val labelRadius = height * 0.64f
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

/** Maps a dB-VU value on the [-20, +3] scale to a lean angle in degrees from vertical. */
private fun leanForDbVu(dbVu: Float): Float {
    val t = ((dbVu - VuMeterEngine.SCALE_MIN_DB_VU) / (VuMeterEngine.SCALE_MAX_DB_VU - VuMeterEngine.SCALE_MIN_DB_VU)).coerceIn(0f, 1f)
    return LEAN_MIN_DEG + t * (LEAN_MAX_DEG - LEAN_MIN_DEG)
}
