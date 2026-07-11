package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb

private const val BASELINE_FRACTION = 0.85f
private const val MAX_BAR_HEIGHT_FRACTION = 0.72f
private const val BAR_WIDTH_FRACTION = 0.5f

/** Fall speed (level units/sec) at which the droplet is fully stretched into its most teardrop-like shape. */
private const val STRETCH_REFERENCE_SPEED = 2.2f

private val BACKGROUND = VisualizerTheme.BACKGROUND
private val WAVEFORM_COLOR = Color.White

/**
 * A classic 12-bar graphic equalizer: bars sit at fixed positions across the width and only their
 * height reacts to [WaveformEngine.barLevel] -- nothing scrolls or runs. Each bar carries its own
 * falling "droplet" peak marker ([WaveformEngine.dropletLevel]) that snaps up instantly on a new
 * peak and falls back down under gravity, stretching into a teardrop shape in proportion to its
 * current fall speed and settling back onto the bar's tip once it catches up -- the water-droplet
 * look. Bars and droplets are rendered into a persistent off-screen bitmap that's faded (not
 * cleared) every frame, which drives both the soft glow (a blurred duplicate drawn first) and the
 * trailing afterglow.
 */
@Composable
fun WaveformScreen(engine: WaveformEngine, settings: WaveformSettings) {
    val trailHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual envelope data lives in plain arrays that Compose can't observe on its own.
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

        // Fading (rather than clearing) the previous frame is what creates the afterglow trail.
        val fadeAlpha = ((1f - settings.afterglow).coerceIn(0.06f, 1f) * 255).toInt()
        val fadeColor = (fadeAlpha shl 24) or (BACKGROUND.toArgb() and 0x00FFFFFF)
        trailCanvas.drawColor(fadeColor, PorterDuff.Mode.SRC_OVER)

        val baseline = size.height * BASELINE_FRACTION
        val maxBarHeight = size.height * MAX_BAR_HEIGHT_FRACTION
        val intensity = settings.intensity.coerceAtLeast(WaveformSettings.INTENSITY_MIN)

        val n = engine.barLevel.size
        val pitch = size.width / n
        // strokeWeight's own default (1.6) is normalized back out here, so the slider scales bar
        // (and droplet) width relative to the design baseline instead of relative to 1.0.
        val barWidth = pitch * BAR_WIDTH_FRACTION * (settings.strokeWeight / 1.6f)

        val barGlowFilter = BlurMaskFilter(size.minDimension * 0.02f, BlurMaskFilter.Blur.NORMAL)
        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
            color = WAVEFORM_COLOR.toArgb()
        }
        val dropletPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            color = WAVEFORM_COLOR.toArgb()
        }

        val dropletRadius = barWidth * 0.55f
        for (i in 0 until n) {
            val cx = (i + 0.5f) * pitch
            val barHeight = (engine.barLevel[i].coerceIn(0f, 1f) * settings.scale * maxBarHeight)
                .coerceAtMost(maxBarHeight)
            val barTopY = baseline - barHeight
            val dropletHeight = (engine.dropletLevel[i].coerceIn(0f, 1f) * settings.scale * maxBarHeight)
                .coerceAtMost(maxBarHeight)
            val dropletY = baseline - dropletHeight

            barPaint.maskFilter = barGlowFilter
            barPaint.alpha = (90 * intensity).toInt()
            trailCanvas.drawLine(cx, baseline, cx, barTopY, barPaint)

            barPaint.maskFilter = null
            barPaint.alpha = (255 * intensity).toInt()
            trailCanvas.drawLine(cx, baseline, cx, barTopY, barPaint)

            // Stretch the droplet into a teardrop in proportion to its current fall speed, and
            // settle it back to a plain circle once it's at rest on the bar's tip.
            val fallSpeed = engine.dropletFallSpeed[i]
            val stretch = (fallSpeed / STRETCH_REFERENCE_SPEED).coerceIn(0f, 1f)
            val rx = dropletRadius * (1f - stretch * 0.3f)
            val ry = dropletRadius * (1f + stretch * 0.7f)
            val dropletCenterY = dropletY - dropletRadius * 0.6f

            dropletPaint.alpha = (140 * intensity).toInt()
            dropletPaint.maskFilter = BlurMaskFilter(size.minDimension * 0.018f, BlurMaskFilter.Blur.NORMAL)
            trailCanvas.drawOval(
                RectF(cx - rx, dropletCenterY - ry, cx + rx, dropletCenterY + ry),
                dropletPaint,
            )
            dropletPaint.alpha = (255 * intensity).toInt()
            dropletPaint.maskFilter = null
            trailCanvas.drawOval(
                RectF(cx - rx, dropletCenterY - ry, cx + rx, dropletCenterY + ry),
                dropletPaint,
            )
        }

        drawRect(color = BACKGROUND)
        drawImage(trail.asImageBitmap())
    }
}
