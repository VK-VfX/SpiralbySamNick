package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * User-tunable look shared by Rainbow Spectrum and Neon Cyan Pulse, exposed through a gear-icon
 * settings panel -- each mode holds its own instance, so their sliders don't affect each other.
 */
class BarSpectrumSettings(
    initialScale: Float = 1f,
    initialStrokeWeight: Float = 1f,
    initialHeight: Float = 0.46f,
) {
    var scale by mutableFloatStateOf(initialScale)
    var strokeWeight by mutableFloatStateOf(initialStrokeWeight)
    var height by mutableFloatStateOf(initialHeight)

    companion object {
        const val SCALE_MIN = 0.4f
        const val SCALE_MAX = 2.5f
        const val STROKE_WEIGHT_MIN = 0.4f
        const val STROKE_WEIGHT_MAX = 2.5f
        const val HEIGHT_MIN = 0.2f
        const val HEIGHT_MAX = 0.5f
    }
}
