package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp

/**
 * A classic 12-bar graphic equalizer: bar *positions* never move -- only their heights react live
 * to the same log-spaced FFT bands [SpectrumEngine] draws (grouped down from
 * [SpectrumAnalyzer.BAND_COUNT] to [BAR_COUNT], bass on the left through treble on the right), with
 * fast-rise/slower-fall ballistics so it reacts to transients immediately but settles smoothly
 * instead of jittering.
 *
 * Each bar also carries its own falling "droplet" peak marker in [dropletLevel]: it snaps to a
 * bar's new peak instantly, then falls back down under constant acceleration in [dropletFallSpeed]
 * -- not a fixed linear or exponential rate -- exactly like a water droplet dropping, resting back
 * on top of the bar once it catches up.
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [barLevel]/[dropletLevel] are plain, non-observable arrays (mutated in place to avoid
 * allocating new arrays every frame).
 */
class WaveformEngine {
    val barLevel = FloatArray(BAR_COUNT)
    val dropletLevel = FloatArray(BAR_COUNT)
    val dropletFallSpeed = FloatArray(BAR_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    /** Eases every bar toward the latest FFT [bands] (grouped down to [BAR_COUNT]) and updates droplets. */
    fun step(dtSeconds: Float, bands: FloatArray) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        elapsed += dt

        val riseAlpha = 1f - exp(-dt / RISE_TAU_SECONDS)
        val fallAlpha = 1f - exp(-dt / FALL_TAU_SECONDS)

        for (i in 0 until BAR_COUNT) {
            val target = groupedBand(bands, i)
            val alpha = if (target > barLevel[i]) riseAlpha else fallAlpha
            barLevel[i] += (target - barLevel[i]) * alpha

            if (barLevel[i] >= dropletLevel[i]) {
                dropletLevel[i] = barLevel[i]
                dropletFallSpeed[i] = 0f
            } else {
                dropletFallSpeed[i] += GRAVITY * dt
                dropletLevel[i] = (dropletLevel[i] - dropletFallSpeed[i] * dt).coerceAtLeast(barLevel[i])
                if (dropletLevel[i] <= barLevel[i]) dropletFallSpeed[i] = 0f
            }
        }
    }

    fun reset() {
        barLevel.fill(0f)
        dropletLevel.fill(0f)
        dropletFallSpeed.fill(0f)
    }

    /** Averages [bands] into the [i]th of [BAR_COUNT] evenly-sized contiguous groups. */
    private fun groupedBand(bands: FloatArray, i: Int): Float {
        val sourceCount = bands.size
        if (sourceCount == 0) return 0f
        val start = i * sourceCount / BAR_COUNT
        val end = ((i + 1) * sourceCount / BAR_COUNT).coerceAtLeast(start + 1).coerceAtMost(sourceCount)
        var sum = 0f
        for (k in start until end) sum += bands[k]
        return sum / (end - start)
    }

    companion object {
        const val BAR_COUNT = 12
        private const val RISE_TAU_SECONDS = 0.05f
        private const val FALL_TAU_SECONDS = 0.22f

        /** Level units (0..1 scale) per second^2 -- how fast the droplet accelerates as it falls. */
        private const val GRAVITY = 3.2f
    }
}
