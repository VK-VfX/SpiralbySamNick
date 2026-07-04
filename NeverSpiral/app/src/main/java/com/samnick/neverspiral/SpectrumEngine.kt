package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp

/**
 * Smooths raw per-band spectrum levels frame to frame so bars rise quickly on a transient but
 * settle back down smoothly, instead of jittering with every FFT frame -- plus a small peak-hold
 * cap per band (rises instantly, falls slowly) like a classic hardware spectrum analyzer.
 *
 * [bands] and [peaks] are plain arrays mutated in place (not Compose state) to avoid allocating a
 * new array every frame; [elapsed] is the only actual Compose-observable value, read once per
 * frame purely so the UI knows to redraw even though the arrays themselves aren't observable.
 */
class SpectrumEngine(bandCount: Int) {
    val bands = FloatArray(bandCount)
    val peaks = FloatArray(bandCount)

    var elapsed by mutableFloatStateOf(0f)
        private set

    fun step(dtSeconds: Float, target: FloatArray) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        elapsed += dt

        val riseAlpha = 1f - exp(-dt / RISE_TAU_SECONDS)
        val fallAlpha = 1f - exp(-dt / FALL_TAU_SECONDS)

        for (i in bands.indices) {
            val t = if (i < target.size) target[i] else 0f
            val alpha = if (t > bands[i]) riseAlpha else fallAlpha
            bands[i] += (t - bands[i]) * alpha

            if (bands[i] >= peaks[i]) {
                peaks[i] = bands[i]
            } else {
                peaks[i] = (peaks[i] - dt / PEAK_FALL_SECONDS).coerceAtLeast(bands[i])
            }
        }
    }

    fun reset() {
        bands.fill(0f)
        peaks.fill(0f)
    }

    companion object {
        private const val RISE_TAU_SECONDS = 0.03f
        private const val FALL_TAU_SECONDS = 0.15f
        private const val PEAK_FALL_SECONDS = 1.2f
    }
}
