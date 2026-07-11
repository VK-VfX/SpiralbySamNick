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
 * Each committed column is also normalized against [recentPeak], a slowly-decaying reference level
 * (instant attack, several-second release -- the same alpha-blend shape used by [GoniometerEngine]'s
 * correlation and [LoudnessEngine]'s smoothing), then pushed through a noise gate: anything below
 * [GATE_THRESHOLD] of that recent peak is dropped to zero rather than merely scaled down, and only
 * what's above it is rescaled back to the full 0..1 range. A real waveform photo's sparse look comes
 * from genuine silence between phrases; continuous music rarely reads as literal near-zero even
 * after normalizing, so this hard gate (an expander, not just a curve) is what forces most columns
 * flat and lets only genuine loud accents spike, instead of a fixed contrast curve alone.
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [columnPeak] itself is a plain, non-observable array (mutated in place to avoid allocating
 * a new array every frame).
 */
class WaveformEngine {
    val columnPeak = FloatArray(COLUMN_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

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
            val normalized = buildingColumns[i] / floor
            val gated = ((normalized - GATE_THRESHOLD) / (1f - GATE_THRESHOLD)).coerceIn(0f, 1f)
            columnPeak[i] = gated
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
        private const val GATE_THRESHOLD = 0.52f
    }
}
