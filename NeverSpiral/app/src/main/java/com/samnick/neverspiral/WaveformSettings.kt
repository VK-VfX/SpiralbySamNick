package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** User-tunable waveform look, exposed through a gear-icon settings panel shown only in waveform mode. */
class WaveformSettings(
    initialScale: Float = 1f,
    initialStrokeWeight: Float = 1.6f,
    initialIntensity: Float = 1f,
    initialAfterglow: Float = 0.35f,
) {
    var scale by mutableFloatStateOf(initialScale)
    var strokeWeight by mutableFloatStateOf(initialStrokeWeight)
    var intensity by mutableFloatStateOf(initialIntensity)
    var afterglow by mutableFloatStateOf(initialAfterglow)

    companion object {
        const val SCALE_MIN = 0.4f
        const val SCALE_MAX = 2.5f
        const val STROKE_WEIGHT_MIN = 0.4f
        const val STROKE_WEIGHT_MAX = 3f
        const val INTENSITY_MIN = 0.1f
        const val AFTERGLOW_MAX = 0.92f
    }
}
