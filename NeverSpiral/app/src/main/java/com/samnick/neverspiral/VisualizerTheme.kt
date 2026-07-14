package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Shared palette for the "modern dark mastering suite" look: flat near-black panels, thin
 * hairline dividers, and a cool desaturated accent instead of warm vintage-hardware tones --
 * reserving red strictly for clip/overload warnings, the way a studio meter would.
 *
 * [ACCENT] is mutable Compose state, not a fixed constant: [AppearanceSettings] lets a custom
 * color override it, and since every mode already reads [ACCENT] (chips, VU needle highlights,
 * Spectrum's Cool scheme, Graphic EQ's lit segments below the warning zone, and more), changing
 * this one value cascades a custom look across the whole app for free. [ACCENT_DIM] is derived
 * from it rather than an independent color, so it stays coherent with whatever [ACCENT] is set to.
 */
object VisualizerTheme {
    val BACKGROUND = Color(0xFF0A0A0D)
    val PANEL = Color(0xFF15161B)
    val PANEL_RAISED = Color(0xFF1C1E24)
    val HAIRLINE = Color(0xFF2E3038)

    val TEXT_PRIMARY = Color(0xFFE7E9EE)
    val TEXT_SECONDARY = Color(0xFF8B8F9C)

    var ACCENT: Color by mutableStateOf(Color(0xFF5AC8E0))
    val ACCENT_DIM: Color
        get() = Color(red = ACCENT.red * 0.52f, green = ACCENT.green * 0.52f, blue = ACCENT.blue * 0.52f, alpha = 1f)
    val WARN = Color(0xFFE0B04A)
    val CRITICAL = Color(0xFFE0453F)

    /** The full-screen backdrop every visualizer mode's Canvas draws first, before its own
     * content -- mutable and user-customizable via [AppearanceSettings], the same "one value
     * cascades everywhere" pattern as [ACCENT]. Defaults to pure black, matching what most modes
     * already hardcoded before this became a setting. */
    var CANVAS_BACKGROUND: Color by mutableStateOf(Color.Black)
}
