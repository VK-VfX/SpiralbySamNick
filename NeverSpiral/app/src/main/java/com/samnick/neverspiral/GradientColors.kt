package com.samnick.neverspiral

import androidx.compose.ui.graphics.Color

/** Left-to-right color stops shared by every "rainbow" color scheme: blue/purple through magenta/pink and orange to yellow. */
internal val RAINBOW_STOPS = listOf(
    0.00f to Color(0xFF3A3BE0),
    0.28f to Color(0xFF9330D9),
    0.55f to Color(0xFFE43A9E),
    0.78f to Color(0xFFFF7A2E),
    1.00f to Color(0xFFFFD400),
)

internal val NEON_CYAN = Color(0xFF25E6FF)
internal val NEON_WHITE_HOT = Color(0xFFFFFFFF)

/** Piecewise-lerps through [RAINBOW_STOPS] at position [t] (0 = leftmost, 1 = rightmost). */
internal fun rainbowColor(t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    for (i in 0 until RAINBOW_STOPS.size - 1) {
        val (t0, c0) = RAINBOW_STOPS[i]
        val (t1, c1) = RAINBOW_STOPS[i + 1]
        if (clamped <= t1 || i == RAINBOW_STOPS.size - 2) {
            val localT = ((clamped - t0) / (t1 - t0)).coerceIn(0f, 1f)
            return lerpColor(c0, c1, localT)
        }
    }
    return RAINBOW_STOPS.last().second
}

internal fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
