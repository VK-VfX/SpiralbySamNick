package com.nikoezveracamspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelLooksTest {
    @Test
    fun pixelHasFourStylesAndSixPresets() {
        val names = PhotoFilter.entries.filter { it.category == FilterCategory.PIXEL }.map { it.label }
        assertEquals(10, names.size)
        assertEquals(names.size, names.toSet().size)
        assertEquals("Original", names.first())
        assertTrue(listOf("Editorial", "Velvet", "Digi", "Black Tie", "Minimal").all { it in names })
    }

    @Test
    fun minimalAddsNoExtraSharpening() {
        assertEquals(0f, Look(filter = PhotoFilter.MINIMAL).baseSharpen, 0f)
        assertEquals(0.2f, Look(filter = PhotoFilter.PX_VANILLA).baseSharpen, 0f)
    }

    @Test
    fun slidersReadInPixelsOwnUnits() {
        assertEquals("+5", LookParam.WARMTH.display(Look(warmth = 0.5f)))
        assertEquals("-10", LookParam.WARMTH.display(Look(warmth = -1f)))
        assertEquals("15", LookParam.SHARPNESS.display(Look(sharpness = 1f)))
        assertEquals("None", LookParam.GRAIN.display(Look(grain = 0f)))
        assertEquals("Moderate", LookParam.GRAIN.display(Look(grain = 2f / 3f)))
        assertEquals("Strong", LookParam.GRAIN.display(Look(grain = 1f)))
    }
}
