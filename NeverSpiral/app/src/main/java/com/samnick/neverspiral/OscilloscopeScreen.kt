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

private const val BAND_HEIGHT_FRACTION = 0.55f
private const val MIN_HALF_HEIGHT_FRACTION = 0.03f
private val BACKGROUND = VisualizerTheme.BACKGROUND
private val GRID_LINE_FRACTIONS = listOf(0.2f, 0.4f, 0.6f, 0.8f)

/**
 * A single continuous white line tracing the waveform envelope -- one flowing "string", not a
 * mirrored top/bottom pair -- over a faint graticule grid, like a real benchtop oscilloscope
 * screen. Quadratic midpoint smoothing between points is what makes it read as a fluid curve
 * instead of a jagged connect-the-dots line. Rendered into a persistent off-screen bitmap that's
 * faded (not cleared) every frame, which is what produces the afterglow trail; the grid is
 * redrawn fresh every frame for the same reason, or it would fade away along with the wave.
 */
@Composable
fun OscilloscopeScreen(engine: OscilloscopeEngine, settings: OscilloscopeSettings) {
    val trailHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual bar data lives in a plain array that Compose can't observe on its own.
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
        // a lower afterglow setting means a more opaque overlay, so the string vanishes faster.
        val fadeAlpha = ((1f - settings.afterglow).coerceIn(0.06f, 1f) * 255).toInt()
        val fadeColor = (fadeAlpha shl 24) or (BACKGROUND.toArgb() and 0x00FFFFFF)
        trailCanvas.drawColor(fadeColor, PorterDuff.Mode.SRC_OVER)

        val gridPaint = AndroidPaint().apply {
            color = VisualizerTheme.HAIRLINE.toArgb()
            alpha = 130
            strokeWidth = 1.5f
        }
        for (frac in GRID_LINE_FRACTIONS) {
            val y = size.height * frac
            trailCanvas.drawLine(0f, y, size.width, y, gridPaint)
            val x = size.width * frac
            trailCanvas.drawLine(x, 0f, x, size.height, gridPaint)
        }

        val centerY = size.height / 2f
        val halfBand = size.height * BAND_HEIGHT_FRACTION / 2f
        val maxHalf = size.height / 2f - 4f
        val minHalf = size.height * MIN_HALF_HEIGHT_FRACTION / 2f

        val n = engine.columnPeak.size
        val pitch = size.width / n

        val tracePoints = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = (i + 0.5f) * pitch
            val half = (engine.columnPeak[i] * settings.scale * halfBand)
                .coerceAtLeast(minHalf)
                .coerceAtMost(maxHalf)
            tracePoints[i * 2] = x
            tracePoints[i * 2 + 1] = centerY - half
        }

        val outline = AndroidPath()
        addSmoothedPoints(outline, tracePoints, reversed = false, startNewPath = true)

        val baseStrokeWidth = size.minDimension * 0.012f
        val paint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = baseStrokeWidth * settings.strokeWeight
            color = Color.White.toArgb()
            alpha = (255 * settings.intensity.coerceAtLeast(OscilloscopeSettings.INTENSITY_MIN)).toInt()
        }
        trailCanvas.drawPath(outline, paint)

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
