package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.exp

/**
 * Drives a small, fixed set of independent envelope followers -- one per bar -- instead of a
 * scrolling or periodically-committed history. [BIN_COUNT] is small on purpose (a sparse row of
 * bars, matching a reference waveform graphic, not a dense FFT-style spectrum): every bin tracks
 * the *same* live input -- the peak amplitude of whatever's just been captured, normalized against
 * a slowly-decaying recent-peak reference so mastered/loud music doesn't just sit pinned near 1.0
 * -- but each bin has its own release time constant, spread from snappy to lazy. Because they all
 * share one attack but decay at different rates, a single transient ripples across the bars and
 * settles at different heights instead of every bar moving in lockstep, which is what makes a
 * handful of bars driven by one mono signal still read as organic rather than mechanical -- no FFT,
 * no windowing, no gating required.
 *
 * Bin *positions* never change; only [binLevel] does, continuously, every time new audio arrives
 * (an instant attack toward the new target in [ingest]) and every rendered frame (a smooth release
 * toward zero in [step]). There's no periodic "commit" and no scrolling: whatever's on screen is
 * always the live, current reading for that bar, so there's nothing to pop or jump.
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [binLevel] itself is a plain, non-observable array (mutated in place to avoid allocating a
 * new array every frame).
 */
class WaveformEngine {
    val binLevel = FloatArray(BIN_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    private var recentPeak = NORMALIZATION_FLOOR

    /** Folds newly captured raw mono PCM (linear, -1..1) into every bin's live envelope target. */
    fun ingest(samples: FloatArray) {
        if (samples.isEmpty()) return
        var peak = 0f
        for (s in samples) {
            val magnitude = abs(s)
            if (magnitude > peak) peak = magnitude
            recentPeak = if (magnitude > recentPeak) {
                magnitude
            } else {
                recentPeak + (magnitude - recentPeak) * RECENT_PEAK_RELEASE_ALPHA
            }
        }
        val target = (peak / recentPeak.coerceAtLeast(NORMALIZATION_FLOOR)).coerceIn(0f, 1f)
        for (i in 0 until BIN_COUNT) {
            if (target > binLevel[i]) binLevel[i] = target
        }
    }

    /** Advances the redraw clock and lets every bin decay smoothly toward zero at its own rate. */
    fun step(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        elapsed += dt
        for (i in 0 until BIN_COUNT) {
            val releaseAlpha = 1f - exp(-dt / RELEASE_SECONDS[i])
            binLevel[i] -= binLevel[i] * releaseAlpha
        }
    }

    fun reset() {
        binLevel.fill(0f)
        recentPeak = NORMALIZATION_FLOOR
    }

    companion object {
        const val BIN_COUNT = 12
        private const val NORMALIZATION_FLOOR = 0.02f
        private const val RECENT_PEAK_RELEASE_SECONDS = 3.5f
        private const val SAMPLE_RATE = 44100
        private val RECENT_PEAK_RELEASE_ALPHA = 1f - exp(-(1f / SAMPLE_RATE) / RECENT_PEAK_RELEASE_SECONDS)

        /**
         * Each bin's own release time constant in seconds, deliberately out of x-position order so
         * a lull in the audio doesn't decay into a visually mechanical ramp across the row.
         */
        private val RELEASE_SECONDS = floatArrayOf(
            0.55f, 0.18f, 0.85f, 0.30f, 0.65f, 0.22f,
            0.95f, 0.40f, 0.20f, 0.75f, 0.35f, 0.60f,
        )
    }
}
