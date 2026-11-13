package com.nikoezveracamspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraHdrWatermarkTest {
    @Test
    fun theHdrBoostKeepsOnlyBrightTones() {
        assertEquals(0f, UltraHdr.highlightWeight(0.2f), 0f)
        assertEquals(0f, UltraHdr.highlightWeight(0.55f), 1e-6f)
        assertEquals(1f, UltraHdr.highlightWeight(0.9f), 0f)
        val mid = UltraHdr.highlightWeight(0.7f)
        assertTrue(mid > 0.3f && mid < 0.7f)
        assertTrue(UltraHdr.highlightWeight(0.8f) > UltraHdr.highlightWeight(0.65f))
    }

    @Test
    fun noBoostIsStoredAsZeroWhenTheLowerLimitIsOne() {
        assertEquals(0f, UltraHdr.neutralEncoded(1f, 4f, 1f), 1e-6f)
        // Limits that also allow dimming (0.5x to 2x): no boost sits halfway.
        assertEquals(0.5f, UltraHdr.neutralEncoded(0.5f, 2f, 1f), 1e-4f)
    }

    @Test
    fun watermarkGetsABackingOnBrightOrBusyPartsOnly() {
        assertFalse(Watermark.needsBacking(60f, 12f))
        assertTrue(Watermark.needsBacking(180f, 10f))
        assertTrue(Watermark.needsBacking(90f, 55f))
    }
}
