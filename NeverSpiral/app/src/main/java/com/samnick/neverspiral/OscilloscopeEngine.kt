package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Builds a smoothly scrolling amplitude-vs-time waveform, the way a real audio waveform display
 * works: as new PCM samples arrive they're folded into a min/max "envelope" pair for whichever
 * column they land in, and completed columns shift left as new ones fill in on the right --
 * rather than replacing the entire visible trace with a fresh, essentially random ~40ms snippet
 * every buffer (the previous approach), which aliased into flickering noise instead of a
 * recognizable wave and never looked smooth from frame to frame.
 *
 * [elapsed] exists purely as a Compose-observable value so the Canvas redraws every frame even
 * though [columnMin]/[columnMax] are plain, non-observable arrays (mutated in place to avoid
 * allocating new arrays every frame).
 */
class OscilloscopeEngine {
    val columnMin = FloatArray(COLUMN_COUNT)
    val columnMax = FloatArray(COLUMN_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    private var partialMin = 0f
    private var partialMax = 0f
    private var partialCount = 0

    /** Folds newly captured raw mono PCM (linear, -1..1) into the scrolling column history. */
    fun ingest(samples: FloatArray) {
        for (s in samples) {
            if (partialCount == 0) {
                partialMin = s
                partialMax = s
            } else {
                if (s < partialMin) partialMin = s
                if (s > partialMax) partialMax = s
            }
            partialCount++
            if (partialCount >= SAMPLES_PER_COLUMN) {
                pushColumn(partialMin, partialMax)
                partialCount = 0
            }
        }
    }

    /** Advances the redraw clock; call once per frame regardless of whether new audio arrived. */
    fun step(dtSeconds: Float) {
        elapsed += dtSeconds.coerceIn(0f, 0.1f)
    }

    fun reset() {
        columnMin.fill(0f)
        columnMax.fill(0f)
        partialCount = 0
    }

    private fun pushColumn(min: Float, max: Float) {
        columnMin.copyInto(columnMin, destinationOffset = 0, startIndex = 1, endIndex = COLUMN_COUNT)
        columnMax.copyInto(columnMax, destinationOffset = 0, startIndex = 1, endIndex = COLUMN_COUNT)
        columnMin[COLUMN_COUNT - 1] = min
        columnMax[COLUMN_COUNT - 1] = max
    }

    companion object {
        const val COLUMN_COUNT = 300
        private const val SAMPLE_RATE = 44100
        private const val WINDOW_SECONDS = 2.0f
        private val SAMPLES_PER_COLUMN = (SAMPLE_RATE * WINDOW_SECONDS / COLUMN_COUNT).toInt()
    }
}
