package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs

/** How many display points the raw waveform buffer is decimated to each frame -- fixed regardless
 * of how many raw samples [AudioCaptureService] happened to hand back in that particular buffer
 * (buffer size can vary with however much the system had ready to read), so the trace's visual
 * density stays constant rather than getting denser or sparser as buffer sizes fluctuate. */
private const val POINT_COUNT = 160

/** Degrees per second the rainbow hue sweeping along the line rotates -- slow and continuous
 * (delta-time based, like Kaleidoscope Bloom's rotation) so the color genuinely *flows* along the
 * trace like molten lava rather than sitting in fixed frequency-mapped zones the way every other
 * mode's color does. */
private const val HUE_ROTATION_DEGREES_PER_SECOND = 24f

/** A quiet trough still glows dimly rather than going fully dark -- real lava never looks black --
 * and brightness ramps up from there toward a hot white-yellow peak as that point's own amplitude
 * rises, so louder transients visibly glow hotter than quiet ones instead of the whole line being
 * a flat, uniformly-lit rainbow. */
private const val LAVA_BASE_BRIGHTNESS = 0.55f
private const val LAVA_SATURATION = 0.9f

private const val STROKE_WIDTH_FRACTION = 0.009f
private const val MAX_AMPLITUDE_FRACTION_OF_HEIGHT = 1f

private const val GLOW_RADIUS_FRACTION = 0.028f
private const val GLOW_ALPHA = 165

/**
 * Replaces three modes at once -- Radial Spectrum Burst, Equalizer Constellation, and Graphic EQ --
 * to make room for a genuinely different kind of visual. Where every other mode in this app is a
 * frequency-domain treatment of [SpectrumEngine]'s FFT bands, this one is time-domain: a single jagged
 * line tracing [AudioSnapshot.waveform], the raw captured PCM samples -- the literal oscilloscope
 * "audio waveform" look, not a stylized reinterpretation of band levels. That raw data has been
 * captured and published by [AudioCaptureService] since the original Oscilloscope/Waveform Ribbon
 * modes existed, but nothing has read it since those were removed; this is the first live consumer
 * again.
 *
 * Each frame decimates the freshest waveform buffer to [POINT_COUNT] points by taking the
 * signed sample of largest magnitude within each bucket (not an average, which would smear out
 * exactly the sharp transients a waveform trace exists to show), then connects them with straight
 * segments -- no smoothing between frames, since each frame's buffer is genuinely different audio
 * content, not a continuous quantity like a band level that benefits from easing toward a new
 * target.
 *
 * Color is a continuously time-rotating full rainbow hue sweep along the line's length
 * ([HUE_ROTATION_DEGREES_PER_SECOND]), with each point's brightness independently boosted by its
 * own amplitude -- the "lava" part: colors flow and drift on their own like molten rock instead of
 * being pinned to fixed frequency zones the way [frequencyZoneColor] drives every other mode, and
 * louder moments glow hotter rather than the whole trace staying one flat brightness.
 *
 * [BarSpectrumSettings.scale] ("Sensitivity") is the amplitude gain applied before the trace is
 * drawn, [strokeWeight] ("Line Thickness") the stroke width, and [height] ("Max Amplitude") caps
 * how much of the canvas's vertical span the trace is allowed to swing into.
 */
@Composable
fun LavaWaveformScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val lineHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val hueRotationHolder = remember { floatArrayOf(0f) }
    val pointsHolder = remember { FloatArray(POINT_COUNT) }

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

        val raw = AudioAnalyzer.snapshots.value.waveform
        val n = raw.size
        val points = pointsHolder
        if (n == 0) {
            points.fill(0f)
        } else if (n <= POINT_COUNT) {
            for (i in points.indices) points[i] = raw[(i.toLong() * n / POINT_COUNT).toInt().coerceIn(0, n - 1)]
        } else {
            val bucket = n / POINT_COUNT
            for (i in points.indices) {
                val start = i * bucket
                val end = if (i == POINT_COUNT - 1) n else start + bucket
                var best = 0f
                for (j in start until end) {
                    val v = raw[j]
                    if (abs(v) > abs(best)) best = v
                }
                points[i] = best
            }
        }

        val minDim = size.minDimension
        val centerY = size.height / 2f
        val halfHeightPx = size.height * settings.height / BarSpectrumSettings.HEIGHT_MAX * MAX_AMPLITUDE_FRACTION_OF_HEIGHT * 0.5f
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

        var prevX = 0f
        var prevY = centerY
        for (i in 0 until POINT_COUNT) {
            val amplitude = (points[i] * settings.scale).coerceIn(-1f, 1f)
            val x = i * stepX
            val y = centerY - amplitude * halfHeightPx

            if (i > 0) {
                val hue = (i.toFloat() / (POINT_COUNT - 1) * 360f + hueRotationHolder[0]) % 360f
                val brightness = (LAVA_BASE_BRIGHTNESS + abs(amplitude) * (1f - LAVA_BASE_BRIGHTNESS)).coerceIn(0f, 1f)
                linePaint.color = android.graphics.Color.HSVToColor(floatArrayOf(hue, LAVA_SATURATION, brightness))
                lineCanvas.drawLine(prevX, prevY, x, y, linePaint)
            }
            prevX = x
            prevY = y
        }

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
