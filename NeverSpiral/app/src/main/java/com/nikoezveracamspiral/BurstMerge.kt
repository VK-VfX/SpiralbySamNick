package com.nikoezveracamspiral

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

class DecodedFrame(val pixels: IntArray, val w: Int, val h: Int)

/**
 * Multi-frame merge in the style of Pixel's HDR+ and Night Sight: a burst of frames is aligned to a
 * reference frame (a coarse-to-fine global shift search), and every pixel is averaged across the
 * frames that agree with the reference there, which cuts noise by roughly the square root of the
 * frame count and rejects anything that moved. The average then goes through a tone curve that
 * restores the exposure, lifts shadows and rolls highlights off smoothly.
 */
object BurstMerge {
    /** [lutFor] gets the reference frame's mean brightness (0..255) and returns a 1024-entry
     * tone curve indexed by the merged average times four. */
    fun merge(frames: List<ByteArray>, refIndex: Int, load: (ByteArray) -> DecodedFrame?, lutFor: (Float) -> IntArray): DecodedFrame? {
        val ref = load(frames[refIndex]) ?: return null
        val w = ref.w
        val h = ref.h
        val n = w * h
        val refPixels = ref.pixels
        val sumR = ShortArray(n)
        val sumG = ShortArray(n)
        val sumB = ShortArray(n)
        val sumW = ShortArray(n)
        var meanAcc = 0L
        var meanCount = 0
        parallelFor(h) { y ->
            for (i in y * w until (y + 1) * w) {
                val p = refPixels[i]
                sumR[i] = (((p shr 16) and 0xFF) * 4).toShort()
                sumG[i] = (((p shr 8) and 0xFF) * 4).toShort()
                sumB[i] = ((p and 0xFF) * 4).toShort()
                sumW[i] = 4
            }
        }
        for (i in 0 until n step 7) {
            meanAcc += luma(refPixels[i])
            meanCount++
        }
        val refMean = if (meanCount > 0) meanAcc.toFloat() / meanCount else 128f

        val w2 = w / 2
        val h2 = h / 2
        val refG2 = downscale2(refPixels, w, h)
        val w8 = w2 / 4
        val h8 = h2 / 4
        val refG8 = downscale4(refG2, w2, h2)

        for (index in frames.indices) {
            if (index == refIndex) continue
            val f = load(frames[index]) ?: continue
            if (f.w != w || f.h != h) continue
            val g2 = downscale2(f.pixels, w, h)
            val g8 = downscale4(g2, w2, h2)
            val coarse = search(refG8, g8, w8, h8, range = 8, cx = 0, cy = 0, stride = 2)
            val fine = search(refG2, g2, w2, h2, range = 3, cx = coarse.first * 4, cy = coarse.second * 4, stride = 3)
            val dx = fine.first * 2
            val dy = fine.second * 2
            val px = f.pixels
            parallelFor(h) { y ->
                val sy = (y + dy).coerceIn(0, h - 1)
                val rowOut = y * w
                val rowSrc = sy * w
                for (x in 0 until w) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val q = px[rowSrc + sx]
                    val d = abs(luma(q) - luma(refPixels[rowOut + x]))
                    val wt = when {
                        d < 10 -> 4
                        d < 18 -> 3
                        d < 30 -> 2
                        d < 48 -> 1
                        else -> 0
                    }
                    if (wt == 0) continue
                    val i = rowOut + x
                    sumR[i] = (sumR[i] + ((q shr 16) and 0xFF) * wt).toShort()
                    sumG[i] = (sumG[i] + ((q shr 8) and 0xFF) * wt).toShort()
                    sumB[i] = (sumB[i] + (q and 0xFF) * wt).toShort()
                    sumW[i] = (sumW[i] + wt).toShort()
                }
            }
        }

        val lut = lutFor(refMean)
        parallelFor(h) { y ->
            for (i in y * w until (y + 1) * w) {
                val wsum = sumW[i].toInt()
                val r = lut[(sumR[i] * 4 / wsum).coerceIn(0, 1023)]
                val g = lut[(sumG[i] * 4 / wsum).coerceIn(0, 1023)]
                val b = lut[(sumB[i] * 4 / wsum).coerceIn(0, 1023)]
                refPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ref
    }

    private fun luma(p: Int): Int = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8

    private fun downscale2(px: IntArray, w: Int, h: Int): ByteArray {
        val w2 = w / 2
        val h2 = h / 2
        val out = ByteArray(w2 * h2)
        for (y in 0 until h2) {
            val r0 = y * 2 * w
            val r1 = r0 + w
            for (x in 0 until w2) {
                val s = luma(px[r0 + x * 2]) + luma(px[r0 + x * 2 + 1]) + luma(px[r1 + x * 2]) + luma(px[r1 + x * 2 + 1])
                out[y * w2 + x] = (s shr 2).toByte()
            }
        }
        return out
    }

    private fun downscale4(g: ByteArray, w: Int, h: Int): ByteArray {
        val w4 = w / 4
        val h4 = h / 4
        val out = ByteArray(w4 * h4)
        for (y in 0 until h4) {
            for (x in 0 until w4) {
                var s = 0
                for (dy in 0 until 4) for (dx in 0 until 4) s += g[(y * 4 + dy) * w + x * 4 + dx].toInt() and 0xFF
                out[y * w4 + x] = (s shr 4).toByte()
            }
        }
        return out
    }

    /** The (dx, dy) minimizing the sum of absolute differences between [ref] and [cur] shifted by
     * that offset, searched within [range] of the starting guess (cx, cy). */
    private fun search(ref: ByteArray, cur: ByteArray, w: Int, h: Int, range: Int, cx: Int, cy: Int, stride: Int): Pair<Int, Int> {
        val maxX = abs(cx) + range
        val maxY = abs(cy) + range
        if (w <= 2 * maxX + 4 || h <= 2 * maxY + 4) return 0 to 0
        var bestX = cx
        var bestY = cy
        var best = Long.MAX_VALUE
        for (sy in cy - range..cy + range) {
            for (sx in cx - range..cx + range) {
                var sad = 0L
                var y = maxY
                while (y < h - maxY) {
                    val rowRef = y * w
                    val rowCur = (y + sy) * w
                    var x = maxX
                    while (x < w - maxX) {
                        sad += abs((ref[rowRef + x].toInt() and 0xFF) - (cur[rowCur + x + sx].toInt() and 0xFF))
                        x += stride
                    }
                    if (sad >= best) break
                    y += stride
                }
                if (sad < best) {
                    best = sad
                    bestX = sx
                    bestY = sy
                }
            }
        }
        return bestX to bestY
    }
}

/** Tone curves applied to a merged burst. Values are gamma-encoded 0..255; the lookup tables are
 * indexed by the merged average times four. */
object ToneMap {
    /** HDR+: the frames were deliberately underexposed by [underEv] stops to protect highlights,
     * so the curve restores that exposure, lifts shadows by [strength] (0..1) and rolls the
     * highlights off smoothly rather than clipping them. */
    fun hdrPlus(strength: Float, underEv: Float): IntArray {
        val gain = 2.0.pow((-underEv) / 2.2).toFloat()
        return IntArray(1024) { i ->
            var x = (i / 4f) / 255f * gain
            x = x.coerceAtLeast(0f).pow(1f - 0.30f * strength)
            if (x > 0.72f) x = 0.72f + 0.28f * (1f - exp(-(x - 0.72f) / 0.28f))
            x += (x * x * (3f - 2f * x) - x) * 0.25f
            (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
    }

    /** Night Sight: brightens to a target level from the reference frame's brightness, with a
     * small black point so noise floor stays dark, a shadow lift and a gentle S-curve. */
    fun night(refMean: Float): IntArray {
        val gain = (90f / max(refMean, 6f)).coerceIn(1f, 5f)
        return IntArray(1024) { i ->
            var x = (i / 4f) / 255f
            x = ((x - 0.015f).coerceAtLeast(0f)) * gain
            x = x.pow(0.9f)
            if (x > 0.72f) x = 0.72f + 0.28f * (1f - exp(-(x - 0.72f) / 0.28f))
            x += (x * x * (3f - 2f * x) - x) * 0.2f
            (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
    }
}
