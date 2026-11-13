package com.nikoezveracamspiral

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The looks Portrait mode can give the background (and, for Studio, the subject). */
enum class PortraitStyle(val label: String) {
    NATURAL("Natural"),
    STUDIO("Studio"),
    SOFT("Soft"),
    BOKEH("Bokeh"),
    STAGE("Stage"),
    MONO("Mono"),
}

/**
 * Portrait mode's background blur, built the way a camera's depth blur behaves rather than as a
 * flat cut-out:
 *
 *  1. Find the subject: an on-device person model, or for anything else (a product, a plant) the
 *     part of the frame that is in focus.
 *  2. Snap that rough shape to the real edges of the picture (a guided filter), so the outline
 *     follows the subject instead of floating next to it, and lean it slightly outward so hair
 *     and fine edges stay sharp.
 *  3. Blur only the background, using only background colours (so the subject's colours can never
 *     bleed out into a glow around it), and blur more the further a pixel is from the subject, as
 *     a real lens does.
 *  4. Put grain back into the blurred area so it matches the sharp part, then apply the style.
 *
 * If no subject is found the photo is left untouched.
 */
object PortraitEffect {
    private class Params(
        /** Largest blur radius as a fraction of the picture's long side. */
        val radius: Float,
        val bloom: Boolean = false,
        val subjectLift: Boolean = false,
        val backgroundGain: Float = 1f,
        val mono: Boolean = false,
    )

    private fun paramsFor(style: PortraitStyle) = when (style) {
        PortraitStyle.NATURAL -> Params(0.010f)
        PortraitStyle.STUDIO -> Params(0.016f, subjectLift = true, backgroundGain = 0.95f)
        PortraitStyle.SOFT -> Params(0.0055f)
        PortraitStyle.BOKEH -> Params(0.013f, bloom = true)
        PortraitStyle.STAGE -> Params(0.011f, backgroundGain = 0.22f)
        PortraitStyle.MONO -> Params(0.011f, backgroundGain = 0.9f, mono = true)
    }

    /** Returns true if a subject was found and the background was blurred. [strength] is 0..1. */
    fun apply(pixels: IntArray, w: Int, h: Int, strength: Float, style: PortraitStyle = PortraitStyle.NATURAL): Boolean {
        val params = paramsFor(style)
        val longSide = max(w, h)
        val scale = min(1f, 1280f / longSide)
        val ww = max(16, (w * scale).roundToInt())
        val wh = max(16, (h * scale).roundToInt())
        val n = ww * wh
        val work = downscale(pixels, w, h, ww, wh)
        val lum = FloatArray(n) { i ->
            val p = work[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }

        // 1 + 2. The subject, snapped to the picture's real edges.
        val rough = personMask(work, ww, wh) ?: focusMask(lum, ww, wh) ?: return false
        val mask = refine(lum, rough, ww, wh)
        var coverage = 0f
        for (m in mask) if (m > 0.5f) coverage += 1f
        coverage /= n
        if (coverage < 0.02f || coverage > 0.97f) return false

        // 3. Blur grows with distance from the subject.
        val distance = distanceFromSubject(mask, ww, wh)
        val reach = 0.16f * max(ww, wh)
        val depth = FloatArray(n) { i -> smooth((distance[i] / reach).coerceIn(0f, 1f)) }

        val strengthFactor = 0.3f + strength.coerceIn(0f, 1f)
        val maxRadius = (params.radius * longSide * strengthFactor * (ww.toFloat() / w)).coerceAtLeast(2f)
        val background = FloatArray(n) { i -> 1f - mask[i] }
        val levels = Array(3) { k ->
            blurredLevel(work, background, ww, wh, (maxRadius * (k + 1) / 3f).roundToInt().coerceAtLeast(1), params.bloom)
        }

        // The blurred backdrop, blended between the three blur strengths by depth, with the style's tint.
        val backdrop = IntArray(n)
        parallelFor(wh) { y ->
            for (x in 0 until ww) {
                val i = y * ww + x
                val pos = depth[i] * 2f
                val k = min(pos.toInt(), 1)
                val t = pos - k
                val a = levels[k][i]
                val b = levels[k + 1][i]
                var r = lerp((a shr 16) and 0xFF, (b shr 16) and 0xFF, t)
                var g = lerp((a shr 8) and 0xFF, (b shr 8) and 0xFF, t)
                var bl = lerp(a and 0xFF, b and 0xFF, t)
                if (params.mono) {
                    val gray = 0.299f * r + 0.587f * g + 0.114f * bl
                    r = gray; g = gray; bl = gray
                }
                val gain = params.backgroundGain
                backdrop[i] = (0xFF shl 24) or
                    ((r * gain).toInt().coerceIn(0, 255) shl 16) or
                    ((g * gain).toInt().coerceIn(0, 255) shl 8) or
                    (bl * gain).toInt().coerceIn(0, 255)
            }
        }

        // 4. Composite at full resolution: sharp subject, blurred backdrop with matching grain.
        val xs = (ww - 1).toFloat() / (w - 1).coerceAtLeast(1)
        val ys = (wh - 1).toFloat() / (h - 1).coerceAtLeast(1)
        parallelFor(h) { y ->
            val fy = y * ys
            val y0 = fy.toInt().coerceAtMost(wh - 2)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = x * xs
                val x0 = fx.toInt().coerceAtMost(ww - 2)
                val tx = fx - x0
                val i00 = y0 * ww + x0
                val m = (mask[i00] * (1 - tx) + mask[i00 + 1] * tx) * (1 - ty) +
                    (mask[i00 + ww] * (1 - tx) + mask[i00 + ww + 1] * tx) * ty
                val i = y * w + x
                var p = pixels[i]
                if (params.subjectLift && m > 0.01f) p = lift(p, m)
                if (m >= 0.999f) {
                    pixels[i] = p
                    continue
                }
                val c00 = backdrop[i00]
                val c10 = backdrop[i00 + 1]
                val c01 = backdrop[i00 + ww]
                val c11 = backdrop[i00 + ww + 1]
                fun channel(shift: Int): Float {
                    val top = ((c00 shr shift) and 0xFF) * (1 - tx) + ((c10 shr shift) and 0xFF) * tx
                    val bottom = ((c01 shr shift) and 0xFF) * (1 - tx) + ((c11 shr shift) and 0xFF) * tx
                    return top * (1 - ty) + bottom * ty
                }
                // Grain, so the smooth backdrop doesn't look plastic next to the sharp subject.
                val grain = (((hash(x, y) ushr 24) - 128) / 128f) * 1.4f
                val inv = 1f - m
                fun mix(shift: Int): Int {
                    val sharp = (p shr shift) and 0xFF
                    val soft = channel(shift) + grain
                    return (sharp * m + soft * inv).toInt().coerceIn(0, 255)
                }
                pixels[i] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
        return true
    }

    // ---- subject ----

    /** The person model's mask at the working size, or null when it finds nobody. */
    private fun personMask(work: IntArray, ww: Int, wh: Int): FloatArray? {
        return try {
            val side = 512f / max(ww, wh)
            val sw = (ww * side).roundToInt().coerceAtLeast(1)
            val sh = (wh * side).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(work, ww, wh, Bitmap.Config.ARGB_8888)
            val input = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            if (input !== bitmap) bitmap.recycle()
            val segmenter = Segmentation.getClient(
                SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build(),
            )
            val mask = try {
                Tasks.await(segmenter.process(InputImage.fromBitmap(input, 0)))
            } finally {
                segmenter.close()
                input.recycle()
            }
            val mw = mask.width
            val mh = mask.height
            val buffer = mask.buffer
            buffer.rewind()
            val conf = FloatArray(mw * mh) { buffer.float }
            var covered = 0f
            for (c in conf) if (c > 0.5f) covered += 1f
            covered /= conf.size
            if (covered < 0.03f || covered > 0.95f) null else resize(conf, mw, mh, ww, wh)
        } catch (e: Exception) {
            android.util.Log.w("PortraitEffect", "Person model failed; trying the focus-based subject", e)
            null
        }
    }

    /**
     * For a subject that isn't a person: the part of the frame that is in focus, found from how
     * much fine detail each area has, favouring the middle of the frame and filled in so a plain
     * surface inside the subject isn't left out. Null when nothing stands out.
     */
    private fun focusMask(lum: FloatArray, ww: Int, wh: Int): FloatArray? {
        val n = ww * wh
        val tmp = FloatArray(n)
        val energy = FloatArray(n)
        parallelFor(wh) { y ->
            for (x in 1 until ww - 1) {
                val i = y * ww + x
                val gx = lum[i + 1] - lum[i - 1]
                val up = if (y > 0) lum[i - ww] else lum[i]
                val down = if (y < wh - 1) lum[i + ww] else lum[i]
                energy[i] = kotlin.math.abs(gx) + kotlin.math.abs(down - up)
            }
        }
        val smoothR = max(3, max(ww, wh) / 160)
        boxBlurF(energy, tmp, ww, wh, smoothR)
        boxBlurF(energy, tmp, ww, wh, smoothR)

        // How much detail the middle of the frame has is the yardstick for "in focus".
        var centre = 0f
        var count = 0
        for (y in (wh * 0.3f).toInt() until (wh * 0.7f).toInt()) {
            for (x in (ww * 0.3f).toInt() until (ww * 0.7f).toInt()) {
                centre += energy[y * ww + x]
                count++
            }
        }
        if (count == 0) return null
        val reference = centre / count
        if (reference < 0.004f) return null

        val conf = FloatArray(n)
        parallelFor(wh) { y ->
            val dy = (y - wh / 2f) / (wh / 2f)
            for (x in 0 until ww) {
                val dx = (x - ww / 2f) / (ww / 2f)
                val prior = (1f - 0.45f * (dx * dx + dy * dy) / 2f).coerceIn(0.5f, 1f)
                val e = energy[y * ww + x] * prior
                conf[y * ww + x] = smooth(((e - 0.35f * reference) / (0.5f * reference)).coerceIn(0f, 1f))
            }
        }
        // Close holes inside the subject and rejoin pieces: blur widely, then re-threshold.
        val close = max(4, max(ww, wh) / 45)
        repeat(2) { boxBlurF(conf, tmp, ww, wh, close) }
        for (i in 0 until n) conf[i] = smooth(((conf[i] - 0.22f) / 0.3f).coerceIn(0f, 1f))

        var covered = 0f
        for (c in conf) if (c > 0.5f) covered += 1f
        covered /= n
        return if (covered < 0.04f || covered > 0.8f) null else conf
    }

    /** Snaps the rough mask to the picture's own edges with a guided filter, leaning outward. */
    private fun refine(lum: FloatArray, rough: FloatArray, ww: Int, wh: Int): FloatArray {
        val radius = max(3, max(ww, wh) / 150)
        val q = guidedFilter(lum, rough, ww, wh, radius, 0.0006f)
        return FloatArray(q.size) { i -> smooth(((q[i] - 0.16f) / 0.46f).coerceIn(0f, 1f)) }
    }

    /** Edge-aware smoothing of [p] guided by [guide] (He et al.'s guided filter, grayscale). */
    private fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = guide.size
        val tmp = FloatArray(n)
        fun mean(src: FloatArray): FloatArray {
            val out = src.copyOf()
            boxBlurF(out, tmp, w, h, r)
            return out
        }
        val meanI = mean(guide)
        val meanP = mean(p)
        val meanIP = mean(FloatArray(n) { guide[it] * p[it] })
        val meanII = mean(FloatArray(n) { guide[it] * guide[it] })
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = meanII[i] - meanI[i] * meanI[i]
            val covariance = meanIP[i] - meanI[i] * meanP[i]
            a[i] = covariance / (variance + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        boxBlurF(a, tmp, w, h, r)
        boxBlurF(b, tmp, w, h, r)
        return FloatArray(n) { (a[it] * guide[it] + b[it]).coerceIn(0f, 1f) }
    }

    /** Distance in pixels from each pixel to the nearest subject pixel (0 inside), by two-pass chamfer. */
    private fun distanceFromSubject(mask: FloatArray, w: Int, h: Int): FloatArray {
        val inf = 1_000_000
        val d = IntArray(w * h) { if (mask[it] > 0.5f) 0 else inf }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + 3)
                if (y > 0) {
                    v = min(v, d[i - w] + 3)
                    if (x > 0) v = min(v, d[i - w - 1] + 4)
                    if (x < w - 1) v = min(v, d[i - w + 1] + 4)
                }
                d[i] = v
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                var v = d[i]
                if (x < w - 1) v = min(v, d[i + 1] + 3)
                if (y < h - 1) {
                    v = min(v, d[i + w] + 3)
                    if (x < w - 1) v = min(v, d[i + w + 1] + 4)
                    if (x > 0) v = min(v, d[i + w - 1] + 4)
                }
                d[i] = v
            }
        }
        return FloatArray(w * h) { if (d[it] >= inf) w.toFloat() else d[it] / 3f }
    }

    // ---- blur ----

    /**
     * The picture blurred with radius [radius], using only background pixels: each blurred pixel is
     * the average of the *background* colours around it, so the subject's colours never leak out
     * (which is what causes a glow around the subject). Where there is no background nearby the
     * original colour is kept.
     */
    private fun blurredLevel(work: IntArray, background: FloatArray, ww: Int, wh: Int, radius: Int, bloom: Boolean): IntArray {
        val n = ww * wh
        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        val weight = background.copyOf()
        for (i in 0 until n) {
            val p = work[i]
            var r = ((p shr 16) and 0xFF).toFloat()
            var g = ((p shr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()
            if (bloom) {
                // Bright points swell a little before blurring, so they melt into soft discs.
                val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                if (l > 0.78f) {
                    val boost = 1f + 1.1f * (l - 0.78f) / 0.22f
                    r *= boost; g *= boost; b *= boost
                }
            }
            val wgt = background[i]
            red[i] = r * wgt
            green[i] = g * wgt
            blue[i] = b * wgt
        }
        val tmp = FloatArray(n)
        repeat(3) {
            boxBlurF(red, tmp, ww, wh, radius)
            boxBlurF(green, tmp, ww, wh, radius)
            boxBlurF(blue, tmp, ww, wh, radius)
            boxBlurF(weight, tmp, ww, wh, radius)
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val wgt = weight[i]
            out[i] = if (wgt < 0.02f) {
                work[i]
            } else {
                (0xFF shl 24) or
                    ((red[i] / wgt).toInt().coerceIn(0, 255) shl 16) or
                    ((green[i] / wgt).toInt().coerceIn(0, 255) shl 8) or
                    (blue[i] / wgt).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Box mean of radius [r] in place over [a] (edges use the pixels that exist), through [tmp]. */
    private fun boxBlurF(a: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        parallelFor(h) { y ->
            val base = y * w
            val prefix = FloatArray(w + 1)
            for (x in 0 until w) prefix[x + 1] = prefix[x] + a[base + x]
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = min(w - 1, x + r)
                tmp[base + x] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            }
        }
        parallelFor(w) { x ->
            val prefix = FloatArray(h + 1)
            for (y in 0 until h) prefix[y + 1] = prefix[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = min(h - 1, y + r)
                a[y * w + x] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            }
        }
    }

    // ---- helpers ----

    /** Studio light: a gentle lift of brightness and contrast on the subject, weighted by [m]. */
    private fun lift(p: Int, m: Float): Int {
        fun ch(shift: Int): Int {
            val v = ((p shr shift) and 0xFF).toFloat()
            val lifted = (((v / 255f - 0.5f) * 1.06f + 0.5f) * 1.05f * 255f).coerceIn(0f, 255f)
            return (v + (lifted - v) * m).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun lerp(a: Int, b: Int, t: Float): Float = a + (b - a) * t

    private fun hash(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    /** Area-averaged shrink of [src] to [dw] x [dh]. */
    private fun downscale(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val out = IntArray(dw * dh)
        val bx = max(1, sw / dw)
        val by = max(1, sh / dh)
        parallelFor(dh) { y ->
            val sy0 = min(sh - by, (y * sh.toLong() / dh).toInt())
            for (x in 0 until dw) {
                val sx0 = min(sw - bx, (x * sw.toLong() / dw).toInt())
                var r = 0
                var g = 0
                var b = 0
                for (yy in 0 until by) {
                    val row = (sy0 + yy) * sw + sx0
                    for (xx in 0 until bx) {
                        val p = src[row + xx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val c = bx * by
                out[y * dw + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
            }
        }
        return out
    }

    /** Bilinear resize of a float image. */
    private fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        parallelFor(dh) { y ->
            val fy = y * (sh - 1f) / (dh - 1).coerceAtLeast(1)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = x * (sw - 1f) / (dw - 1).coerceAtLeast(1)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                out[y * dw + x] = (src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx) * (1 - ty) +
                    (src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx) * ty
            }
        }
        return out
    }
}
