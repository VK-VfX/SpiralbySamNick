package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val WHEEL_HUE_STEPS = 24
private val WHEEL_HUE_COLORS = (0..WHEEL_HUE_STEPS).map { i ->
    Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 360f / WHEEL_HUE_STEPS, 1f, 1f)))
}

private const val PUCK_RADIUS_PX = 9f
private const val WHEEL_SIZE_DP = 168

/**
 * A circular hue/saturation picker (angle = hue, radial distance = saturation) plus a brightness
 * slider below it -- a genuine wheel, unlike [AppearanceSettings]'s three-slider accent/background
 * picker, which was built specifically to avoid needing one. The wheel is drawn as a hue
 * [Brush.sweepGradient] circle with a white-to-transparent [Brush.radialGradient] composited on
 * top via normal alpha blending -- since `result = white * (1-saturation) + hueColor * saturation`
 * is exactly what SRC_OVER-compositing a white circle at alpha `(1-saturation)` over the hue
 * circle produces, that single extra draw call is enough to depict the desaturation-toward-center
 * falloff without computing per-pixel HSV. A live preview swatch shows the exact selected color
 * (computed with true `HSVToColor`, not the wheel's visual approximation) above the wheel.
 */
@Composable
fun ColorWheelPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onColorChange: (hue: Float, saturation: Float, value: Float) -> Unit,
) {
    fun updateFromOffset(offset: Offset, sizePx: IntSize) {
        val center = Offset(sizePx.width / 2f, sizePx.height / 2f)
        val radius = min(sizePx.width, sizePx.height) / 2f
        if (radius <= 0f) return
        val dx = offset.x - center.x
        val dy = offset.y - center.y
        val distance = sqrt(dx * dx + dy * dy)
        val newSaturation = (distance / radius).coerceIn(0f, 1f)
        val angleDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val newHue = (angleDeg + 360f) % 360f
        onColorChange(newHue, newSaturation, value)
    }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))),
        )
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .size(WHEEL_SIZE_DP.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> updateFromOffset(offset, size) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            updateFromOffset(change.position, size)
                            change.consume()
                        }
                    },
            ) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(brush = Brush.sweepGradient(WHEEL_HUE_COLORS, center = center), radius = radius, center = center)
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center = center, radius = radius),
                    radius = radius,
                    center = center,
                )

                val angleRad = Math.toRadians(hue.toDouble())
                val puckDistance = saturation * radius
                val puckCenter = Offset(
                    center.x + (puckDistance * cos(angleRad)).toFloat(),
                    center.y + (puckDistance * sin(angleRad)).toFloat(),
                )
                drawCircle(color = Color.Black, radius = PUCK_RADIUS_PX, center = puckCenter, style = Stroke(width = 3f))
                drawCircle(color = Color.White, radius = PUCK_RADIUS_PX, center = puckCenter, style = Stroke(width = 1.5f))
            }
        }
        Box(modifier = Modifier.padding(top = 10.dp)) {
            SettingSliderRow("Brightness", value, CustomColorSettings.VALUE_MIN..1f) {
                onColorChange(hue, saturation, it)
            }
        }
    }
}
