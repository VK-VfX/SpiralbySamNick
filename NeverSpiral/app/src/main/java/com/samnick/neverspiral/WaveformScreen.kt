package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
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

private const val GLOW_RADIUS_FRACTION = 0.02f
private const val GLOW_ALPHA = 130

private val BACKGROUND = VisualizerTheme.BACKGROUND
private val WHITE = Color.White

/**
 * A classic 12-bar graphic equalizer: bars sit at fixed positions across the width and only their
 * height reacts to [WaveformEngine.barLevel] -- nothing scrolls or runs. Each bar carries its own
 * falling "droplet" peak marker ([WaveformEngine.dropletLevel]) that snaps up instantly on a new
 * peak and falls back down under gravity, stretching into a teardrop shape in proportion to its
 * current fall speed and settling back onto the bar's tip once it catches up -- the water-droplet
 * look. [WaveformSettings.colorScheme] picks solid white, a horizontal rainbow gradient across the
 * row, or a per-bar cyan-to-white gradient from base to tip.
 *
 * Bars and droplets are drawn once, solid, into their own bitmap; the glow is a *single* blurred
 * copy of that whole composited layer, not a per-shape blur -- `BlurMaskFilter`'s cost is dominated
 * by per-call overhead, so blurring everything at once is far cheaper than blurring 24 shapes
 * individually while looking effectively identical.
 */
@Composable
fun WaveformScreen(engine: WaveformEngine, settings: WaveformSettings) {
    val barsHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual envelope data lives in plain arrays that Compose can't observe on its own.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var bars = barsHolder[0]
        if (bars == null || bars.width != widthPx || bars.height != heightPx) {
            bars = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            barsHolder[0] = bars
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }
        val barsCanvas = AndroidCanvas(bars)
        barsCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val baseline = size.height * BASELINE_FRACTION
        val maxBarHeight = size.height * MAX_BAR_HEIGHT_FRACTION
        val intensity = settings.intensity.coerceAtLeast(WaveformSettings.INTENSITY_MIN)

        val n = engine.barLevel.size
        val pitch = size.width / n
        // strokeWeight's own default (1.6) is normalized back out here, so the slider scales bar
        // (and droplet) width relative to the design baseline instead of relative to 1.0.
        val barWidth = pitch * BAR_WIDTH_FRACTION * (settings.strokeWeight / 1.6f)

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
            alpha = (255 * intensity).toInt()
        }
        val dropletPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            alpha = (255 * intensity).toInt()
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
            val rainbowT = i.toFloat() / (n - 1).coerceAtLeast(1)

            when (settings.colorScheme) {
                WaveformColorScheme.WHITE -> {
                    barPaint.shader = null
                    barPaint.color = WHITE.toArgb()
                }
                WaveformColorScheme.RAINBOW -> {
                    barPaint.shader = null
                    barPaint.color = rainbowColor(rainbowT).toArgb()
                }
                WaveformColorScheme.NEON_CYAN -> barPaint.shader = LinearGradient(
                    cx, baseline, cx, barTopY,
                    intArrayOf(NEON_CYAN.toArgb(), NEON_WHITE_HOT.toArgb()),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            barsCanvas.drawLine(cx, baseline, cx, barTopY, barPaint)

            // Stretch the droplet into a teardrop in proportion to its current fall speed, and
            // settle it back to a plain circle once it's at rest on the bar's tip.
            val fallSpeed = engine.dropletFallSpeed[i]
            val stretch = (fallSpeed / STRETCH_REFERENCE_SPEED).coerceIn(0f, 1f)
            val rx = dropletRadius * (1f - stretch * 0.3f)
            val ry = dropletRadius * (1f + stretch * 0.7f)
            val dropletCenterY = dropletY - dropletRadius * 0.6f

            dropletPaint.shader = null
            dropletPaint.color = when (settings.colorScheme) {
                WaveformColorScheme.WHITE -> WHITE.toArgb()
                WaveformColorScheme.RAINBOW -> rainbowColor(rainbowT).toArgb()
                WaveformColorScheme.NEON_CYAN -> NEON_WHITE_HOT.toArgb()
            }
            barsCanvas.drawOval(
                RectF(cx - rx, dropletCenterY - ry, cx + rx, dropletCenterY + ry),
                dropletPaint,
            )
        }

        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = (GLOW_ALPHA * intensity).toInt()
            maskFilter = BlurMaskFilter(size.minDimension * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawBitmap(bars, 0f, 0f, glowPaint)

        drawRect(color = BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(bars.asImageBitmap())
    }
}
