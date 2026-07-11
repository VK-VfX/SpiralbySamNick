package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

private const val BAND_HEIGHT_FRACTION = 0.5f
private const val MIN_HALF_HEIGHT_FRACTION = 0.02f

/**
 * Most currently-playing (mastered, loudness-normalized) music sits close to peak amplitude for
 * a large fraction of the time, so mapping the raw envelope linearly to height reads as a nearly
 * solid block with barely any contrast -- not the sparse, spiky look of a real waveform overview.
 * Raising the envelope to this power before mapping it to height compresses everything below
 * peak much harder than the peak itself, so only genuine loud transients read as tall spikes and
 * everything else collapses back toward the centerline, the way the reference image looks.
 */
private const val CONTRAST_GAMMA = 3.2f

private val BACKGROUND = VisualizerTheme.BACKGROUND
private val WAVEFORM_COLOR = Color(0xFFE8DAB0)
private val WAVEFORM_HIGHLIGHT = Color(0xFFF6EDD4)

/**
 * A classic linear waveform: the amplitude envelope mirrored symmetrically top and bottom around
 * a horizontal centerline and filled solid, like a track waveform in an audio editor -- tall
 * spikes for loud transients tapering into small ripples for quiet passages. Quadratic midpoint
 * smoothing between envelope points is what keeps the outline a fluid vector shape instead of a
 * jagged connect-the-dots line. Rendered into a persistent off-screen bitmap that's faded (not
 * cleared) every frame, which is what produces the trailing afterglow.
 */
@Composable
fun WaveformScreen(engine: WaveformEngine, settings: WaveformSettings) {
    val trailHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual envelope data lives in a plain array that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var trail = trailHolder[0]
        if (trail == null || trail.width != widthPx || trail.height != heightPx) {
            trail = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            trailHolder[0] = trail
        }
        val trailCanvas = AndroidCanvas(trail)

        // Fading (rather than clearing) the previous frame is what creates the afterglow trail --
        // a lower afterglow setting means a more opaque overlay, so the shape vanishes faster.
        val fadeAlpha = ((1f - settings.afterglow).coerceIn(0.06f, 1f) * 255).toInt()
        val fadeColor = (fadeAlpha shl 24) or (BACKGROUND.toArgb() and 0x00FFFFFF)
        trailCanvas.drawColor(fadeColor, PorterDuff.Mode.SRC_OVER)

        val centerY = size.height / 2f
        val halfBand = size.height * BAND_HEIGHT_FRACTION / 2f
        val maxHalf = size.height / 2f - 4f
        val minHalf = size.height * MIN_HALF_HEIGHT_FRACTION / 2f

        val n = engine.columnPeak.size
        val pitch = size.width / n

        val topPoints = FloatArray(n * 2)
        val bottomPoints = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = (i + 0.5f) * pitch
            val contrasted = engine.columnPeak[i].coerceIn(0f, 1f).pow(CONTRAST_GAMMA)
            val half = (contrasted * settings.scale * halfBand)
                .coerceAtLeast(minHalf)
                .coerceAtMost(maxHalf)
            topPoints[i * 2] = x
            topPoints[i * 2 + 1] = centerY - half
            bottomPoints[i * 2] = x
            bottomPoints[i * 2 + 1] = centerY + half
        }

        val outline = AndroidPath()
        addSmoothedPoints(outline, topPoints, reversed = false, startNewPath = true)
        addSmoothedPoints(outline, bottomPoints, reversed = true, startNewPath = false)
        outline.close()

        val fillPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            color = WAVEFORM_COLOR.toArgb()
            alpha = (255 * settings.intensity.coerceAtLeast(WaveformSettings.INTENSITY_MIN)).toInt()
        }
        trailCanvas.drawPath(outline, fillPaint)

        val outlinePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = size.minDimension * 0.006f * settings.strokeWeight
            color = WAVEFORM_HIGHLIGHT.toArgb()
            alpha = (180 * settings.intensity.coerceAtLeast(WaveformSettings.INTENSITY_MIN)).toInt()
        }
        trailCanvas.drawPath(outline, outlinePaint)

        drawRect(color = BACKGROUND)
        drawImage(trail.asImageBitmap())
    }
}

/** Appends [points] (packed x,y pairs) to [path] as a quadratic-smoothed curve through their midpoints. */
private fun addSmoothedPoints(path: AndroidPath, points: FloatArray, reversed: Boolean, startNewPath: Boolean) {
    val count = points.size / 2
    if (count == 0) return
    val order = if (reversed) (count - 1 downTo 0) else (0 until count)
    var previousX = 0f
    var previousY = 0f
    var first = true
    for (i in order) {
        val x = points[i * 2]
        val y = points[i * 2 + 1]
        if (first) {
            if (startNewPath) path.moveTo(x, y) else path.lineTo(x, y)
            first = false
        } else {
            val midX = (previousX + x) / 2f
            val midY = (previousY + y) / 2f
            path.quadTo(previousX, previousY, midX, midY)
        }
        previousX = x
        previousY = y
    }
    path.lineTo(previousX, previousY)
}
