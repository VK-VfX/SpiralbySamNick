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
import kotlin.math.abs

private const val POINT_COUNT = 160

private const val HUE_ROTATION_DEGREES_PER_SECOND = 24f
private const val LAVA_BASE_BRIGHTNESS = 0.55f
private const val LAVA_SATURATION = 0.9f

private const val STROKE_WIDTH_FRACTION = 0.007f

private const val GLOW_RADIUS_FRACTION = 0.026f
private const val GLOW_ALPHA = 150

/** How many faded trailing copies sit behind the live trace -- not user-tunable (like
 * Equalizer Constellation's fixed star count before it), since varying it would need a fourth
 * slider [BarSpectrumSettings] doesn't have. */
private const val TRAIL_LAYER_COUNT = 5

/** How often (in seconds) a new trailing copy is captured -- deliberately much coarser than the
 * frame rate, so each echo reads as a distinct afterimage of a specific past moment rather than a
 * smear of nearly-identical adjacent frames. */
private const val CAPTURE_INTERVAL_SECONDS = 0.07f

/** The oldest trailing echo's opacity as a fraction of the live trace's -- the newest echo (just
 * behind the live trace) ramps up from there toward [OLDEST_LAYER_ALPHA_FRACTION]'s complement,
 * so the whole trail reads as a smooth fade rather than a hard cutoff after [TRAIL_LAYER_COUNT]. */
private const val OLDEST_LAYER_ALPHA_FRACTION = 0.10f
private const val NEWEST_TRAIL_LAYER_ALPHA_FRACTION = 0.55f

private class TrailFrame(val points: FloatArray, val hueRotation: Float)

/**
 * A ghost-trail sibling of Lava Waveform: the same decimated raw-PCM trace and the same
 * continuously-rotating lava-rainbow coloring, but instead of only ever drawing the newest
 * buffer, a short rolling history of [TRAIL_LAYER_COUNT] past traces is kept and drawn behind the
 * live one at decreasing opacity -- each captured copy also freezes the hue rotation angle that
 * was active at the moment it was captured, so the trail shows genuine color history flowing past
 * rather than every echo re-painted in whatever hue is currently active.
 *
 * Captures happen on a fixed [CAPTURE_INTERVAL_SECONDS] cadence, not every frame -- capturing
 * every frame at 60-120fps would produce a smear of near-duplicate copies instead of visually
 * distinct echoes of specific past moments.
 *
 * [BarSpectrumSettings.scale] ("Sensitivity") is the amplitude gain, [strokeWeight]
 * ("Line Thickness") the stroke width shared by every layer, and [height] ("Max Amplitude") caps
 * how much of the canvas's vertical span the trace can swing into.
 */
@Composable
fun EchoWaveformScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val lineHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val hueRotationHolder = remember { floatArrayOf(0f) }
    val captureTimerHolder = remember { floatArrayOf(0f) }
    val pointsHolder = remember { FloatArray(POINT_COUNT) }
    val trail = remember { ArrayDeque<TrailFrame>() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed
        hueRotationHolder[0] = (hueRotationHolder[0] + HUE_ROTATION_DEGREES_PER_SECOND * dt) % 360f

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
        if (captureTimerHolder[0] >= CAPTURE_INTERVAL_SECONDS) {
            captureTimerHolder[0] -= CAPTURE_INTERVAL_SECONDS
            trail.addLast(TrailFrame(points.copyOf(), hueRotationHolder[0]))
            while (trail.size > TRAIL_LAYER_COUNT) trail.removeFirst()
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

        fun drawTrace(tracePoints: FloatArray, hueRotation: Float, alphaFraction: Float) {
            var prevX = 0f
            var prevY = centerY
            for (i in 0 until POINT_COUNT) {
                val amplitude = (tracePoints[i] * settings.scale).coerceIn(-1f, 1f)
                val x = i * stepX
                val y = centerY - amplitude * halfHeightPx
                if (i > 0) {
                    val hue = (i.toFloat() / (POINT_COUNT - 1) * 360f + hueRotation) % 360f
                    val brightness = (LAVA_BASE_BRIGHTNESS + abs(amplitude) * (1f - LAVA_BASE_BRIGHTNESS)).coerceIn(0f, 1f)
                    linePaint.color = android.graphics.Color.HSVToColor(floatArrayOf(hue, LAVA_SATURATION, brightness))
                    linePaint.alpha = (255 * alphaFraction).toInt().coerceIn(0, 255)
                    lineCanvas.drawLine(prevX, prevY, x, y, linePaint)
                }
                prevX = x
                prevY = y
            }
        }

        trail.forEachIndexed { index, frame ->
            val alphaFraction = if (trail.size <= 1) {
                NEWEST_TRAIL_LAYER_ALPHA_FRACTION
            } else {
                OLDEST_LAYER_ALPHA_FRACTION +
                    (NEWEST_TRAIL_LAYER_ALPHA_FRACTION - OLDEST_LAYER_ALPHA_FRACTION) * index / (trail.size - 1)
            }
            drawTrace(frame.points, frame.hueRotation, alphaFraction)
        }
        drawTrace(points, hueRotationHolder[0], 1f)

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
