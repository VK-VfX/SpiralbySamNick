package com.nikoezveracamspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class CoreLogicTest {
    /** A 2x2x2 cube that maps every colour to itself (red varies fastest, as the format says). */
    private val identityCube = """
        # identity
        TITLE "identity"
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test
    fun parsesAnIdentityCubeAndSamplesItUnchanged() {
        val lut = Lut.parseCube("identity", identityCube)
        assertEquals(2, lut.size)
        val out = FloatArray(3)
        lut.sample(128f, 64f, 200f, out)
        assertEquals(128f, out[0], 1.5f)
        assertEquals(64f, out[1], 1.5f)
        assertEquals(200f, out[2], 1.5f)
    }

    @Test
    fun identityCubeFitsAnIdentityMatrix() {
        val m = Lut.parseCube("identity", identityCube).fit
        assertEquals(1f, m[0], 0.02f)
        assertEquals(1f, m[6], 0.02f)
        assertEquals(1f, m[12], 0.02f)
        assertEquals(0f, m[1], 0.02f)
        assertEquals(0f, m[4], 2f)
    }

    @Test
    fun rejectsOneDimensionalAndMalformedCubes() {
        try {
            Lut.parseCube("bad", "LUT_1D_SIZE 4\n0 0 0\n")
            fail("1D LUT should be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            Lut.parseCube("bad", "LUT_3D_SIZE 2\n0 0 0\n1 1 1\n")
            fail("wrong entry count should be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertNonDecreasing(lut: IntArray) {
        for (i in 1 until lut.size) assertTrue("lut[$i] < lut[${i - 1}]", lut[i] >= lut[i - 1])
        assertTrue(lut.all { it in 0..255 })
    }

    @Test
    fun toneCurvesAreMonotonicAndInRange() {
        assertNonDecreasing(ToneMap.hdrPlus(0.8f, -1f))
        assertNonDecreasing(ToneMap.hdrPlus(0.2f, -0.6f))
        assertNonDecreasing(ToneMap.night(12f))
        assertNonDecreasing(ToneMap.night(90f))
        assertEquals(0, ToneMap.hdrPlus(0.8f, -1f)[0])
    }

    private fun texture(w: Int, h: Int): IntArray {
        var seed = 12345
        return IntArray(w * h) {
            seed = seed * 1103515245 + 12345
            val v = (seed ushr 16) and 0xFF
            (0xFF shl 24) or (v shl 16) or (((v * 3) and 0xFF) shl 8) or ((v * 7) and 0xFF)
        }
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray, w: Int, margin: Int, h: Int): Double {
        var sum = 0L
        var count = 0
        for (y in margin until h - margin) {
            for (x in margin until w - margin) {
                val p = a[y * w + x]
                val q = b[y * w + x]
                sum += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
                count++
            }
        }
        return sum.toDouble() / count
    }

    @Test
    fun mergingIdenticalFramesKeepsTheImage() {
        val w = 256
        val h = 256
        val base = texture(w, h)
        val identity = IntArray(1024) { (it / 4).coerceIn(0, 255) }
        val frames = List(3) { ByteArray(1) { it.toByte() } }
        val result = BurstMerge.merge(
            frames, 1,
            load = { DecodedFrame(base.copyOf(), w, h) },
            lutFor = { identity },
        )!!
        assertTrue(meanAbsDiff(base, result.pixels, w, 0, h) < 1.0)
    }

    @Test
    fun mergingAShiftedFrameDoesNotSmearTheReference() {
        val w = 256
        val h = 256
        val base = texture(w, h)
        val shifted = IntArray(w * h) { i -> base[(i / w) * w + ((i % w) + 8).coerceAtMost(w - 1)] }
        val identity = IntArray(1024) { (it / 4).coerceIn(0, 255) }
        var call = 0
        val result = BurstMerge.merge(
            listOf(ByteArray(1), ByteArray(1)), 0,
            load = { DecodedFrame(if (call++ == 0) base.copyOf() else shifted.copyOf(), w, h) },
            lutFor = { identity },
        )!!
        // Whether the shift is found or the mismatching frame is rejected, the result stays close
        // to the reference rather than a blurred average of the two.
        assertTrue(meanAbsDiff(base, result.pixels, w, 16, h) < 12.0)
    }
}
