package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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

private const val BAND_HEIGHT_FRACTION = 0.62f

/** Each spike's width as a fraction of its column pitch -- wide, with a handful of bins, not a needle. */
private const val SPIKE_WIDTH_FRACTION = 0.6f

/** Shapes each bin's height a bit further -- taller accents read a little taller still. */
private const val CONTRAST_GAMMA = 1.3f

/**
 * How far each side of a spike bulges past the straight line between its tip and its waist, as a
 * multiple of that straight-line midpoint's offset from center -- 1.0 would be a plain straight-
 * sided diamond, so anything above that bows the sides outward into a leaf/petal curve instead.
 */
private const val SPIKE_BULGE = 1.35f

private val BACKGROUND = VisualizerTheme.BACKGROUND
private val WAVEFORM_COLOR = Color(0xFFE8DAB0)
private val WAVEFORM_HIGHLIGHT = Color(0xFFF6EDD4)

/**
 * A sparse, live-reacting waveform: a handful of wide, curved spikes -- one per
 * [WaveformEngine.BIN_COUNT] bin, positions fixed across the width -- sitting on a single unbroken
 * baseline that spans from edge to edge. Every spike is drawn in one uniform warm tone; there is no
 * progress split and no playhead, just [WaveformEngine]'s continuously-updating levels rendered
 * straight through, so growth and decay are smooth every frame rather than popping between
 * snapshots. Spikes and the baseline are rendered into a persistent off-screen bitmap that's faded
 * (not cleared) every frame, which drives the soft glow (a blurred duplicate drawn first) around
 * each shape.
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

        val fadeAlpha = ((1f - settings.afterglow).coerceIn(0.06f, 1f) * 255).toInt()
        val fadeColor = (fadeAlpha shl 24) or (BACKGROUND.toArgb() and 0x00FFFFFF)
        trailCanvas.drawColor(fadeColor, PorterDuff.Mode.SRC_OVER)

        val centerY = size.height / 2f
        val halfBand = size.height * BAND_HEIGHT_FRACTION / 2f
        val maxHalf = size.height / 2f - 4f
        val intensity = settings.intensity.coerceAtLeast(WaveformSettings.INTENSITY_MIN)

        val n = engine.binLevel.size
        val pitch = size.width / n

        // A single unbroken baseline from edge to edge, connecting the waist of every spike.
        val baselinePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = size.minDimension * 0.0025f
            color = WAVEFORM_COLOR.toArgb()
            alpha = (90 * intensity).toInt()
        }
        trailCanvas.drawLine(0f, centerY, size.width, centerY, baselinePaint)

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            color = WAVEFORM_COLOR.toArgb()
            maskFilter = BlurMaskFilter(size.minDimension * 0.025f, BlurMaskFilter.Blur.NORMAL)
        }
        val fillPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            color = WAVEFORM_COLOR.toArgb()
        }
        val outlinePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
            strokeWidth = size.minDimension * 0.004f * settings.strokeWeight
            color = WAVEFORM_HIGHLIGHT.toArgb()
        }

        val halfWidth = pitch * SPIKE_WIDTH_FRACTION / 2f
        for (i in 0 until n) {
            val amplitude = engine.binLevel[i].coerceIn(0f, 1f)
            val x = (i + 0.5f) * pitch
            val half = (amplitude.pow(CONTRAST_GAMMA) * settings.scale * halfBand).coerceAtMost(maxHalf)

            val spike = spikePath(x, centerY, halfWidth, half)

            glowPaint.alpha = (110 * intensity).toInt()
            trailCanvas.drawPath(spike, glowPaint)

            fillPaint.alpha = (255 * intensity).toInt()
            trailCanvas.drawPath(spike, fillPaint)

            outlinePaint.alpha = (180 * intensity).toInt()
            trailCanvas.drawPath(spike, outlinePaint)
        }

        drawRect(color = BACKGROUND)
        drawImage(trail.asImageBitmap())
    }
}

/**
 * Builds a closed, curved spike centered at ([cx], [cy]): a tip [half] above center, a tip [half]
 * below (mirrored), and two waist points [halfWidth] to either side sitting exactly on the
 * baseline -- but the four edges connecting them are quadratic curves bowed outward past their
 * straight-line path, rather than straight lines, which is what turns a plain diamond into the
 * bulging leaf/petal silhouette of the reference art.
 */
private fun spikePath(cx: Float, cy: Float, halfWidth: Float, half: Float): AndroidPath {
    val top = floatArrayOf(cx, cy - half)
    val right = floatArrayOf(cx + halfWidth, cy)
    val bottom = floatArrayOf(cx, cy + half)
    val left = floatArrayOf(cx - halfWidth, cy)

    // The control point for the curve between two corners is that segment's straight-line
    // midpoint, pushed further away from center by SPIKE_BULGE -- which is what bows the edge
    // outward into a convex curve instead of a straight diamond side.
    fun bulgeControl(a: FloatArray, b: FloatArray): FloatArray {
        val midX = (a[0] + b[0]) / 2f
        val midY = (a[1] + b[1]) / 2f
        return floatArrayOf(cx + (midX - cx) * SPIKE_BULGE, cy + (midY - cy) * SPIKE_BULGE)
    }

    val path = AndroidPath()
    path.moveTo(top[0], top[1])
    var c = bulgeControl(top, right)
    path.quadTo(c[0], c[1], right[0], right[1])
    c = bulgeControl(right, bottom)
    path.quadTo(c[0], c[1], bottom[0], bottom[1])
    c = bulgeControl(bottom, left)
    path.quadTo(c[0], c[1], left[0], left[1])
    c = bulgeControl(left, top)
    path.quadTo(c[0], c[1], top[0], top[1])
    path.close()
    return path
}
