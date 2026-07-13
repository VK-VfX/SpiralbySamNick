package com.samnick.neverspiral

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class GradientColorsTest {

    @Test
    fun `frequencyZoneColor at 0 is exactly the bass stop`() {
        assertColorEquals(FREQUENCY_ZONE_STOPS.first().second, frequencyZoneColor(0f))
    }

    @Test
    fun `frequencyZoneColor at 1 is exactly the treble stop`() {
        assertColorEquals(FREQUENCY_ZONE_STOPS.last().second, frequencyZoneColor(1f))
    }

    @Test
    fun `frequencyZoneColor at an interior stop position matches that stop exactly`() {
        val (t, color) = FREQUENCY_ZONE_STOPS[2]
        assertColorEquals(color, frequencyZoneColor(t))
    }

    @Test
    fun `frequencyZoneColor interpolates between neighboring stops, not just snaps to one`() {
        val (t0, c0) = FREQUENCY_ZONE_STOPS[0]
        val (t1, c1) = FREQUENCY_ZONE_STOPS[1]
        val midway = frequencyZoneColor((t0 + t1) / 2f)
        assertColorEquals(lerpGradientColor(c0, c1, 0.5f), midway)
    }

    @Test
    fun `frequencyZoneColor clamps out-of-range input instead of throwing`() {
        assertColorEquals(FREQUENCY_ZONE_STOPS.first().second, frequencyZoneColor(-5f))
        assertColorEquals(FREQUENCY_ZONE_STOPS.last().second, frequencyZoneColor(5f))
    }

    @Test
    fun `lerpGradientColor at t=0 and t=1 returns the endpoints`() {
        val a = Color(0xFFFF0000)
        val b = Color(0xFF0000FF)
        assertColorEquals(a, lerpGradientColor(a, b, 0f))
        assertColorEquals(b, lerpGradientColor(a, b, 1f))
    }

    private fun assertColorEquals(expected: Color, actual: Color, tolerance: Float = 0.001f) {
        assertEquals(expected.red, actual.red, tolerance)
        assertEquals(expected.green, actual.green, tolerance)
        assertEquals(expected.blue, actual.blue, tolerance)
    }
}
