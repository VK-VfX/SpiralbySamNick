package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.exp

/**
 * Builds a *static* amplitude envelope snapshot the way a track-overview waveform looks, not a
 * scrolling scope trace: each of [COLUMN_COUNT] columns tracks the peak absolute amplitude seen in
 * its slice of a fixed window, but instead of shifting older columns left as new ones arrive (which
 * reads as continuous horizontal motion), a full window's worth of columns is accumulated silently
 * off to the side and the whole visible [columnPeak] array is swapped in at once when it's ready --
 * so the shape holds still and only jumps to a new still shape periodically, never scrolls.
 *
 * Each committed column is normalized against [recentPeak], a slowly-decaying reference level
 * (instant attack, several-second release -- the same alpha-blend shape used by [GoniometerEngine]'s
 * correlation and [LoudnessEngine]'s smoothing), so typical loud passages read as modest levels and
 * only genuine accents approach full height. This engine hands over plain 0..1 levels and nothing
 * more -- it's [WaveformScreen] that decides whether a given column's level clears the bar to be
 * drawn as a shape at all, since this is a discrete shape-per-bin display, not a continuous line
 * that needs its data pre-pinched to look sparse.
 *
 * [playheadProgress] is how far into the *next*, still-accumulating window capture has gotten, as a
 * 0..1 fraction -- [WaveformScreen] sweeps a playhead marker across the *currently shown* static
 * shape using this value, so the marker resets to the start the instant a new shape commits.
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [columnPeak] itself is a plain, non-observable array (mutated in place to avoid allocating
 * a new array every frame).
 */
class WaveformEngine {
    val columnPeak = FloatArray(COLUMN_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    val playheadProgress: Float
        get() = (columnIndex.toFloat() / COLUMN_COUNT).coerceIn(0f, 1f)

    private val buildingColumns = FloatArray(COLUMN_COUNT)
    private var columnIndex = 0

    private var partialPeak = 0f
    private var partialCount = 0

    private var recentPeak = NORMALIZATION_FLOOR

    /** Folds newly captured raw mono PCM (linear, -1..1) into the current accumulating window. */
    fun ingest(samples: FloatArray) {
        for (s in samples) {
            val magnitude = abs(s)
            if (magnitude > partialPeak) partialPeak = magnitude

            recentPeak = if (magnitude > recentPeak) {
                magnitude
            } else {
                recentPeak + (magnitude - recentPeak) * RECENT_PEAK_RELEASE_ALPHA
            }

            partialCount++
            if (partialCount >= SAMPLES_PER_COLUMN) {
                accumulateColumn(partialPeak)
                partialPeak = 0f
                partialCount = 0
            }
        }
    }

    /** Advances the redraw clock; call once per frame regardless of whether new audio arrived. */
    fun step(dtSeconds: Float) {
        elapsed += dtSeconds.coerceIn(0f, 0.1f)
    }

    fun reset() {
        columnPeak.fill(0f)
        buildingColumns.fill(0f)
        columnIndex = 0
        partialPeak = 0f
        partialCount = 0
        recentPeak = NORMALIZATION_FLOOR
    }

    private fun accumulateColumn(peak: Float) {
        if (columnIndex >= COLUMN_COUNT) return
        buildingColumns[columnIndex] = peak
        columnIndex++
        if (columnIndex >= COLUMN_COUNT) commitWindow()
    }

    private fun commitWindow() {
        val floor = recentPeak.coerceAtLeast(NORMALIZATION_FLOOR)
        for (i in 0 until COLUMN_COUNT) {
            columnPeak[i] = (buildingColumns[i] / floor).coerceIn(0f, 1f)
        }
        columnIndex = 0
    }

    companion object {
        const val COLUMN_COUNT = 40
        private const val SAMPLE_RATE = 44100
        private const val WINDOW_SECONDS = 2f
        private val SAMPLES_PER_COLUMN = (SAMPLE_RATE * WINDOW_SECONDS / COLUMN_COUNT).toInt()
        private const val RECENT_PEAK_RELEASE_SECONDS = 3.5f
        private val RECENT_PEAK_RELEASE_ALPHA = 1f - exp(-(1f / SAMPLE_RATE) / RECENT_PEAK_RELEASE_SECONDS)
        private const val NORMALIZATION_FLOOR = 0.02f
    }
}
