package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb

private const val BASELINE_FRACTION = 0.85f
private const val MAX_BAR_HEIGHT_FRACTION = 0.75f
private const val BAR_WIDTH_FRACTION = 0.55f

private val BACKGROUND = VisualizerTheme.BACKGROUND
private val WAVEFORM_COLOR = Color.White

/**
 * A classic single-sided bar waveform: [WaveformEngine.BAR_COUNT] thin white bars, rounded caps,
 * anchored to a bottom baseline and scrolling left as new bars arrive on the right -- the familiar
 * look of a track-overview or podcast-player waveform, not a mirrored continuous line. Each bar is
 * a stroked line with a round cap rather than a filled rectangle, which is what gives it the
 * rounded top (and a small round "dot" instead of vanishing entirely during quiet stretches).
 * Rendered into a persistent off-screen bitmap that's faded (not cleared) every frame, which drives
 * the trailing afterglow as bars scroll by.
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
        // a lower afterglow setting means a more opaque overlay, so bars vanish faster as they scroll.
        val fadeAlpha = ((1f - settings.afterglow).coerceIn(0.06f, 1f) * 255).toInt()
        val fadeColor = (fadeAlpha shl 24) or (BACKGROUND.toArgb() and 0x00FFFFFF)
        trailCanvas.drawColor(fadeColor, PorterDuff.Mode.SRC_OVER)

        val baseline = size.height * BASELINE_FRACTION
        val maxBarHeight = size.height * MAX_BAR_HEIGHT_FRACTION
        val intensity = settings.intensity.coerceAtLeast(WaveformSettings.INTENSITY_MIN)

        val n = engine.barLevel.size
        val pitch = size.width / n
        // strokeWeight's own default (1.6) is normalized back out here, so the slider scales bar
        // width relative to the design baseline instead of relative to 1.0.
        val barWidth = pitch * BAR_WIDTH_FRACTION * (settings.strokeWeight / 1.6f)
        val minBarHeight = barWidth * 0.5f

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
            color = WAVEFORM_COLOR.toArgb()
            alpha = (255 * intensity).toInt()
        }

        for (i in 0 until n) {
            val amplitude = engine.barLevel[i].coerceIn(0f, 1f)
            val height = (amplitude * settings.scale * maxBarHeight)
                .coerceAtLeast(minBarHeight)
                .coerceAtMost(maxBarHeight)
            val cx = (i + 0.5f) * pitch
            trailCanvas.drawLine(cx, baseline, cx, baseline - height, barPaint)
        }

        drawRect(color = BACKGROUND)
        drawImage(trail.asImageBitmap())
    }
}
