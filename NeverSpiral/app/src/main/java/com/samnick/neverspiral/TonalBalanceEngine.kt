package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp

/**
 * A long-averaged spectral tilt: the same log-spaced FFT bands as the Spectrum view, but smoothed
 * with a multi-second time constant instead of a fast-rise/slow-fall one, so it settles into the
 * overall tonal character of what's playing (bass-heavy, bright, scooped mids, ...) rather than
 * reacting to individual transients. Useful for judging a track's -- or a listening system's --
 * overall EQ balance at a glance.
 *
 * [referenceBands] tracks the *same* bands with a much longer time constant (tens of seconds vs.
 * a few seconds), so it settles into the session's overall character -- the [smoothedBands] curve
 * is then compared against that instead of an arbitrary flat line, showing whether what's playing
 * right now is trending brighter/darker/bassier than what's been playing over the last minute or
 * so, a genuinely meaningful reference rather than a decorative one.
 */
class TonalBalanceEngine(bandCount: Int) {
    val smoothedBands = FloatArray(bandCount)
    val referenceBands = FloatArray(bandCount)

    var elapsed by mutableFloatStateOf(0f)
        private set

    fun step(dtSeconds: Float, target: FloatArray) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        elapsed += dt

        val alpha = 1f - exp(-dt / TILT_TAU_SECONDS)
        val referenceAlpha = 1f - exp(-dt / REFERENCE_TAU_SECONDS)
        for (i in smoothedBands.indices) {
            val t = if (i < target.size) target[i] else 0f
            smoothedBands[i] += (t - smoothedBands[i]) * alpha
            referenceBands[i] += (t - referenceBands[i]) * referenceAlpha
        }
    }

    fun reset() {
        smoothedBands.fill(0f)
        referenceBands.fill(0f)
    }

    companion object {
        private const val TILT_TAU_SECONDS = 4f
        private const val REFERENCE_TAU_SECONDS = 45f
    }
}
