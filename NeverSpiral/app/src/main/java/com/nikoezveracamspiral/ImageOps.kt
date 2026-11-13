package com.nikoezveracamspiral

import kotlin.math.abs

/** Shared pixel operations. */
internal object ImageOps {
    /** Separable box blur of radius [r] over the RGB channels of [px]. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val div = 2 * r + 1
        for (y in 0 until h) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = px[y * w + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = px[y * w + (x + r + 1).coerceIn(0, w - 1)]
                val sub = px[y * w + (x - r).coerceIn(0, w - 1)]
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val sub = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    /**
     * Unsharp mask: adds back [amount] of the difference between the image and a blurred copy
     * (radius [radius]), skipping differences under [threshold] so noise isn't amplified. A wider
     * radius than the 3x3 sharpen restores the soft, upscaled look of digital zoom.
     */
    fun unsharp(pixels: IntArray, w: Int, h: Int, radius: Int, amount: Float, threshold: Int = 3) {
        if (amount <= 0.01f) return
        val blurred = pixels.copyOf()
        boxBlur(blurred, w, h, radius)
        boxBlur(blurred, w, h, radius)
        parallelFor(h) { y ->
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                val b = blurred[row + x]
                var out = 0xFF shl 24
                var shift = 16
                while (shift >= 0) {
                    val pv = (p shr shift) and 0xFF
                    val diff = pv - ((b shr shift) and 0xFF)
                    val v = if (abs(diff) > threshold) (pv + amount * diff).toInt().coerceIn(0, 255) else pv
                    out = out or (v shl shift)
                    shift -= 8
                }
                pixels[row + x] = out
            }
        }
    }
}
