package com.nikoezveracamspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotPixelsTest {
    private val w = 100
    private val h = 80

    private fun dark(noise: Int = 8) = ByteArray(w * h) { (noise + (it * 7919 % 5)).toByte() }

    @Test
    fun aLoneBrightPointInADarkFrameIsFound() {
        val luma = dark()
        luma[40 * w + 50] = 200.toByte()
        val spots = HotPixels.find(luma, w, h, minPeak = 40, minContrast = 28, maxRing = 40)
        assertEquals(1, spots.size)
        assertEquals(50, spots[0].xPx)
        assertEquals(40, spots[0].yPx)
        assertEquals(200, spots[0].peak)
        assertEquals(50f / (w - 1), spots[0].x, 1e-4f)
    }

    @Test
    fun aBrightObjectOrFlatNoiseIsNotAHotPixel() {
        val luma = dark()
        for (y in 20 until 50) for (x in 30 until 70) luma[y * w + x] = 200.toByte()
        assertTrue(HotPixels.find(luma, w, h, 40, 28, 40).isEmpty())
        assertTrue(HotPixels.find(dark(), w, h, 40, 28, 40).isEmpty())
    }

    @Test
    fun onlySpecksSeenInMostFramesCount() {
        val real = Spot(50, 40, 0.5f, 0.5f, 180, 160)
        val nearlyThere = Spot(51, 39, 0.51f, 0.49f, 170, 150)
        val noise = Spot(10, 10, 0.1f, 0.1f, 90, 70)
        val merged = HotPixels.consistent(listOf(listOf(real, noise), listOf(nearlyThere), listOf(real)), minFrames = 2)
        assertEquals(1, merged.size)
        assertEquals(50, merged[0].xPx)
    }

    @Test
    fun healingPaintsAHotPixelOutOfAFlatDarkArea() {
        val pixels = IntArray(w * h) { (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30 }
        pixels[40 * w + 50] = (0xFF shl 24) or (255 shl 16) or (250 shl 8) or 255
        SpotHeal.heal(pixels, w, h, listOf(Pair(50f / (w - 1), 40f / (h - 1))))
        val c = pixels[40 * w + 50]
        assertEquals(30, (c shr 16) and 0xFF)
        assertEquals(30, c and 0xFF)
        // Far away nothing changed.
        assertEquals(30, pixels[5 * w + 5] and 0xFF)
    }

    @Test
    fun aTapMovesToTheBrightPointNextToIt() {
        val pixels = IntArray(w * h) { (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20 }
        pixels[42 * w + 53] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        val snapped = SpotHeal.snap(pixels, w, h, 50f / (w - 1), 40f / (h - 1))
        assertEquals(53f / (w - 1), snapped.first, 1e-4f)
        assertEquals(42f / (h - 1), snapped.second, 1e-4f)
        // With nothing standing out the tap stays where it was.
        val flat = IntArray(w * h) { (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20 }
        assertEquals(Pair(0.5f, 0.5f), SpotHeal.snap(flat, w, h, 0.5f, 0.5f))
    }

    private fun run(t: Long, spots: List<Spot>) = SensorRun(t, "Rear camera (main)", 4080, 3072, 3200, 300, spots)
    private fun spot(peak: Int) = Spot(100, 100, 0.5f, 0.4f, peak, peak - 10)

    @Test
    fun statusFollowsHowManyAndHowBright() {
        assertEquals(SensorStatus.HEALTHY, SensorHealth.status(emptyList()))
        assertEquals(SensorStatus.MINOR, SensorHealth.status(listOf(spot(90))))
        assertEquals(SensorStatus.ATTENTION, SensorHealth.status(listOf(spot(200))))
        assertEquals(SensorStatus.ATTENTION, SensorHealth.status(List(5) { spot(60) }))
    }

    @Test
    fun trendComparesWithTheFirstCheck() {
        val first = run(1_000_000_000_000L, listOf(spot(120)))
        val later = run(1_100_000_000_000L, listOf(spot(120), spot(80)))
        assertEquals(null, SensorHealth.trend(listOf(first), first))
        assertTrue(SensorHealth.trend(listOf(first, later), later)!!.startsWith("2 hot pixels now, 1 at the first check"))
        val same = run(1_200_000_000_000L, listOf(spot(125)))
        assertTrue(SensorHealth.trend(listOf(first, same), same)!!.startsWith("No change"))
    }

    @Test
    fun theReportListsEveryRun() {
        val text = SensorHealth.report("Google Pixel 9 Pro XL", "17 (API 37)", "2.11.0", listOf(run(1_000_000_000_000L, listOf(spot(150)))))
        assertTrue(text.contains("Pixel 9 Pro XL"))
        assertTrue(text.contains("Visible hot pixels (1)"))
        assertTrue(text.contains("at pixel 100, 100"))
    }
}
