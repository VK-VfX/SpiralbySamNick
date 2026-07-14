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

/**
 * Left-to-right color stops for Neon Cyan Pulse's frequency-reactive coloring: bass reads red,
 * climbing through low-mid/mid warmth, back to the mode's namesake cyan around vocal presence,
 * then out to violet for treble/air -- a bar's position in the row is a stand-in for which
 * frequency band it represents, since [SpectrumEngine]'s bands are laid out log-spaced low-to-high.
 */
internal val FREQUENCY_ZONE_STOPS = listOf(
    0.00f to Color(0xFFFF3B30), // bass
    0.25f to Color(0xFFFF8A00), // low-mid
    0.50f to Color(0xFFCFFF3B), // mid (vocal fundamentals)
    0.75f to Color(0xFF25E6FF), // high-mid / presence ("high pitched voices")
    1.00f to Color(0xFFC742FF), // treble / air
)

/** Piecewise-lerps through [RAINBOW_STOPS] at position [t] (0 = leftmost, 1 = rightmost). */
internal fun rainbowColor(t: Float): Color = colorAtStops(RAINBOW_STOPS, t)

/** Piecewise-lerps through [FREQUENCY_ZONE_STOPS] at position [t] (0 = bass, 1 = treble). */
internal fun frequencyZoneColor(t: Float): Color = colorAtStops(FREQUENCY_ZONE_STOPS, t)

/** Piecewise-lerps through a left-to-right list of (position, color) [stops] at position [t]. */
private fun colorAtStops(stops: List<Pair<Float, Color>>, t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    for (i in 0 until stops.size - 1) {
        val (t0, c0) = stops[i]
        val (t1, c1) = stops[i + 1]
        if (clamped <= t1 || i == stops.size - 2) {
            val localT = ((clamped - t0) / (t1 - t0)).coerceIn(0f, 1f)
            return lerpGradientColor(c0, c1, localT)
        }
    }
    return stops.last().second
}

/**
 * Named distinctly from the several file-private `lerpColor` helpers elsewhere (SpectrumScreen,
 * VuMeterScreen) -- Kotlin flags identically-signatured top-level functions in the same package as
 * conflicting overloads even when all but one are private, since visibility only controls
 * accessibility, not whether the compiler treats them as the same overload set.
 */
internal fun lerpGradientColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/**
 * A true closed 360-degree hue wheel (red -> orange -> yellow -> green -> blue -> purple -> back
 * to red), unlike [RAINBOW_STOPS] which is a deliberately non-looping 5-stop gradient tuned for a
 * straight bar row. Returns [steps] + 1 ARGB colors evenly spaced around the hue circle, first and
 * last identical, so a shader built from them (e.g. `android.graphics.SweepGradient`) wraps
 * seamlessly with no visible seam where it meets itself. RGB-interpolating shaders only blend
 * linearly between the colors they're given, so this needs enough intermediate hues (not just
 * red/green/blue) or the "gradient" would cut through muddy off-hues instead of a clean rainbow --
 * each stop is computed via HSV at full saturation/value, not picked by hand.
 */
internal fun fullHueSweepColors(steps: Int = 12): IntArray = IntArray(steps + 1) { i ->
    android.graphics.Color.HSVToColor(floatArrayOf(i * 360f / steps, 1f, 1f))
}

/** Evenly-spaced [0, 1] positions matching [fullHueSweepColors]'s stop count. */
internal fun fullHueSweepPositions(steps: Int = 12): FloatArray = FloatArray(steps + 1) { i -> i.toFloat() / steps }
