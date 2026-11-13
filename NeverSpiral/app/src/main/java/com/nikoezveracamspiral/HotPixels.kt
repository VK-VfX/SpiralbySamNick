package com.nikoezveracamspiral

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One bright speck found in an image: where (pixels and 0..1 of the frame) and how bright. */
class Spot(val xPx: Int, val yPx: Int, val x: Float, val y: Float, val peak: Int, val contrast: Int)

/**
 * Finds hot pixels: single points that are far brighter than everything around them. In a dark test
 * frame that is the sensor's defects; in an ordinary photo it is stray specks in dark areas (which
 * can also be real things like stars, so the editor only suggests them).
 */
object HotPixels {
    private val RING = arrayOf(
        intArrayOf(4, 0), intArrayOf(-4, 0), intArrayOf(0, 4), intArrayOf(0, -4),
        intArrayOf(3, 3), intArrayOf(-3, 3), intArrayOf(3, -3), intArrayOf(-3, -3),
    )

    /**
     * @param luma brightness 0..255 per pixel, row by row.
     * @param minPeak the speck must be at least this bright.
     * @param minContrast and at least this much brighter than the middle of the ring around it.
     * @param maxRing the ring around it must itself be darker than this (so bright objects don't count).
     */
    fun find(luma: ByteArray, w: Int, h: Int, minPeak: Int, minContrast: Int, maxRing: Int, limit: Int = 200): List<Spot> {
        if (w < 16 || h < 16) return emptyList()
        val found = ArrayList<Spot>()
        val ring = IntArray(8)
        for (y in 4 until h - 4) {
            val row = y * w
            for (x in 4 until w - 4) {
                val v = luma[row + x].toInt() and 0xFF
                if (v < minPeak) continue
                // Only the brightest point of its 5x5 neighbourhood can be the speck.
                var isMax = true
                loop@ for (dy in -2..2) {
                    val r2 = (y + dy) * w + x
                    for (dx in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        val n = luma[r2 + dx].toInt() and 0xFF
                        if (n > v || (n == v && (dy < 0 || (dy == 0 && dx < 0)))) {
                            isMax = false
                            break@loop
                        }
                    }
                }
                if (!isMax) continue
                for (i in 0 until 8) ring[i] = luma[(y + RING[i][1]) * w + x + RING[i][0]].toInt() and 0xFF
                ring.sort()
                val median = (ring[3] + ring[4]) / 2
                if (median > maxRing || v - median < minContrast) continue
                found.add(Spot(x, y, x.toFloat() / (w - 1), y.toFloat() / (h - 1), v, v - median))
            }
        }
        found.sortByDescending { it.peak }
        return if (found.size > limit) found.subList(0, limit).toList() else found
    }

    /** The specks that show up in at least [minFrames] of the frames at (nearly) the same place: real sensor defects, not noise. */
    fun consistent(frames: List<List<Spot>>, minFrames: Int, tolerancePx: Int = 3): List<Spot> {
        if (frames.isEmpty()) return emptyList()
        val clusters = ArrayList<MutableList<Spot>>()
        for (frame in frames) {
            val used = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MutableList<Spot>, Boolean>())
            for (s in frame) {
                val c = clusters.firstOrNull { cl ->
                    cl !in used && abs(cl[0].xPx - s.xPx) <= tolerancePx && abs(cl[0].yPx - s.yPx) <= tolerancePx
                }
                if (c != null) {
                    c.add(s)
                    used.add(c)
                } else {
                    val fresh = mutableListOf(s)
                    clusters.add(fresh)
                    used.add(fresh)
                }
            }
        }
        return clusters
            .filter { it.size >= minFrames }
            .map { cl ->
                val best = cl.maxByOrNull { it.peak }!!
                Spot(best.xPx, best.yPx, best.x, best.y, cl.sumOf { it.peak } / cl.size, cl.sumOf { it.contrast } / cl.size)
            }
            .sortedByDescending { it.peak }
    }
}

/** Painting a hot pixel out of a picture: the spot is replaced by the middle colour of the ring around it. */
object SpotHeal {
    private fun radiusFor(w: Int, h: Int): Float = max(3f, max(w, h) * 0.0035f)

    /** Heals [spots] (0..1 positions) in [pixels] (ARGB, row by row), in place. */
    fun heal(pixels: IntArray, w: Int, h: Int, spots: List<Pair<Float, Float>>) {
        if (spots.isEmpty()) return
        val r = radiusFor(w, h)
        val outer = r * 1.8f
        val inner = r * 1.15f
        val cap = ((2 * outer + 3) * (2 * outer + 3)).toInt()
        val rs = IntArray(cap)
        val gs = IntArray(cap)
        val bs = IntArray(cap)
        for ((nx, ny) in spots) {
            val cx = nx.coerceIn(0f, 1f) * (w - 1)
            val cy = ny.coerceIn(0f, 1f) * (h - 1)
            val x0 = max(0, floor(cx - outer).toInt())
            val x1 = min(w - 1, floor(cx + outer).toInt() + 1)
            val y0 = max(0, floor(cy - outer).toInt())
            val y1 = min(h - 1, floor(cy + outer).toInt() + 1)
            var n = 0
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < inner || d > outer || n >= cap) continue
                val p = pixels[y * w + x]
                rs[n] = (p shr 16) and 0xFF
                gs[n] = (p shr 8) and 0xFF
                bs[n] = p and 0xFF
                n++
            }
            if (n < 8) continue
            java.util.Arrays.sort(rs, 0, n)
            java.util.Arrays.sort(gs, 0, n)
            java.util.Arrays.sort(bs, 0, n)
            val mr = rs[n / 2]
            val mg = gs[n / 2]
            val mb = bs[n / 2]
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d > r) continue
                val a = if (d <= r * 0.7f) 1f else ((r - d) / (r * 0.3f)).coerceIn(0f, 1f)
                val i = y * w + x
                val p = pixels[i]
                val nr = (((p shr 16) and 0xFF) * (1 - a) + mr * a).toInt()
                val ng = (((p shr 8) and 0xFF) * (1 - a) + mg * a).toInt()
                val nb = ((p and 0xFF) * (1 - a) + mb * a).toInt()
                pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /** Moves a tap to the brightest point close to it (a finger is far less exact than a pixel). Returns the tap itself if nothing stands out. */
    fun snap(pixels: IntArray, w: Int, h: Int, nx: Float, ny: Float): Pair<Float, Float> {
        val reach = max(4, (max(w, h) * 0.012f).toInt())
        val cx = (nx * (w - 1)).toInt()
        val cy = (ny * (h - 1)).toInt()
        var best = -1
        var bx = cx
        var by = cy
        var sum = 0L
        var count = 0
        for (y in max(0, cy - reach)..min(h - 1, cy + reach)) for (x in max(0, cx - reach)..min(w - 1, cx + reach)) {
            val p = pixels[y * w + x]
            val l = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            sum += l
            count++
            if (l > best) {
                best = l
                bx = x
                by = y
            }
        }
        if (count == 0 || best - sum / count < 20) return Pair(nx, ny)
        return Pair(bx.toFloat() / (w - 1), by.toFloat() / (h - 1))
    }
}
