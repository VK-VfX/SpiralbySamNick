package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * A scrolling time/frequency history for the waterfall view: a ring buffer of [COLUMN_COUNT]
 * columns, each holding one FFT frame's worth of band levels. [ingest] overwrites the oldest
 * column in place (O(1), no shifting), and [columnAt] hands back columns in logical
 * oldest-to-newest order for rendering.
 */
class SpectrogramEngine(private val bandCount: Int) {
    private val columns = Array(COLUMN_COUNT) { FloatArray(bandCount) }
    private var writeIndex = 0

    var generation by mutableIntStateOf(0)
        private set
    var elapsed by mutableFloatStateOf(0f)
        private set

    fun ingest(bands: FloatArray) {
        val column = columns[writeIndex]
        for (i in 0 until bandCount) {
            column[i] = if (i < bands.size) bands[i] else 0f
        }
        writeIndex = (writeIndex + 1) % COLUMN_COUNT
        generation++
    }

    /** Advances the redraw clock; call once per frame regardless of whether new audio arrived. */
    fun step(dtSeconds: Float) {
        elapsed += dtSeconds.coerceIn(0f, 0.1f)
    }

    fun reset() {
        for (column in columns) column.fill(0f)
        writeIndex = 0
        generation++
    }

    /** Column [index] where 0 is the oldest column and [COLUMN_COUNT] - 1 is the newest. */
    fun columnAt(index: Int): FloatArray = columns[(writeIndex + index) % COLUMN_COUNT]

    companion object {
        const val COLUMN_COUNT = 120
    }
}
