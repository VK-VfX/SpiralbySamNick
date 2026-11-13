package com.nikoezveracamspiral

import androidx.compose.ui.graphics.Color

/**
 * A flat black-and-white palette matching the launcher icon and the "materialistic" brief --
 * true black background (so the viewfinder itself reads as part of the UI, the way every camera
 * app's chrome recedes behind the live image) with white as the one accent color, reserving
 * color only for warning/critical states (low storage, a failed capture) the way a real camera's
 * status LEDs would.
 */
object CameraTheme {
    val BACKGROUND = Color(0xFF000000)
    val SURFACE = Color(0xFF1A1A1A)
    val SURFACE_RAISED = Color(0xFF262626)
    val HAIRLINE = Color(0x33FFFFFF)

    val TEXT_PRIMARY = Color(0xFFFFFFFF)
    val TEXT_SECONDARY = Color(0xFFABABAB)

    val ACCENT = Color(0xFFFFFFFF)
    val WARN = Color(0xFFE0B04A)
    val CRITICAL = Color(0xFFE0453F)
}
