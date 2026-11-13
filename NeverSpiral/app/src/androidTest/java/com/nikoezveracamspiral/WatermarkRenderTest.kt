package com.nikoezveracamspiral

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Draws every watermark style on a sample picture, with the real app logo, and leaves the pictures behind to look at. */
@RunWith(AndroidJUnit4::class)
class WatermarkRenderTest {
    @Test
    fun everyStyleDrawsWithTheRealLogo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val logo = Watermark.logo(context)
        assertNotNull("the app logo should load from the launcher icon", logo)
        TestResults.saveBitmap("watermark_logo.jpg", logo!!, 256)
        val details = "6.9 mm  \u00B7  f/1.7  \u00B7  1/120 s  \u00B7  ISO 50"
        val cases = listOf(
            "corner_left" to WatermarkSettings(on = true),
            "corner_right_no_details" to WatermarkSettings(on = true, position = WatermarkPosition.RIGHT, details = false),
            "corner_centre_text_only" to WatermarkSettings(on = true, position = WatermarkPosition.CENTER, logo = false, device = false, details = false, text = "Shot by Niko on Niko's Cam"),
            "bar_light" to WatermarkSettings(on = true, style = WatermarkStyle.BAR),
            "bar_dark_right" to WatermarkSettings(on = true, style = WatermarkStyle.BAR, dark = true, position = WatermarkPosition.RIGHT),
            "bar_centre" to WatermarkSettings(on = true, style = WatermarkStyle.BAR, position = WatermarkPosition.CENTER),
        )
        for ((name, settings) in cases) {
            val bitmap = Watermark.preview(if (settings.logo) logo else null, settings, if (settings.details) details else "")
            TestResults.saveBitmap("watermark_$name.jpg", bitmap)
            TestResults.log("watermark $name: ${bitmap.width}x${bitmap.height}")
            if (settings.style == WatermarkStyle.BAR) assertTrue("a bar makes the picture taller", bitmap.height > bitmap.width * 4 / 3)
            else assertEquals(bitmap.width * 4 / 3, bitmap.height)
        }
    }
}
