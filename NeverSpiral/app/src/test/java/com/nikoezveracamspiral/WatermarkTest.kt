package com.nikoezveracamspiral

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words on the watermark: nothing here draws anything. */
class WatermarkTest {
    @Test
    fun theDetailLineReadsLikeACameraSpecLine() {
        val meta = PhotoMeta(exposureNanos = 8_333_333L, iso = 50, aperture = 1.7f, focalLengthMm = 6.9f)
        assertEquals("6.9 mm  \u00B7  f/1.7  \u00B7  1/120 s  \u00B7  ISO 50", Watermark.detailLine(meta))
    }

    @Test
    fun missingDetailsAreLeftOutNotShownAsZero() {
        assertEquals("ISO 400", Watermark.detailLine(PhotoMeta(iso = 400)))
        assertEquals("", Watermark.detailLine(PhotoMeta()))
    }

    @Test
    fun longExposuresAreShownInSeconds() {
        assertEquals("1.0 s", Watermark.shutterText(1_000_000_000L))
        assertEquals("1/4 s", Watermark.shutterText(250_000_000L))
    }

    @Test
    fun aClearedTextFallsBackToTheDefault() {
        assertEquals("Shot on Niko's Cam", WatermarkSettings(text = "   ").shownText)
        assertEquals("Shot by Niko", WatermarkSettings(text = " Shot by Niko ").shownText)
    }
}
