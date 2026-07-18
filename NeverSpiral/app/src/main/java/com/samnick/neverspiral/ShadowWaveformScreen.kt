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
import androidx.compose.ui.graphics.asImageBitmap

private const val POINT_COUNT = 160
private const val STROKE_WIDTH_FRACTION = 0.008f

/** How long ago the shadow trace's snapshot was taken -- long enough to read as a distinct past
 * moment rather than a near-identical smear of the live trace. */
private const val SHADOW_CAPTURE_INTERVAL_SECONDS = 0.15f
private const val SHADOW_BRIGHTNESS_FRACTION = 0.4f
private const val SHADOW_ALPHA = 150

private const val GLOW_RADIUS_FRACTION = 0.026f
private const val GLOW_ALPHA = 150

/**
 * Replaces Horizon Spectrum, whose original build was based on a misread of its reference image:
 * the source is a single jagged time-domain waveform trace with one fainter echo of itself layered
 * behind it, not a frequency-domain bar spectrum at all. This corrects that -- like Lava Waveform
 * and White Waveform, it reads raw PCM directly from [AudioAnalyzer] via the shared
 * [decimateWaveform], but draws two traces: the live one at full brightness, and a second "shadow"
 * trace holding whatever the live trace looked like [SHADOW_CAPTURE_INTERVAL_SECONDS] ago, dimmer
 * and drawn first so the live trace sits on top of it. Both use the same single user-picked hue via
 * [ColorWheelPicker] ([CustomColorSettings]) rather than Lava Waveform's rotating rainbow.
 *
 * [BarSpectrumSettings.scale] ("Sensitivity") is the amplitude gain applied to both traces,
 * [strokeWeight] ("Line Thickness") the stroke width, and [height] ("Max Amplitude") caps how much
 * of the canvas's vertical span either trace can swing into.
 */
@Composable
fun ShadowWaveformScreen(engine: SpectrumEngine, settings: BarSpectrumSettings, colorSettings: CustomColorSettings) {
    val lineHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val captureTimerHolder = remember { floatArrayOf(0f) }
    val pointsHolder = remember { FloatArray(POINT_COUNT) }
    val shadowPointsHolder = remember { FloatArray(POINT_COUNT) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var line = lineHolder[0]
        if (line == null || line.width != widthPx || line.height != heightPx) {
            line = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            lineHolder[0] = line
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        val points = pointsHolder
        decimateWaveform(AudioAnalyzer.snapshots.value.waveform, points)

        captureTimerHolder[0] += dt
        if (captureTimerHolder[0] >= SHADOW_CAPTURE_INTERVAL_SECONDS) {
            captureTimerHolder[0] -= SHADOW_CAPTURE_INTERVAL_SECONDS
            points.copyInto(shadowPointsHolder)
        }

        val minDim = size.minDimension
        val centerY = size.height / 2f
        val halfHeightPx = size.height * settings.height / BarSpectrumSettings.HEIGHT_MAX * 0.5f
        val stepX = size.width / (POINT_COUNT - 1).coerceAtLeast(1)

        val lineCanvas = AndroidCanvas(line)
        lineCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val linePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = minDim * STROKE_WIDTH_FRACTION * settings.strokeWeight
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
        }

        fun drawTrace(tracePoints: FloatArray, brightnessFraction: Float, alpha: Int) {
            linePaint.color = android.graphics.Color.HSVToColor(
                floatArrayOf(colorSettings.hue, colorSettings.saturation, colorSettings.value * brightnessFraction),
            )
            linePaint.alpha = alpha
            var prevX = 0f
            var prevY = centerY
            for (i in 0 until POINT_COUNT) {
                val amplitude = (tracePoints[i] * settings.scale).coerceIn(-1f, 1f)
                val x = i * stepX
                val y = centerY - amplitude * halfHeightPx
                if (i > 0) lineCanvas.drawLine(prevX, prevY, x, y, linePaint)
                prevX = x
                prevY = y
            }
        }

        drawTrace(shadowPointsHolder, SHADOW_BRIGHTNESS_FRACTION, SHADOW_ALPHA)
        drawTrace(points, 1f, 255)

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(line, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(line.asImageBitmap())
    }
}
