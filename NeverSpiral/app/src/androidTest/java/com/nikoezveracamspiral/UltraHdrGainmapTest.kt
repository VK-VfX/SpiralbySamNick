package com.nikoezveracamspiral

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Ultra HDR boost is kept for bright parts of the picture only (needs Android 14's gain-map classes). */
@RunWith(AndroidJUnit4::class)
class UltraHdrGainmapTest {
    private fun gainmap(): Gainmap {
        val contents = Bitmap.createBitmap(16, 16, Bitmap.Config.ALPHA_8)
        contents.eraseColor(0xFF000000.toInt()) // full boost everywhere
        return Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(4f, 4f, 4f)
            setGamma(1f, 1f, 1f)
        }
    }

    private fun picture(leftLuma: Int, rightLuma: Int): IntArray {
        val left = (0xFF shl 24) or (leftLuma shl 16) or (leftLuma shl 8) or leftLuma
        val right = (0xFF shl 24) or (rightLuma shl 16) or (rightLuma shl 8) or rightLuma
        return IntArray(64 * 64) { if (it % 64 < 32) left else right }
    }

    @Test
    fun darkPartsLoseTheBoostAndBrightPartsKeepIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val tamed = UltraHdr.highlightsOnly(gainmap(), picture(50, 245), 64, 64)
        assertNotNull(tamed)
        val out = tamed!!.gainmapContents
        assertEquals("a dark part has no boost left", 0, android.graphics.Color.alpha(out.getPixel(2, 8)))
        assertEquals("a bright part keeps the full boost", 255, android.graphics.Color.alpha(out.getPixel(13, 8)))
    }

    @Test
    fun aPictureWithNothingBrightGetsNoHdrLayer() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        assertNull(UltraHdr.highlightsOnly(gainmap(), picture(80, 120), 64, 64))
    }
}
