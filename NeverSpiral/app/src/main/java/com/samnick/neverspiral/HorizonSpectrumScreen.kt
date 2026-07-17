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
import kotlin.math.pow

/** Denser even than Neon Cyan Pulse's doubled row -- the "wall of bars" density from the reference
 * look, achieved the same way: linearly interpolating between [SpectrumEngine]'s bands rather than
 * a higher-resolution FFT. */
private const val BAR_COUNT = SpectrumAnalyzer.BAND_COUNT * 3
private const val BAR_WIDTH_FRACTION = 0.55f
private const val SENSITIVITY_GAMMA = 0.85f

/** A quiet bar still reads at this fraction of the picked color's brightness rather than going
 * invisible, so the whole row stays a visible mass even between hits -- only the louder bars pull
 * brightness up toward the full picked color. */
private const val BASE_BRIGHTNESS_FRACTION = 0.42f

private const val GLOW_RADIUS_FRACTION = 0.026f
private const val GLOW_ALPHA = 170

/** The bright horizontal seam running through the middle of the bar mass -- the signature detail
 * of the reference look, drawn once across the full width on top of the bars rather than baked
 * into each bar's own gradient. */
private const val CENTERLINE_THICKNESS_FRACTION = 0.006f
private const val CENTERLINE_ALPHA = 210

/**
 * A dense mirrored FFT bar spectrum -- three times [SpectrumEngine]'s own band count, achieved by
 * linear interpolation like [NeonCyanPulseScreen], for a solid "wall of bars" mass rather than a
 * countable row. Unlike every other bar-spectrum mode, color isn't fixed or frequency-reactive --
 * it's a single user-picked hue via [ColorWheelPicker] ([CustomColorSettings]), with only
 * brightness reacting to each bar's own level ([BASE_BRIGHTNESS_FRACTION] at rest, ramping to the
 * full picked color at full level) rather than the hue itself shifting per band or per hit.
 *
 * A bright horizontal seam is drawn once across the full width at the mirror axis, on top of every
 * bar, into the same bitmap before the single glow blur pass -- the detail that reads as a
 * horizon line cutting through the bar mass in the reference this mode is based on.
 */
@Composable
fun HorizonSpectrumScreen(engine: SpectrumEngine, settings: BarSpectrumSettings, colorSettings: CustomColorSettings) {
    val barsHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
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

        val minDim = size.minDimension
        val centerY = size.height / 2f
        val maxHalf = size.height * settings.height
        val pitch = size.width / BAR_COUNT
        val barWidth = pitch * BAR_WIDTH_FRACTION * settings.strokeWeight

        val barPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeWidth = barWidth
        }

        for (i in 0 until BAR_COUNT) {
            val rawLevel = interpolatedBand(engine.bands, i, BAR_COUNT).coerceIn(0f, 1f)
            val level = (rawLevel.pow(SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
            val half = (level * maxHalf).coerceAtLeast(barWidth * 0.3f)
            val cx = (i + 0.5f) * pitch

            val brightnessFraction = BASE_BRIGHTNESS_FRACTION + level * (1f - BASE_BRIGHTNESS_FRACTION)
            barPaint.color = android.graphics.Color.HSVToColor(
                floatArrayOf(colorSettings.hue, colorSettings.saturation, colorSettings.value * brightnessFraction),
            )
            barsCanvas.drawLine(cx, centerY - half, cx, centerY + half, barPaint)
        }

        val centerlinePaint = AndroidPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            alpha = CENTERLINE_ALPHA
            strokeWidth = minDim * CENTERLINE_THICKNESS_FRACTION
        }
        barsCanvas.drawLine(0f, centerY, size.width, centerY, centerlinePaint)

        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawBitmap(bars, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(bars.asImageBitmap())
    }
}

/** Linearly interpolates [bands] up to [totalBars] positions, denser than the source band count --
 * duplicated per mode that needs it (also in [NeonCyanPulseScreen]) rather than shared, matching
 * this codebase's existing convention of small per-file helpers (e.g. each screen's own `lerpColor`). */
private fun interpolatedBand(bands: FloatArray, i: Int, totalBars: Int): Float {
    if (bands.isEmpty()) return 0f
    val sourcePos = i.toFloat() * (bands.size - 1) / (totalBars - 1).coerceAtLeast(1)
    val lowIndex = sourcePos.toInt().coerceIn(0, bands.size - 1)
    val highIndex = (lowIndex + 1).coerceAtMost(bands.size - 1)
    val frac = sourcePos - lowIndex
    return bands[lowIndex] + (bands[highIndex] - bands[lowIndex]) * frac
}
