package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT * 2
private const val DOT_RADIUS_FRACTION = 0.0038f
private const val DOT_SPACING_FRACTION = 0.014f
private const val SENSITIVITY_GAMMA = 0.85f

/** A dot's brightness never drops fully to zero even at the row's faded edges, so the shape stays
 * legible rather than disappearing outright. */
private const val EDGE_FADE_MIN_FRACTION = 0.3f

private const val GLOW_RADIUS_FRACTION = 0.024f
private const val GLOW_ALPHA = 150

/**
 * A mirrored FFT bar spectrum rendered as stacked dots rather than continuous bars -- each band's
 * level becomes a column of small filled circles marching outward from the mirror axis instead of
 * one solid stroke, a dot-matrix/LED-cluster texture distinct from every other bar mode's solid
 * fill. Color is a single user-picked hue via [ColorWheelPicker] ([CustomColorSettings]), like
 * [HorizonSpectrumScreen], rather than fixed or frequency-reactive.
 *
 * Brightness also fades toward the row's left and right edges (a raised-cosine window peaking at
 * the center column, floored at [EDGE_FADE_MIN_FRACTION] so the edges dim rather than vanish) --
 * the faded-edges texture from the reference this mode is based on, distinct from every other bar
 * mode's uniform-brightness row.
 */
@Composable
fun DotSpectrumScreen(engine: SpectrumEngine, settings: BarSpectrumSettings, colorSettings: CustomColorSettings) {
    val dotsHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var dots = dotsHolder[0]
        if (dots == null || dots.width != widthPx || dots.height != heightPx) {
            dots = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            dotsHolder[0] = dots
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }
        val dotsCanvas = AndroidCanvas(dots)
        dotsCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val minDim = size.minDimension
        val centerY = size.height / 2f
        val maxHalf = size.height * settings.height
        val pitch = size.width / BAR_COUNT
        val dotRadius = minDim * DOT_RADIUS_FRACTION * settings.strokeWeight
        val dotSpacing = (minDim * DOT_SPACING_FRACTION).coerceAtLeast(dotRadius * 2.2f)

        val dotPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.FILL
        }

        for (i in 0 until BAR_COUNT) {
            val rawLevel = interpolatedBand(engine.bands, i, BAR_COUNT).coerceIn(0f, 1f)
            val level = (rawLevel.pow(SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val half = level * maxHalf
            val cx = (i + 0.5f) * pitch

            val edgeFade = EDGE_FADE_MIN_FRACTION +
                (1f - EDGE_FADE_MIN_FRACTION) * sin(PI * i / (BAR_COUNT - 1).coerceAtLeast(1)).toFloat()
            dotPaint.color = android.graphics.Color.HSVToColor(
                floatArrayOf(colorSettings.hue, colorSettings.saturation, colorSettings.value * edgeFade),
            )

            val dotCount = (half / dotSpacing).toInt()
            for (j in 0..dotCount) {
                val dy = j * dotSpacing
                dotsCanvas.drawCircle(cx, centerY - dy, dotRadius, dotPaint)
                dotsCanvas.drawCircle(cx, centerY + dy, dotRadius, dotPaint)
            }
        }

        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawBitmap(dots, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(dots.asImageBitmap())
    }
}

/** Linearly interpolates [bands] up to [totalBars] positions -- see [HorizonSpectrumScreen]'s copy
 * of the same helper for why this is duplicated rather than shared. */
private fun interpolatedBand(bands: FloatArray, i: Int, totalBars: Int): Float {
    if (bands.isEmpty()) return 0f
    val sourcePos = i.toFloat() * (bands.size - 1) / (totalBars - 1).coerceAtLeast(1)
    val lowIndex = sourcePos.toInt().coerceIn(0, bands.size - 1)
    val highIndex = (lowIndex + 1).coerceAtMost(bands.size - 1)
    val frac = sourcePos - lowIndex
    return bands[lowIndex] + (bands[highIndex] - bands[lowIndex]) * frac
}
