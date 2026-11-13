package com.nikoezveracamspiral

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Merges several RAW frames of the same scene into one cleaner RAW, before any colour processing.
 *
 * Averaging N frames cuts noise by about the square root of N, and doing it on the RAW data
 * (10-bit linear sensor values) rather than on finished JPEGs keeps all of that headroom for the
 * shadows. The frames are aligned with a global shift that is always a whole number of 2x2 quads,
 * so the colour filter pattern still lines up; each pixel then averages only the frames that agree
 * with the reference there (using the sensor's noise model to decide what "agree" means), which
 * leaves anything that moved untouched instead of ghosting it.
 */
object RawMerge {
    /** The merged RAW as 16-bit samples, row by row with no padding. */
    class Merged(val width: Int, val height: Int, val samples: ShortArray, val framesUsed: Int)

    private class Plane(image: Image) {
        val width = image.width
        val height = image.height
        val rowShorts: Int
        val pixelStride: Int
        val buffer: ShortBuffer

        init {
            val plane = image.planes[0]
            pixelStride = plane.pixelStride
            rowShorts = plane.rowStride / 2
            buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }

        fun at(x: Int, y: Int): Int = buffer.get(y * rowShorts + x).toInt() and 0xFFFF
    }

    /** Returns null when the frames can't be merged (odd layout), so the caller keeps one RAW. */
    fun merge(
        images: List<Image>,
        results: List<CaptureResult>,
        chars: CameraCharacteristics,
        refIndex: Int,
    ): Merged? {
        if (images.size < 2 || images.size != results.size) return null
        val planes = images.map { Plane(it) }
        val w = planes[0].width
        val h = planes[0].height
        if (planes.any { it.width != w || it.height != h || it.pixelStride != 2 }) return null
        if (w % 8 != 0 || h % 8 != 0) return null
        val ref = refIndex.coerceIn(0, images.size - 1)

        val white = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).coerceIn(255, 65535)
        val black = blackLevel(chars, results[ref]).coerceIn(0f, white / 2f)
        val range = (white - black).coerceAtLeast(1f)

        // Exposure of every frame relative to the reference, so an HDR bracket (or any frames with
        // different shutter/ISO) is brought to the same brightness before comparing.
        fun exposureOf(r: CaptureResult): Double {
            val t = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return 1.0
            val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return 1.0
            return t.toDouble() * iso
        }
        val refExposure = exposureOf(results[ref])
        val gains = FloatArray(images.size) { i ->
            val e = exposureOf(results[i])
            if (e <= 0.0 || refExposure <= 0.0) 1f else (refExposure / e).toFloat().coerceIn(0.25f, 4f)
        }

        // Where each frame sits relative to the reference, in raw pixels (always even).
        val refProxy = Proxy.build(planes[ref], black, gains[ref])
        val shiftX = IntArray(images.size)
        val shiftY = IntArray(images.size)
        for (i in images.indices) {
            if (i == ref) continue
            val proxy = Proxy.build(planes[i], black, gains[i])
            val (dx, dy) = Proxy.align(refProxy, proxy)
            shiftX[i] = dx
            shiftY[i] = dy
        }

        // Noise model: variance = S * signal + O, in units of the normalised signal.
        val profile = results[ref].get(CaptureResult.SENSOR_NOISE_PROFILE)
        var s = 0.0
        var o = 0.0
        if (profile != null && profile.isNotEmpty()) {
            for (pair in profile) {
                s += pair.first
                o += pair.second
            }
            s /= profile.size
            o /= profile.size
        } else {
            s = 0.0004
            o = 0.000002
        }
        // How far from the reference a sample may be and still count as the same scene content.
        val threshold = IntArray(white + 1) { v ->
            val signal = ((v - black) / range).coerceIn(0f, 1f)
            val sigma = sqrt(2.0 * (s * signal + o)) * range
            (3.5 * sigma + 4.0).roundToInt().coerceIn(4, white / 4)
        }

        val out = ShortArray(w * h)
        val refPlane = planes[ref]
        val others = images.indices.filter { it != ref }
        parallelFor(h) { y ->
            val base = y * w
            for (x in 0 until w) {
                val r = refPlane.at(x, y)
                var sum = r.toFloat()
                var count = 1
                val limit = threshold[r.coerceAtMost(white)]
                for (i in others) {
                    val sx = x + shiftX[i]
                    val sy = y + shiftY[i]
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                    val raw = planes[i].at(sx, sy)
                    val v = if (gains[i] == 1f) raw.toFloat() else (raw - black) * gains[i] + black
                    if (abs(v - r) <= limit) {
                        sum += v
                        count++
                    }
                }
                out[base + x] = (sum / count).roundToInt().coerceIn(0, 65535).toShort()
            }
        }
        return Merged(w, h, out, images.size)
    }

    private fun blackLevel(chars: CameraCharacteristics, result: CaptureResult): Float {
        val dynamic = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        if (dynamic != null && dynamic.isNotEmpty()) return dynamic.average().toFloat()
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (pattern != null) {
            var sum = 0f
            for (c in 0 until 2) for (r in 0 until 2) sum += pattern.getOffsetForIndex(c, r)
            return sum / 4f
        }
        return 64f
    }

    /** Small brightness maps of a frame, used only to find how far it moved. */
    private class Proxy(val quad: IntArray, val qw: Int, val qh: Int, val coarse: IntArray, val cw: Int, val ch: Int) {
        companion object {
            /** One value per 2x2 quad (the sum of its four samples), then one per 4x4 quads. */
            fun build(p: Plane, black: Float, gain: Float): Proxy {
                val qw = p.width / 2
                val qh = p.height / 2
                val quad = IntArray(qw * qh)
                val blackQuad = (black * 4f).toInt()
                parallelFor(qh) { qy ->
                    for (qx in 0 until qw) {
                        val x = qx * 2
                        val y = qy * 2
                        val sum = p.at(x, y) + p.at(x + 1, y) + p.at(x, y + 1) + p.at(x + 1, y + 1)
                        quad[qy * qw + qx] = (((sum - blackQuad) * gain)).toInt()
                    }
                }
                val cw = qw / 4
                val ch = qh / 4
                val coarse = IntArray(cw * ch)
                parallelFor(ch) { cy ->
                    for (cx in 0 until cw) {
                        var sum = 0L
                        for (dy in 0 until 4) for (dx in 0 until 4) sum += quad[(cy * 4 + dy) * qw + cx * 4 + dx]
                        coarse[cy * cw + cx] = (sum / 16).toInt()
                    }
                }
                return Proxy(quad, qw, qh, coarse, cw, ch)
            }

            /** The (dx, dy) in raw pixels (even) that lines [cur] up with [ref]: coarse search over
             * a wide window, then a fine search around the coarse answer. */
            fun align(ref: Proxy, cur: Proxy): Pair<Int, Int> {
                val coarseRadius = 8
                val (cdx, cdy) = search(ref.coarse, cur.coarse, ref.cw, ref.ch, coarseRadius, 0, 0, 1)
                // A coarse pixel is 4 quads; start the fine search at the matching quad offset.
                val (fdx, fdy) = search(ref.quad, cur.quad, ref.qw, ref.qh, 4, cdx * 4, cdy * 4, 3)
                return Pair(fdx * 2, fdy * 2)
            }

            /** Exhaustive search for the shift minimising the mean absolute difference, comparing
             * every [step]th sample. */
            private fun search(
                a: IntArray, b: IntArray, w: Int, h: Int, radius: Int, centreX: Int, centreY: Int, step: Int,
            ): Pair<Int, Int> {
                val margin = radius + 2 + maxOf(abs(centreX), abs(centreY))
                val x0 = margin.coerceAtMost(w / 4)
                val y0 = margin.coerceAtMost(h / 4)
                val x1 = w - x0
                val y1 = h - y0
                if (x1 <= x0 || y1 <= y0) return Pair(0, 0)
                var bestX = 0
                var bestY = 0
                var best = Long.MAX_VALUE
                val strideStep = if (step <= 1) 1 else step * 2
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val ox = centreX + dx
                        val oy = centreY + dy
                        var sum = 0L
                        var n = 0
                        var y = y0
                        while (y < y1) {
                            if (y + oy in 0 until h) {
                                var x = x0
                                val rowA = y * w
                                val rowB = (y + oy) * w
                                while (x < x1) {
                                    val xb = x + ox
                                    if (xb in 0 until w) {
                                        sum += abs(a[rowA + x] - b[rowB + xb]).toLong()
                                        n++
                                    }
                                    x += strideStep
                                }
                            }
                            y += strideStep
                        }
                        if (n > 0) {
                            // Prefer small moves on ties: a handheld burst rarely moves far.
                            val score = sum / n + (abs(ox) + abs(oy))
                            if (score < best) {
                                best = score
                                bestX = ox
                                bestY = oy
                            }
                        }
                    }
                }
                return Pair(bestX, bestY)
            }
        }
    }
}
