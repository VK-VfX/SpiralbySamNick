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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val BAND_HEIGHT_FRACTION = 0.62f

/** Each diamond's width as a fraction of its column pitch -- the rest of the pitch is empty gap. */
private const val DIAMOND_WIDTH_FRACTION = 0.46f

/** A column's normalized amplitude must clear this before it draws a diamond at all. */
private const val VISIBILITY_THRESHOLD = 0.22f

/** Shapes the height of columns that do clear the threshold -- taller accents read a bit taller still. */
private const val CONTRAST_GAMMA = 1.3f

private const val BASELINE_ALPHA = 70

private val BACKGROUND = VisualizerTheme.BACKGROUND
private val COMPLETED_COLOR = Color(0xFFE3B341)
private val UPCOMING_COLOR = Color(0xFFE8DAB0)
private val PLAYHEAD_COLOR = Color(0xFFF6EDD4)

/**
 * A discrete, static audio-progress-bar waveform -- not a continuous reactive line. Each of
 * [WaveformEngine.COLUMN_COUNT] bins is its own isolated diamond, only drawn when its amplitude
 * clears [VISIBILITY_THRESHOLD], pinned to a flat horizontal baseline with visible gaps between
 * shapes -- like a podcast or voice-message scrubber, never a smoothed path stitched across every
 * bin. [WaveformEngine] only swaps in a new static map periodically (see its own doc), and a
 * playhead sweeps left to right across whichever map is currently showing, recoloring the diamonds
 * it has passed gold ("completed") and leaving the rest a faint beige ("upcoming"). Diamonds are
 * rendered into a persistent off-screen bitmap that's faded (not cleared) every frame, both for the
 * glow (a blurred duplicate of each diamond drawn first) and to soften the moment a new static map
 * swaps in, instead of it just popping.
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

        val n = engine.columnPeak.size
        val pitch = size.width / n
        val playheadX = engine.playheadProgress * size.width
        val intensity = settings.intensity.coerceAtLeast(WaveformSettings.INTENSITY_MIN)

        val baselinePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = size.minDimension * 0.0025f
        }
        baselinePaint.color = COMPLETED_COLOR.toArgb()
        baselinePaint.alpha = (BASELINE_ALPHA * intensity).toInt()
        trailCanvas.drawLine(0f, centerY, playheadX, centerY, baselinePaint)
        baselinePaint.color = UPCOMING_COLOR.toArgb()
        baselinePaint.alpha = (BASELINE_ALPHA * 0.6f * intensity).toInt()
        trailCanvas.drawLine(playheadX, centerY, size.width, centerY, baselinePaint)

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            maskFilter = BlurMaskFilter(size.minDimension * 0.02f, BlurMaskFilter.Blur.NORMAL)
        }
        val fillPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
        }
        val outlinePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeJoin = AndroidPaint.Join.ROUND
            strokeWidth = size.minDimension * 0.004f * settings.strokeWeight
        }

        for (i in 0 until n) {
            val amplitude = engine.columnPeak[i].coerceIn(0f, 1f)
            if (amplitude < VISIBILITY_THRESHOLD) continue

            val x = (i + 0.5f) * pitch
            val half = (amplitude.pow(CONTRAST_GAMMA) * settings.scale * halfBand).coerceAtMost(maxHalf)
            val halfWidth = pitch * DIAMOND_WIDTH_FRACTION / 2f

            // An isolated diamond: left tip on the baseline, up to the peak, right tip on the
            // baseline, down to the mirrored peak, closed -- never connected to its neighbors.
            val diamond = AndroidPath().apply {
                moveTo(x - halfWidth, centerY)
                lineTo(x, centerY - half)
                lineTo(x + halfWidth, centerY)
                lineTo(x, centerY + half)
                close()
            }

            val completed = x <= playheadX
            val color = if (completed) COMPLETED_COLOR else UPCOMING_COLOR
            val shapeAlpha = if (completed) 255 else 130

            glowPaint.color = color.toArgb()
            glowPaint.alpha = (shapeAlpha * 0.45f * intensity).toInt()
            trailCanvas.drawPath(diamond, glowPaint)

            fillPaint.color = color.toArgb()
            fillPaint.alpha = (shapeAlpha * intensity).toInt()
            trailCanvas.drawPath(diamond, fillPaint)

            outlinePaint.color = PLAYHEAD_COLOR.toArgb()
            outlinePaint.alpha = ((if (completed) 200 else 90) * intensity).toInt()
            trailCanvas.drawPath(diamond, outlinePaint)
        }

        val starOuter = size.minDimension * 0.028f
        val starInner = starOuter * 0.45f
        val star = starPath(playheadX, centerY, starOuter, starInner)
        val starPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
            color = PLAYHEAD_COLOR.toArgb()
        }
        starPaint.alpha = (160 * intensity).toInt()
        starPaint.maskFilter = BlurMaskFilter(size.minDimension * 0.012f, BlurMaskFilter.Blur.NORMAL)
        trailCanvas.drawPath(star, starPaint)
        starPaint.alpha = (255 * intensity).toInt()
        starPaint.maskFilter = null
        trailCanvas.drawPath(star, starPaint)

        drawRect(color = BACKGROUND)
        drawImage(trail.asImageBitmap())
    }
}

/** Builds a filled 5-point star [AndroidPath] centered at ([cx], [cy]) -- the playhead marker. */
private fun starPath(cx: Float, cy: Float, outerRadius: Float, innerRadius: Float): AndroidPath {
    val path = AndroidPath()
    val points = 5
    val step = PI.toFloat() / points
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val angle = -PI.toFloat() / 2f + i * step
        val x = cx + radius * cos(angle)
        val y = cy + radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
