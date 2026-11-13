package com.nikoezveracamspiral

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One depth cue at some working size: [value] (larger = nearer where the cue knows) and [conf] (0..1) for every pixel. */
class DepthPlane(val w: Int, val h: Int, val value: FloatArray, val conf: FloatArray)

/** The depth cues measured for one photo. Either may be missing. */
class DepthCues(val stereo: DepthPlane?, val focus: DepthPlane?)

/**
 * The second lens's view taken with the shot: its brightness only, in the sensor's own
 * orientation, plus what is needed to line it up with the main picture.
 *
 * @property rotationDegrees clockwise turn that makes the frame upright.
 * @property focalWide focal length of the second lens divided by its sensor's long side; [focalMain] the same for the main lens.
 * @property horizontal true if the two lenses sit side by side in the upright picture (so the parallax runs left-right).
 */
class StereoFrame(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val focalWide: Float,
    val focalMain: Float,
    val horizontal: Boolean,
)

/** Small viewfinder frames taken with the lens focused at [diopters] (0 = far, larger = nearer), brightness only. */
class FocusStack(val width: Int, val height: Int, val diopters: FloatArray, val luma: List<ByteArray>)

/** Everything the camera measured for Portrait besides the photo itself. */
class DepthCapture(val stereo: StereoFrame?, val focus: FocusStack?)

/**
 * Builds Portrait's measured depth cues, the way phones with no depth sensor do:
 *
 *  - **Stereo from two lenses.** The main and ultra-wide lenses are about a centimetre and a half
 *    apart, so nearby things sit at slightly different places in the two pictures, and the nearer
 *    they are the bigger the shift. The wide picture is first cropped and enlarged to the main
 *    lens's field of view, then every pixel is matched along the line between the lenses.
 *  - **Depth from focus.** The lens is stepped from far to near and, for each area, the step at
 *    which it looks sharpest says how far away it is.
 *
 * Both are rough and only trusted where the picture has enough detail to match; everywhere else
 * Portrait falls back on the subject's outline.
 */
object PortraitDepth {
    private const val TAG = "PortraitDepth"
    private const val LONG_SIDE = 640

    /** The cues for a finished picture of [w] x [h] pixels (already cropped to [ratio]). */
    fun cues(capture: DepthCapture, pixels: IntArray, w: Int, h: Int, ratio: AspectOption, mirror: Boolean): DepthCues {
        val scale = min(1f, LONG_SIDE.toFloat() / max(w, h))
        val ww = max(16, (w * scale).roundToInt())
        val wh = max(16, (h * scale).roundToInt())
        val stereo = capture.stereo?.let { frame ->
            try {
                val work = PortraitEffect.downscale(pixels, w, h, ww, wh)
                val lum = FloatArray(ww * wh) { i ->
                    val p = work[i]
                    (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
                }
                stereo(lum, ww, wh, frame, ratio, mirror)
            } catch (e: Exception) {
                Log.w(TAG, "Stereo depth failed", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Stereo depth ran out of memory", e)
                null
            }
        }
        val focus = capture.focus?.let { stack ->
            try {
                focus(stack)
            } catch (e: Exception) {
                Log.w(TAG, "Depth from focus failed", e)
                null
            }
        }
        Log.i(TAG, "Depth cues: stereo ${stereo != null}, focus ${focus != null}")
        return DepthCues(stereo, focus)
    }

    // ---- stereo from two lenses ----

    private fun stereo(main: FloatArray, ww: Int, wh: Int, f: StereoFrame, ratio: AspectOption, mirror: Boolean): DepthPlane? {
        val turns = ((f.rotationDegrees % 360) + 360) % 360
        val upright = rotate(f.luma, f.width, f.height, turns)
        val uw = upright.width
        val uh = upright.height
        val k = f.focalWide / f.focalMain
        if (k < 0.25f || k > 0.95f) return null

        // Where the finished picture sits inside the lens's whole frame (it was cropped to the ratio).
        val fullAspect = uw.toFloat() / uh
        val target = ratio.widthOverHeight
        var cx = 0f
        var cy = 0f
        var cw = 1f
        var ch = 1f
        if (fullAspect > target) {
            cw = target / fullAspect
            cx = (1f - cw) / 2f
        } else if (fullAspect < target) {
            ch = fullAspect / target
            cy = (1f - ch) / 2f
        }

        // The wide frame cropped and enlarged to the main lens's view of the same scene.
        val wide = FloatArray(uw * uh) { (upright.data[it].toInt() and 0xFF) / 255f }
        val warped = FloatArray(ww * wh)
        parallelFor(wh) { y ->
            val b = cy + (y + 0.5f) / wh * ch
            val sy = (0.5f + k * (b - 0.5f)) * uh - 0.5f
            for (x in 0 until ww) {
                val xx = if (mirror) ww - 1 - x else x
                val a = cx + (xx + 0.5f) / ww * cw
                val sx = (0.5f + k * (a - 0.5f)) * uw - 0.5f
                warped[y * ww + x] = bilinear(wide, uw, uh, sx, sy)
            }
        }

        // Compare edges and texture, not brightness: the two lenses expose differently.
        val tmp = FloatArray(ww * wh)
        val a = highPass(main, ww, wh, tmp)
        val b = highPass(warped, ww, wh, tmp)
        var ea = 0f
        var eb = 0f
        for (i in a.indices) {
            ea += a[i] * a[i]
            eb += b[i] * b[i]
        }
        if (ea < 1e-3f || eb < 1e-3f) return null
        val gain = sqrt(ea / eb)
        for (i in b.indices) b[i] *= gain

        // The lenses' line is normally left-right in the upright picture, but if the phone reports
        // it differently try the other direction too.
        var best: DepthPlane? = null
        var bestScore = 0f
        for (horizontal in listOf(f.horizontal, !f.horizontal)) {
            val result = match(a, b, ww, wh, horizontal)
            if (result.second > bestScore) {
                bestScore = result.second
                best = result.first
            }
            if (bestScore > 0.2f) break
        }
        Log.i(TAG, "Stereo match quality %.2f".format(bestScore))
        return if (bestScore >= 0.1f) best else null
    }

    /** Disparity between [a] (main) and [b] (wide, already enlarged) along one axis, and how trustworthy the whole result is. */
    private fun match(a: FloatArray, b: FloatArray, ww: Int, wh: Int, horizontal: Boolean): Pair<DepthPlane, Float> {
        val n = ww * wh
        val range = max(6, (max(ww, wh) * 0.022f).roundToInt())
        val best = FloatArray(n) { Float.MAX_VALUE }
        val bestIdx = IntArray(n)
        val costPrev = FloatArray(n)
        val costNext = FloatArray(n)
        var previous = FloatArray(n)
        var current = FloatArray(n)
        val tmp = FloatArray(n)
        for (d in -range..range) {
            val slice = current
            parallelFor(wh) { y ->
                for (x in 0 until ww) {
                    val sx = if (horizontal) x + d else x
                    val sy = if (horizontal) y else y + d
                    val i = y * ww + x
                    slice[i] = if (sx in 0 until ww && sy in 0 until wh) abs(a[i] - b[sy * ww + sx]) else 0.08f
                }
            }
            PortraitEffect.boxBlurF(slice, tmp, ww, wh, 4)
            val before = previous
            for (i in 0 until n) {
                val c = slice[i]
                if (c < best[i]) {
                    best[i] = c
                    bestIdx[i] = d
                    costPrev[i] = if (d > -range) before[i] else c
                    costNext[i] = Float.MAX_VALUE
                } else if (bestIdx[i] == d - 1) {
                    costNext[i] = c
                }
            }
            previous = slice
            current = before
        }

        val texture = FloatArray(n) { abs(a[it]) }
        PortraitEffect.boxBlurF(texture, tmp, ww, wh, 4)
        val value = FloatArray(n)
        val conf = FloatArray(n)
        var total = 0f
        for (i in 0 until n) {
            val cb = best[i]
            val cp = costPrev[i]
            val cn = if (costNext[i] == Float.MAX_VALUE) cb else costNext[i]
            var d = bestIdx[i].toFloat()
            val denom = cp - 2f * cb + cn
            if (denom > 1e-7f) d += (0.5f * (cp - cn) / denom).coerceIn(-0.5f, 0.5f)
            // A clear dip in the cost, in an area with detail, is a trustworthy match.
            val t = texture[i]
            var c = (denom / (0.25f * t + 0.004f)).coerceIn(0f, 1f) * ((t - 0.006f) / 0.02f).coerceIn(0f, 1f)
            if (bestIdx[i] == -range || bestIdx[i] == range) c *= 0.3f
            value[i] = d
            conf[i] = c
            total += c
        }
        val quality = total / n
        return Pair(fillGaps(value, conf, ww, wh, 6), quality)
    }

    // ---- depth from focus ----

    private fun focus(stack: FocusStack): DepthPlane? {
        val w = stack.width
        val h = stack.height
        val n = w * h
        val steps = stack.luma.size
        if (steps < 3 || stack.diopters.size != steps) return null
        val tmp = FloatArray(n)
        // How much fine detail every area has in every frame.
        val sharp = Array(steps) { s ->
            val bytes = stack.luma[s]
            val y = FloatArray(n) { (bytes[it].toInt() and 0xFF) / 255f }
            PortraitEffect.boxBlurF(y, tmp, w, h, 1)
            val detail = FloatArray(n)
            for (row in 1 until h - 1) {
                for (col in 1 until w - 1) {
                    val i = row * w + col
                    detail[i] = abs(4f * y[i] - y[i - 1] - y[i + 1] - y[i - w] - y[i + w])
                }
            }
            PortraitEffect.boxBlurF(detail, tmp, w, h, 4)
            detail
        }
        val value = FloatArray(n)
        val conf = FloatArray(n)
        for (i in 0 until n) {
            var bestStep = 0
            var top = -1f
            var sum = 0f
            for (s in 0 until steps) {
                val v = sharp[s][i]
                sum += v
                if (v > top) {
                    top = v
                    bestStep = s
                }
            }
            var pos = bestStep.toFloat()
            if (bestStep in 1 until steps - 1) {
                val before = sharp[bestStep - 1][i]
                val after = sharp[bestStep + 1][i]
                val denom = before - 2f * top + after
                if (denom < -1e-9f) pos += (0.5f * (before - after) / denom).coerceIn(-0.5f, 0.5f)
            }
            val lower = pos.toInt().coerceIn(0, steps - 1)
            val upper = min(lower + 1, steps - 1)
            val t = pos - lower
            value[i] = stack.diopters[lower] * (1f - t) + stack.diopters[upper] * t
            val others = (sum - top) / (steps - 1)
            val peak = ((top - others) / (top + 1e-4f) * 1.6f).coerceIn(0f, 1f)
            val detail = ((top - 0.004f) / 0.012f).coerceIn(0f, 1f)
            conf[i] = PortraitEffect.smooth(peak) * detail
        }
        return fillGaps(value, conf, w, h, 5)
    }

    // ---- helpers ----

    /** Smooths [value] using only its trustworthy pixels, so areas with no detail take on their neighbours' depth. The returned confidence is how much trustworthy data was nearby. */
    private fun fillGaps(value: FloatArray, conf: FloatArray, w: Int, h: Int, radius: Int): DepthPlane {
        val n = w * h
        val tmp = FloatArray(n)
        val num = FloatArray(n) { value[it] * conf[it] }
        val den = conf.copyOf()
        repeat(2) {
            PortraitEffect.boxBlurF(num, tmp, w, h, radius)
            PortraitEffect.boxBlurF(den, tmp, w, h, radius)
        }
        val filled = FloatArray(n) { if (den[it] > 1e-4f) num[it] / den[it] else 0f }
        val support = FloatArray(n) { (den[it] * 4f).coerceIn(0f, 1f) }
        return DepthPlane(w, h, filled, support)
    }

    private fun highPass(src: FloatArray, w: Int, h: Int, tmp: FloatArray): FloatArray {
        val blur = src.copyOf()
        PortraitEffect.boxBlurF(blur, tmp, w, h, 6)
        return FloatArray(src.size) { src[it] - blur[it] }
    }

    private class Luma(val data: ByteArray, val width: Int, val height: Int)

    /** Rotates a brightness image clockwise by [degrees] (a multiple of 90). */
    private fun rotate(src: ByteArray, w: Int, h: Int, degrees: Int): Luma = when (degrees) {
        90 -> {
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
            Luma(out, h, w)
        }
        180 -> {
            val out = ByteArray(w * h)
            for (i in 0 until w * h) out[w * h - 1 - i] = src[i]
            Luma(out, w, h)
        }
        270 -> {
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = src[y * w + x]
            Luma(out, h, w)
        }
        else -> Luma(src, w, h)
    }

    private fun bilinear(src: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val fx = x.coerceIn(0f, w - 1f)
        val fy = y.coerceIn(0f, h - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        return (src[y0 * w + x0] * (1 - tx) + src[y0 * w + x1] * tx) * (1 - ty) +
            (src[y1 * w + x0] * (1 - tx) + src[y1 * w + x1] * tx) * ty
    }
}
