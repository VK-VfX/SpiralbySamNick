package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A stereo-imaging tool straight out of a mastering suite: plots left/right as a mid/side dot
 * cloud (mono content collapses to a vertical line; anything that drifts sideways is phase
 * difference between channels) and tracks a running phase-correlation coefficient in -1..1 --
 * +1 means perfectly in phase (mono-safe), 0 means uncorrelated (wide stereo), negative means the
 * channels are working against each other and will partially cancel when summed to mono.
 *
 * [latestLeft]/[latestRight] are the most recent raw buffer (not a history -- the screen owns the
 * scrolling dot-cloud trail); [generation] increments once per [ingest] call so the screen can
 * tell a genuinely new buffer arrived apart from the shared frame loop's per-frame redraw.
 */
class GoniometerEngine {
    var latestLeft: FloatArray = FloatArray(0)
        private set
    var latestRight: FloatArray = FloatArray(0)
        private set

    var generation by mutableIntStateOf(0)
        private set
    var correlation by mutableFloatStateOf(0f)
        private set
    var elapsed by mutableFloatStateOf(0f)
        private set

    private var meanLL = 0f
    private var meanRR = 0f
    private var meanLR = 0f

    fun ingest(left: FloatArray, right: FloatArray) {
        latestLeft = left
        latestRight = right
        generation++
        if (left.isEmpty()) return

        var sumLL = 0f
        var sumRR = 0f
        var sumLR = 0f
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            sumLL += l * l
            sumRR += r * r
            sumLR += l * r
        }
        val n = left.size
        val chunkSeconds = n / SAMPLE_RATE
        val alpha = 1f - exp(-chunkSeconds / CORRELATION_TAU_SECONDS)
        meanLL += (sumLL / n - meanLL) * alpha
        meanRR += (sumRR / n - meanRR) * alpha
        meanLR += (sumLR / n - meanLR) * alpha

        val denom = sqrt(meanLL * meanRR)
        correlation = if (denom > CORRELATION_FLOOR) (meanLR / denom).coerceIn(-1f, 1f) else 0f
    }

    /** Advances the redraw clock; call once per frame regardless of whether new audio arrived. */
    fun step(dtSeconds: Float) {
        elapsed += dtSeconds.coerceIn(0f, 0.1f)
    }

    fun reset() {
        latestLeft = FloatArray(0)
        latestRight = FloatArray(0)
        correlation = 0f
        meanLL = 0f
        meanRR = 0f
        meanLR = 0f
    }

    companion object {
        private const val SAMPLE_RATE = 44100f
        private const val CORRELATION_TAU_SECONDS = 0.3f
        private const val CORRELATION_FLOOR = 1e-6f
    }
}
