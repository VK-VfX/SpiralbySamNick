package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class GraphicEqColorScheme(val label: String) {
    CLASSIC("Classic"),
    FREQUENCY("Frequency"),
}

/** User-tunable Graphic EQ appearance, exposed through a gear-icon settings panel. */
class GraphicEqSettings(initialColorScheme: GraphicEqColorScheme = GraphicEqColorScheme.CLASSIC) {
    var colorScheme by mutableStateOf(initialColorScheme)
}
