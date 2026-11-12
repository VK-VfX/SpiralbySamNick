package com.nikoezveracamspiral

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max

/**
 * Portrait mode's background blur: an on-device person segmentation model (bundled, no network)
 * produces a soft mask of the subject, and everything outside it is replaced by a blurred copy of
 * the photo, with a feathered edge. If no person is found the photo is left untouched rather than
 * blurring the whole frame.
 */
object PortraitEffect {
    /** Returns true if a subject was found and the background was blurred. [strength] is 0..1. */
    fun apply(pixels: IntArray, w: Int, h: Int, strength: Float): Boolean {
        val full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val segSide = 512
        val scale = segSide.toFloat() / max(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(1)
        val sh = (h * scale).toInt().coerceAtLeast(1)
        val segInput = Bitmap.createScaledBitmap(full, sw, sh, true)
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build(),
        )
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(segInput, 0)))
        } finally {
            segmenter.close()
            if (segInput !== full) segInput.recycle()
        }
        val mw = mask.width
        val mh = mask.height
        val buffer = mask.buffer
        buffer.rewind()
        val conf = FloatArray(mw * mh) { buffer.float }
        var coverage = 0f
        for (c in conf) coverage += if (c > 0.5f) 1f else 0f
        coverage /= conf.size
        if (coverage < 0.03f || coverage > 0.95f) {
            full.recycle()
            return false
        }

        // Blur at one sixth resolution, then scale back up.
        val bw = (w / 6).coerceAtLeast(2)
        val bh = (h / 6).coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(full, bw, bh, true)
        full.recycle()
        val smallPixels = IntArray(bw * bh)
        small.getPixels(smallPixels, 0, bw, 0, 0, bw, bh)
        small.recycle()
        val radius = (2 + (8 * strength).toInt())
        repeat(3) { boxBlur(smallPixels, bw, bh, radius) }
        val blurredSmall = Bitmap.createBitmap(smallPixels, bw, bh, Bitmap.Config.ARGB_8888)
        val blurredFull = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
        blurredSmall.recycle()
        val blurred = IntArray(w * h)
        blurredFull.getPixels(blurred, 0, w, 0, 0, w, h)
        blurredFull.recycle()

        for (y in 0 until h) {
            val fy = y * (mh - 1f) / (h - 1).coerceAtLeast(1)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, mh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = x * (mw - 1f) / (w - 1).coerceAtLeast(1)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, mw - 1)
                val tx = fx - x0
                val c = (conf[y0 * mw + x0] * (1 - tx) + conf[y0 * mw + x1] * tx) * (1 - ty) +
                    (conf[y1 * mw + x0] * (1 - tx) + conf[y1 * mw + x1] * tx) * ty
                val t = ((c - 0.35f) / 0.4f).coerceIn(0f, 1f)
                val m = t * t * (3f - 2f * t)
                if (m >= 0.999f) continue
                val i = y * w + x
                val a = pixels[i]
                val b = blurred[i]
                fun mix(shift: Int): Int {
                    val av = (a shr shift) and 0xFF
                    val bv = (b shr shift) and 0xFF
                    return (bv + (av - bv) * m).toInt().coerceIn(0, 255)
                }
                pixels[i] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
        return true
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
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
}
