package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Holds the latest time-domain waveform window for the oscilloscope view: amplitude on the Y
 * axis, time on the X axis, exactly like a benchtop scope's normal Y-T mode. Drawn raw --
 * smoothing it would blur the waveform's actual shape. [elapsed] exists purely as a
 * Compose-observable value so the Canvas redraws every frame even though [samples] itself is a
 * plain, non-observable array (mutated in place to avoid allocating a new array every frame).
 */
class OscilloscopeEngine {
    val samples = FloatArray(POINT_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    fun step(dtSeconds: Float, waveform: FloatArray) {
        elapsed += dtSeconds.coerceIn(0f, 0.1f)
        val n = minOf(POINT_COUNT, waveform.size)
        for (i in 0 until n) {
            samples[i] = waveform[i]
        }
    }

    fun reset() {
        samples.fill(0f)
    }

    companion object {
        const val POINT_COUNT = 800
    }
}
