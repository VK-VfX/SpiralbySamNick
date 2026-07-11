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

/** Each diamond's width as a fraction of its column pitch -- wide, with a handful of bins, not a needle. */
private const val DIAMOND_WIDTH_FRACTION = 0.6f

/** Shapes each bin's height a bit further -- taller accents read a little taller still. */
private const val CONTRAST_GAMMA = 1.3f

private val BACKGROUND = VisualizerTheme.BACKGROUND
private val WAVEFORM_COLOR = Color(0xFFE8DAB0)
private val WAVEFORM_HIGHLIGHT = Color(0xFFF6EDD4)

/**
 * A sparse, live-reacting waveform: a handful of wide diamonds -- one per
 * [WaveformEngine.BIN_COUNT] bin, positions fixed across the width -- sitting on a single unbroken
 * baseline that spans from edge to edge. Every diamond is drawn in one uniform warm tone; there is
 * no progress split and no playhead, just [WaveformEngine]'s continuously-updating levels rendered
 * straight through, so growth and decay are smooth every frame rather than popping between
 * snapshots. Diamonds and the baseline are rendered into a persistent off-screen bitmap that's
 * faded (not cleared) every frame, which drives the soft glow (a blurred duplicate drawn first)
 * around each shape.
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

        // A single unbroken baseline from edge to edge, connecting the base of every diamond.
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

        val halfWidth = pitch * DIAMOND_WIDTH_FRACTION / 2f
        for (i in 0 until n) {
            val amplitude = engine.binLevel[i].coerceIn(0f, 1f)
            val x = (i + 0.5f) * pitch
            val half = (amplitude.pow(CONTRAST_GAMMA) * settings.scale * halfBand).coerceAtMost(maxHalf)

            // A wide, isolated diamond: baseline, up to the peak, baseline, mirrored peak, closed.
            val diamond = AndroidPath().apply {
                moveTo(x - halfWidth, centerY)
                lineTo(x, centerY - half)
                lineTo(x + halfWidth, centerY)
                lineTo(x, centerY + half)
                close()
            }

            glowPaint.alpha = (110 * intensity).toInt()
            trailCanvas.drawPath(diamond, glowPaint)

            fillPaint.alpha = (255 * intensity).toInt()
            trailCanvas.drawPath(diamond, fillPaint)

            outlinePaint.alpha = (180 * intensity).toInt()
            trailCanvas.drawPath(diamond, outlinePaint)
        }

        drawRect(color = BACKGROUND)
        drawImage(trail.asImageBitmap())
    }
}
