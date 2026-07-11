package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.log10

/**
 * A scrolling peak-amplitude history: each of [BAR_COUNT] bars tracks the peak absolute amplitude
 * seen in its slice of a short rolling window, and completed bars shift left as new ones fill in on
 * the right -- so genuinely quiet stretches (intros, breakdowns, breaths between phrases) and
 * genuinely loud stretches both show up as real height differences across the history, the way a
 * track-overview waveform looks.
 *
 * Height isn't a raw linear peak -- it's converted to a dBFS-style level first (20*log10(peak),
 * normalized against a fixed floor), the same technique real level meters and waveform displays
 * use. Mastered/loud music often sits close to 1.0 linear peak for long stretches, so mapping that
 * linearly would read as a nearly solid block; the dB curve is what makes genuinely quieter
 * passages read meaningfully shorter instead of merely "slightly less maxed."
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [barLevel] itself is a plain, non-observable array (mutated in place to avoid allocating a
 * new array every frame).
 */
class WaveformEngine {
    val barLevel = FloatArray(BAR_COUNT)

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
            if (partialCount >= SAMPLES_PER_BAR) {
                pushBar(partialPeak)
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
        barLevel.fill(0f)
        partialPeak = 0f
        partialCount = 0
    }

    private fun pushBar(peak: Float) {
        barLevel.copyInto(barLevel, destinationOffset = 0, startIndex = 1, endIndex = BAR_COUNT)
        val db = if (peak > 0f) 20f * log10(peak.toDouble()).toFloat() else FLOOR_DB
        barLevel[BAR_COUNT - 1] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }

    companion object {
        const val BAR_COUNT = 56
        private const val SAMPLE_RATE = 44100
        private const val WINDOW_SECONDS = 6f
        private val SAMPLES_PER_BAR = (SAMPLE_RATE * WINDOW_SECONDS / BAR_COUNT).toInt()
        private const val FLOOR_DB = -46f
    }
}
