package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SpectrumColorScheme(val label: String) {
    COOL("Cool"),
    CLASSIC("Classic"),
    FREQUENCY("Frequency"),
}

/** User-tunable Spectrum look, exposed through a gear-icon settings panel. */
class SpectrumSettings(initialColorScheme: SpectrumColorScheme = SpectrumColorScheme.COOL) {
    var colorScheme by mutableStateOf(initialColorScheme)
}
