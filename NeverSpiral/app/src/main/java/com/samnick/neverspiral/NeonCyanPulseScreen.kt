package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/** Denser than Rainbow Spectrum's one-bar-per-band: linearly interpolates between [SpectrumEngine]'s bands. */
private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT * 2

/** Thinner and tighter than Rainbow Spectrum's 0.62 -- a dense LED-wall look. */
private const val BAR_WIDTH_FRACTION = 0.42f

/** Exponent applied to each band's level before it becomes height: <1 lifts quiet bands so the row reads livelier. */
private const val SENSITIVITY_GAMMA = 0.85f

/** Larger radius and alpha than Rainbow Spectrum's glow -- the "stronger glow" nightclub LED-wall feel. */
private const val GLOW_RADIUS_FRACTION = 0.028f
private const val GLOW_ALPHA = 170

private val CYAN = Color(0xFF25E6FF)
private val WHITE_HOT = Color(0xFFFFFFFF)

/**
 * A mirrored FFT bar spectrum, denser and thinner than [RainbowSpectrumScreen]: each bar is its
 * own cyan-to-white gradient running from the center axis out to its tip on both halves, with a
 * stronger glow, on a pure black background. Bar count is doubled past [SpectrumEngine]'s own band
 * count by linearly interpolating between adjacent bands -- a rendering-only choice, not new DSP --
 * for a smoother, denser-looking row than one bar per band would give. Rendered into a bitmap
 * cleared (not faded) every frame, purely so [BlurMaskFilter] has a software canvas to blur against.
 */
@Composable
fun NeonCyanPulseScreen(engine: SpectrumEngine) {
    val trailHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // the actual band data lives in a plain array that Compose can't observe on its own.
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
        trailCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val centerY = size.height / 2f
        val maxHalf = size.height * 0.46f
        val pitch = size.width / BAR_COUNT
        val barWidth = pitch * BAR_WIDTH_FRACTION
        val glowRadius = size.minDimension * GLOW_RADIUS_FRACTION

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
        }

        for (i in 0 until BAR_COUNT) {
            val level = interpolatedBand(engine.bands, i, BAR_COUNT).coerceIn(0f, 1f).pow(SENSITIVITY_GAMMA)
            val half = (level * maxHalf).coerceAtLeast(barWidth * 0.3f)
            val cx = (i + 0.5f) * pitch
            val topY = centerY - half
            val bottomY = centerY + half

            barPaint.shader = LinearGradient(
                cx, topY, cx, bottomY,
                intArrayOf(WHITE_HOT.toArgb(), CYAN.toArgb(), WHITE_HOT.toArgb()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )

            barPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
            barPaint.alpha = GLOW_ALPHA
            trailCanvas.drawLine(cx, topY, cx, bottomY, barPaint)

            barPaint.maskFilter = null
            barPaint.alpha = 255
            trailCanvas.drawLine(cx, topY, cx, bottomY, barPaint)
        }

        drawRect(color = Color.Black)
        drawImage(trail.asImageBitmap())
    }
}

/** Linearly interpolates [bands] up to [totalBars] positions, denser than the source band count. */
private fun interpolatedBand(bands: FloatArray, i: Int, totalBars: Int): Float {
    if (bands.isEmpty()) return 0f
    val sourcePos = i.toFloat() * (bands.size - 1) / (totalBars - 1).coerceAtLeast(1)
    val lowIndex = sourcePos.toInt().coerceIn(0, bands.size - 1)
    val highIndex = (lowIndex + 1).coerceAtMost(bands.size - 1)
    val frac = sourcePos - lowIndex
    return bands[lowIndex] + (bands[highIndex] - bands[lowIndex]) * frac
}
