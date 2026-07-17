package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap

private const val POINT_COUNT = 160

private const val FILL_ALPHA = 235
private const val OUTLINE_WIDTH_FRACTION = 0.006f

private const val GLOW_RADIUS_FRACTION = 0.026f
private const val GLOW_ALPHA = 130

/**
 * A filled sibling of Lava Waveform, plain white rather than color-driven: the same decimated
 * raw-PCM trace ([decimateWaveform]), but instead of stroking just the line, the region between
 * the trace and the center axis is filled solid -- built as a single closed [AndroidPath] from
 * `(0, centerY)` through every trace point and back to `(width, centerY)`, which Android's default
 * nonzero winding fill rule renders correctly as a series of filled humps above and below center
 * even though the path re-crosses the axis many times, without needing separate top/bottom
 * sub-paths. A thin white outline stroke on top of the fill (the same path, stroked rather than
 * filled) keeps the silhouette's edge crisp instead of just a soft blurred blob.
 *
 * [BarSpectrumSettings.scale] ("Sensitivity") is the amplitude gain applied before the shape is
 * built, [strokeWeight] ("Outline Thickness") the width of the crisp edge stroke, and [height]
 * ("Max Amplitude") caps how much of the canvas's vertical span the shape can swing into.
 */
@Composable
fun WhiteWaveformScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val shapeHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val pointsHolder = remember { FloatArray(POINT_COUNT) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var shape = shapeHolder[0]
        if (shape == null || shape.width != widthPx || shape.height != heightPx) {
            shape = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            shapeHolder[0] = shape
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        val points = pointsHolder
        decimateWaveform(AudioAnalyzer.snapshots.value.waveform, points)

        val minDim = size.minDimension
        val centerY = size.height / 2f
        val halfHeightPx = size.height * settings.height / BarSpectrumSettings.HEIGHT_MAX * 0.5f
        val stepX = size.width / (POINT_COUNT - 1).coerceAtLeast(1)

        val path = AndroidPath()
        path.moveTo(0f, centerY)
        for (i in 0 until POINT_COUNT) {
            val amplitude = (points[i] * settings.scale).coerceIn(-1f, 1f)
            val x = i * stepX
            val y = centerY - amplitude * halfHeightPx
            path.lineTo(x, y)
        }
        path.lineTo(size.width, centerY)
        path.close()

        val shapeCanvas = AndroidCanvas(shape)
        shapeCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val fillPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            color = AndroidColor.WHITE
            alpha = FILL_ALPHA
        }
        shapeCanvas.drawPath(path, fillPaint)
        val outlinePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            color = AndroidColor.WHITE
            strokeWidth = minDim * OUTLINE_WIDTH_FRACTION * settings.strokeWeight
            strokeJoin = AndroidPaint.Join.ROUND
        }
        shapeCanvas.drawPath(path, outlinePaint)

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(shape, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(shape.asImageBitmap())
    }
}
