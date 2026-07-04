package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Holds the latest left/right sample trace for the X/Y oscilloscope view. The trace is drawn raw
 * -- smoothing it would blur the Lissajous shapes an oscilloscope is meant to show. [elapsed]
 * exists purely as a Compose-observable value so the Canvas redraws every frame even though the
 * point arrays themselves are plain, non-observable arrays (mutated in place to avoid allocating
 * a new array every frame).
 */
class OscilloscopeEngine {
    val pointsX = FloatArray(POINT_COUNT)
    val pointsY = FloatArray(POINT_COUNT)

    var elapsed by mutableFloatStateOf(0f)
        private set

    fun step(dtSeconds: Float, x: FloatArray, y: FloatArray) {
        elapsed += dtSeconds.coerceIn(0f, 0.1f)
        val n = minOf(POINT_COUNT, x.size, y.size)
        for (i in 0 until n) {
            pointsX[i] = x[i]
            pointsY[i] = y[i]
        }
    }

    fun reset() {
        pointsX.fill(0f)
        pointsY.fill(0f)
    }

    companion object {
        const val POINT_COUNT = 512
    }
}
