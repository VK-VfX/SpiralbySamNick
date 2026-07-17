package com.samnick.neverspiral

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * A user-tunable HSV fill color, shared by every mode that exposes a [ColorWheelPicker] (Shadow
 * Waveform, Dot Spectrum, Skyline Spectrum) -- kept separate from [BarSpectrumSettings] rather
 * than folded into it, since only these three modes need a custom color and every other
 * [BarSpectrumSettings] consumer would otherwise carry three unused fields.
 */
class CustomColorSettings(
    initialHue: Float = 190f,
    initialSaturation: Float = 0.75f,
    initialValue: Float = 0.85f,
) {
    var hue by mutableFloatStateOf(initialHue)
    var saturation by mutableFloatStateOf(initialSaturation)
    var value by mutableFloatStateOf(initialValue)

    companion object {
        const val SATURATION_MIN = 0.1f
        const val VALUE_MIN = 0.3f
    }
}

internal fun CustomColorSettings.toColor(): Color = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
