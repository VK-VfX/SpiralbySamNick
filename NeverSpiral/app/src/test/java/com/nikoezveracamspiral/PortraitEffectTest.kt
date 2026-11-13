package com.nikoezveracamspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream

/** The pure maths behind Portrait: nothing here touches the camera or Android. */
class PortraitEffectTest {

    @Test
    fun workingSizeFollowsTheAppHeap() {
        // A Pixel 9 Pro XL's 512 MB heap gets the full size, a Pixel 8a's 256 MB a smaller one, and a
        // tiny heap never goes below the floor.
        assertEquals(1024, PortraitEffect.workSizeFor(512L * 1024 * 1024))
        val small = PortraitEffect.workSizeFor(256L * 1024 * 1024)
        assertTrue("size on a 256 MB heap was $small", small in 640..800)
        assertEquals(576, PortraitEffect.workSizeFor(64L * 1024 * 1024))
    }

    @Test
    fun cleaningTheMaskKeepsAttachedStrandsAndDropsFloatingIslands() {
        val w = 100
        val h = 100
        val mask = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0..y1) for (x in x0..x1) mask[y * w + x] = 1f
        }
        fill(30, 30, 70, 70) // the subject
        fill(72, 40, 74, 43) // a strand of hair a pixel away from it
        fill(5, 5, 9, 9) // a stray patch far from it
        val out = PortraitEffect.cleanMask(mask, w, h)
        assertEquals(1f, out[50 * w + 50], 0f)
        assertEquals("the attached strand is kept", 1f, out[41 * w + 73], 0f)
        assertEquals("the floating island is dropped", 0f, out[7 * w + 7], 0f)
        // The subject's own edge is untouched, so there are no bites out of it.
        assertEquals(1f, out[30 * w + 30], 0f)
        assertEquals(1f, out[70 * w + 70], 0f)
    }

    @Test
    fun theDiscBlurSpreadsLightWithoutLosingAnyAndStaysSymmetric() {
        val w = 64
        val h = 64
        val a = FloatArray(w * h)
        a[32 * w + 32] = 1000f
        PortraitEffect.discBlurF(a, FloatArray(w * h), w, h, 8f)
        assertEquals("no light is gained or lost", 1000f, a.sum(), 1f)
        assertTrue("the point is spread out", a[32 * w + 32] < 100f)
        assertEquals(a[32 * w + 38], a[32 * w + 26], 1e-3f)
        assertEquals(a[38 * w + 32], a[26 * w + 32], 1e-3f)
        assertEquals(a[37 * w + 37], a[27 * w + 27], 1e-3f)
        // Flat, not peaked: the middle and a point halfway out are about equally lit.
        assertTrue(a[32 * w + 35] > 0.5f * a[32 * w + 32])
    }

    @Test
    fun aDiagonalBlurAveragesAlongTheDiagonal() {
        val w = 16
        val h = 16
        val a = FloatArray(w * h)
        for (i in 0 until 16) a[i * w + i] = 9f // the main diagonal
        PortraitEffect.diagBlurF(a, w, h, 3, 1)
        // A line along the blurred direction stays a line of the same value away from its ends.
        assertEquals(9f, a[8 * w + 8], 1e-4f)
        assertEquals(0f, a[8 * w + 9], 1e-4f)
    }

    @Test
    fun aSavedPortraitAnalysisSurvivesTheRoundTrip() {
        val w = 40
        val h = 30
        val n = w * h
        val analysis = PortraitAnalysis(
            w, h,
            FloatArray(n) { (it % 7) / 7f },
            FloatArray(n) { if (it % 5 == 0) 1f else 0f },
            BooleanArray(n) { it % 3 == 0 },
            0.42f,
        )
        val decoded = PortraitStore.decode(PortraitStore.encode(analysis, PortraitStyle.BOKEH, 0.7f, "6.9 mm  \u00B7  f/1.7"))
        assertNotNull(decoded)
        decoded!!
        assertEquals(PortraitStyle.BOKEH, decoded.style)
        assertEquals("6.9 mm  \u00B7  f/1.7", decoded.details)
        assertEquals(0.7f, decoded.strength, 1e-6f)
        assertEquals(0.42f, decoded.analysis.focus, 1e-6f)
        assertEquals(w, decoded.analysis.width)
        for (i in 0 until n) {
            assertEquals(analysis.depth[i], decoded.analysis.depth[i], 1f / 255f)
            assertEquals(analysis.keep[i], decoded.analysis.keep[i], 1f / 255f)
            assertEquals(analysis.confident[i], decoded.analysis.confident[i])
        }
    }

    @Test
    fun brokenAnalysisDataIsRejectedNotTrusted() {
        assertEquals(null, PortraitStore.decode(ByteArray(10) { it.toByte() }))
        assertEquals(null, PortraitStore.decode(ByteArray(0)))
    }

    @Test
    fun tappingAnotherThingMovesTheFocusAndTheSubjectToIt() {
        val w = 80
        val h = 60
        val n = w * h
        // Left strip near (depth 0.8), the rest far (0.3); the original subject was the far part.
        val depth = FloatArray(n) { if (it % w < 30) 0.8f else 0.3f }
        val keep = FloatArray(n) { if (it % w >= 30) 1f else 0f }
        val analysis = PortraitAnalysis(w, h, depth, keep, BooleanArray(n) { true }, 0.3f)
        val lum = FloatArray(n) { 0.5f }
        val moved = PortraitEffect.refocus(analysis, lum, Pair(0.15f, 0.5f))
        assertNotNull(moved)
        moved!!
        assertEquals(0.8f, moved.focus, 1e-4f)
        assertTrue("the tapped area is now the subject", moved.keep[30 * w + 10] > 0.5f)
        assertTrue("the old subject is now background", moved.keep[30 * w + 70] < 0.5f)
    }

    @Test
    fun aRecordInTheFirstFormatStillLoads() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
            out.writeInt(0x4E504131) // the first format, before watermark details were kept
            out.writeInt(16)
            out.writeInt(16)
            out.writeFloat(0.4f)
            out.writeInt(0)
            out.writeFloat(0.5f)
            out.write(ByteArray(256))
            out.write(ByteArray(256))
            out.write(ByteArray(256))
        }
        val decoded = PortraitStore.decode(bytes.toByteArray())
        assertNotNull(decoded)
        assertEquals("", decoded!!.details)
        assertEquals(16, decoded.analysis.width)
    }
}
