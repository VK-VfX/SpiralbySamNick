package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp

/**
 * A streamlined, frequency-resolved envelope: this reuses the same log-spaced FFT bands
 * [SpectrumEngine] draws as bars -- bass on the left through treble on the right, already
 * normalized to a fixed dB floor/ceiling rather than a recent-peak-relative one -- instead of
 * driving every point off the same overall time-domain peak. That's what gives it real dynamic
 * range: different content in different registers genuinely reads as different heights, rather
 * than needing an artificial gate or per-point decay timing to fake variety. Smoothing uses the
 * same fast-rise/slower-fall ballistics as [SpectrumEngine] so it reacts to transients immediately
 * but settles without jitter.
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [bandLevel] itself is a plain, non-observable array (mutated in place to avoid allocating
 * a new array every frame).
 */
class WaveformEngine {
    val bandLevel = FloatArray(SpectrumAnalyzer.BAND_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    /** Eases every band toward [target] (the latest FFT levels) and advances the redraw clock. */
    fun step(dtSeconds: Float, target: FloatArray) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        elapsed += dt

        val riseAlpha = 1f - exp(-dt / RISE_TAU_SECONDS)
        val fallAlpha = 1f - exp(-dt / FALL_TAU_SECONDS)
        for (i in bandLevel.indices) {
            val t = if (i < target.size) target[i] else 0f
            val alpha = if (t > bandLevel[i]) riseAlpha else fallAlpha
            bandLevel[i] += (t - bandLevel[i]) * alpha
        }
    }

    fun reset() {
        bandLevel.fill(0f)
    }

    companion object {
        private const val RISE_TAU_SECONDS = 0.05f
        private const val FALL_TAU_SECONDS = 0.22f
    }
}
