package com.nikoezveracamspiral

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Pixel looks rendered on real pixels (needs Android's ColorMatrix, so it runs on the emulator). */
@RunWith(AndroidJUnit4::class)
class PixelLooksRenderTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun channels(p: Int) = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    private fun render(look: Look, p: Int): IntArray {
        val a = IntArray(16) { p }
        LookProcessor.apply(a, 4, 4, look)
        return channels(a[5])
    }

    @Test
    fun blackTieIsMonochrome() {
        val out = render(Look(filter = PhotoFilter.BLACK_TIE), px(200, 90, 40))
        assertEquals(out[0], out[1])
        assertEquals(out[1], out[2])
    }

    @Test
    fun vibranceLiftsMutedColoursMoreThanStrongOnes() {
        val muted = px(140, 120, 110)
        val strong = px(230, 60, 40)
        fun spread(c: IntArray) = c.max() - c.min()
        val look = Look(vibrance = 0.8f)
        val mutedGain = spread(render(look, muted)) - spread(channels(muted))
        val strongGain = spread(render(look, strong)) - spread(channels(strong))
        assertTrue("muted +$mutedGain, strong +$strongGain", mutedGain > 0 && mutedGain.toFloat() / spread(channels(muted)) > strongGain.toFloat() / spread(channels(strong)))
        val grey = render(look, px(100, 100, 100))
        assertEquals(grey[0], grey[2])
    }

    @Test
    fun velvetPushesBluesMoreThanGreens() {
        val blue = px(60, 90, 200)
        val green = px(80, 180, 70)
        val v = Look(filter = PhotoFilter.VELVET)
        val plain = Look(filter = PhotoFilter.VELVET.let { PhotoFilter.NATURAL })
        fun spread(c: IntArray) = (c.max() - c.min()).toFloat()
        val blueRatio = spread(render(v, blue)) / spread(render(plain, blue))
        val greenRatio = spread(render(v, green)) / spread(render(plain, green))
        assertTrue("blue x$blueRatio, green x$greenRatio", blueRatio > greenRatio)
    }
}
