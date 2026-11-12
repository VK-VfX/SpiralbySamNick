package com.nikoezveracamspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProFormattingTest {

    @Test
    fun `shutter speeds a full second or slower are labeled in seconds`() {
        assertEquals("2.0\"", shutterSpeedLabel(2_000_000_000L))
        assertEquals("1.0\"", shutterSpeedLabel(1_000_000_000L))
    }

    @Test
    fun `shutter speeds faster than a second are labeled as a fraction`() {
        assertEquals("1/125", shutterSpeedLabel(1_000_000_000L / 125))
        assertEquals("1/60", shutterSpeedLabel(1_000_000_000L / 60))
    }

    @Test
    fun `standard shutter speeds only include stops within the sensor range`() {
        val steps = standardShutterSpeedsNanos(1_000_000_000L / 4000, 1_000_000_000L / 30)
        assertTrue("expected 1/125 within range", steps.contains(1_000_000_000L / 125))
        assertTrue("expected 1/2000 within range", steps.contains(1_000_000_000L / 2000))
        assertTrue("expected 1s excluded as slower than the range", steps.none { it > 1_000_000_000L / 30 })
        assertTrue("expected 1/8000 excluded as faster than the range", steps.none { it < 1_000_000_000L / 4000 })
    }

    @Test
    fun `standard shutter speeds falls back to the minimum when no standard stop overlaps`() {
        val steps = standardShutterSpeedsNanos(123L, 456L)
        assertEquals(listOf(123L), steps)
    }

    @Test
    fun `standard shutter speeds are sorted ascending`() {
        val steps = standardShutterSpeedsNanos(1_000_000_000L / 8000, 4_000_000_000L)
        assertEquals(steps.sorted(), steps)
    }

    @Test
    fun `standard ISO values only include stops within the sensor range`() {
        val values = standardIsoValues(100, 3200)
        assertEquals(listOf(100, 200, 400, 800, 1600, 3200), values)
    }

    @Test
    fun `standard ISO values falls back to the minimum when no standard stop overlaps`() {
        assertEquals(listOf(73), standardIsoValues(73, 90))
    }
}
