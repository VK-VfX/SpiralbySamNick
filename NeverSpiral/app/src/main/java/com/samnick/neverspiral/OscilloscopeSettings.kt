package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * User-tunable oscilloscope look, exposed through a gear-icon settings panel shown only in
 * oscilloscope mode.
 */
class OscilloscopeSettings {
    var scale by mutableFloatStateOf(1f)
    var strokeWeight by mutableFloatStateOf(1.6f)
    var intensity by mutableFloatStateOf(1f)
    var afterglow by mutableFloatStateOf(0.35f)

    companion object {
        const val SCALE_MIN = 0.4f
        const val SCALE_MAX = 2.5f
        const val STROKE_WEIGHT_MIN = 0.4f
        const val STROKE_WEIGHT_MAX = 3f
        const val INTENSITY_MIN = 0.1f
        const val AFTERGLOW_MAX = 0.92f
    }
}
