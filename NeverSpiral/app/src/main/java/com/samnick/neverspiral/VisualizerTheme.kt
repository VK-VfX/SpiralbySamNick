package com.samnick.neverspiral

import androidx.compose.ui.graphics.Color

/**
 * Shared palette for the "modern dark mastering suite" look: flat near-black panels, thin
 * hairline dividers, and a cool desaturated accent instead of warm vintage-hardware tones --
 * reserving red strictly for clip/overload warnings, the way a studio meter would.
 */
object VisualizerTheme {
    val BACKGROUND = Color(0xFF0A0A0D)
    val PANEL = Color(0xFF15161B)
    val PANEL_RAISED = Color(0xFF1C1E24)
    val HAIRLINE = Color(0xFF2E3038)

    val TEXT_PRIMARY = Color(0xFFE7E9EE)
    val TEXT_SECONDARY = Color(0xFF8B8F9C)

    val ACCENT = Color(0xFF5AC8E0)
    val ACCENT_DIM = Color(0xFF2E5E68)
    val WARN = Color(0xFFE0B04A)
    val CRITICAL = Color(0xFFE0453F)
}
