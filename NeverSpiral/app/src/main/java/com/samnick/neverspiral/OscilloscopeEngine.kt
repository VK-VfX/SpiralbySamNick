package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Builds a smoothly scrolling waveform envelope for a minimalist bar-style oscilloscope (the
 * "podcast waveform" look, mirrored symmetrically around the centerline) rather than a raw
 * electrical scope trace. Each of [COLUMN_COUNT] bars tracks the peak absolute amplitude seen in
 * its slice of a scrolling window, and completed bars shift left as new ones fill in on the
 * right.
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [columnPeak] itself is a plain, non-observable array (mutated in place to avoid
 * allocating a new array every frame).
 */
class OscilloscopeEngine {
    val columnPeak = FloatArray(COLUMN_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    private var partialPeak = 0f
    private var partialCount = 0

    /** Folds newly captured raw mono PCM (linear, -1..1) into the scrolling bar history. */
    fun ingest(samples: FloatArray) {
        for (s in samples) {
            val magnitude = abs(s)
            if (magnitude > partialPeak) partialPeak = magnitude
            partialCount++
            if (partialCount >= SAMPLES_PER_COLUMN) {
                pushColumn(partialPeak)
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
        partialPeak = 0f
        partialCount = 0
    }

    private fun pushColumn(peak: Float) {
        columnPeak.copyInto(columnPeak, destinationOffset = 0, startIndex = 1, endIndex = COLUMN_COUNT)
        columnPeak[COLUMN_COUNT - 1] = peak.coerceIn(0f, 1f)
    }

    companion object {
        const val COLUMN_COUNT = 56
        private const val SAMPLE_RATE = 44100
        private const val WINDOW_SECONDS = 2.4f
        private val SAMPLES_PER_COLUMN = (SAMPLE_RATE * WINDOW_SECONDS / COLUMN_COUNT).toInt()
    }
}
