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

    /** What a renderer hands to [composite]: the blurred picture at working size, and for every
     * pixel how much of it to use (0 = keep the sharp original, 1 = fully the blurred one). */
    private class Render(val image: IntArray, val weight: FloatArray)

    /** Returns true if a subject was found and the background was blurred. [strength] is 0..1. */
    fun apply(pixels: IntArray, w: Int, h: Int, strength: Float, style: PortraitStyle = PortraitStyle.NATURAL, cues: DepthCues? = null, tap: Pair<Float, Float>? = null): Boolean {
        val params = paramsFor(style)
        val longSide = max(w, h)
        val scale = min(1f, 1024f / longSide)
        val ww = max(16, (w * scale).roundToInt())
        val wh = max(16, (h * scale).roundToInt())
        val n = ww * wh
        val work = downscale(pixels, w, h, ww, wh)
        val lum = FloatArray(n) { i ->
            val p = work[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        val strengthFactor = 0.2f + 0.8f * strength.coerceIn(0f, 1f)
        val maxRadius = (params.radius * longSide * strengthFactor * (ww.toFloat() / w)).coerceAtLeast(2f)

        // Depth from the camera's own measurements plus the subject's outline; the plain
        // outline blur is the last resort.
        val render = depthRender(work, lum, ww, wh, params, maxRadius, cues, tap)
            ?: maskRender(work, lum, ww, wh, params, maxRadius, tap)
            ?: return false
        composite(pixels, w, h, render, ww, wh, params.subjectLift)
        return true
    }

    /**
     * Per-pixel gain that makes small bright points (lights, reflections) swell before they are
     * blurred into soft discs, while big bright surfaces such as a sheet of paper are left alone
     * (boosting those is what turned them into white blobs). Null when the style has no bloom.
     */
    private fun bloomGain(lum: FloatArray, ww: Int, wh: Int, radius: Float, bloom: Boolean): FloatArray? {
        if (!bloom) return null
        val local = lum.copyOf()
        val tmp = FloatArray(lum.size)
        boxBlurF(local, tmp, ww, wh, max(3, (radius * 1.5f).roundToInt()))
        return FloatArray(lum.size) { i ->
            val excess = lum[i] - local[i]
            if (lum[i] > 0.7f && excess > 0.08f) 1f + 0.9f * ((excess - 0.08f) / 0.3f).coerceIn(0f, 1f) else 1f
        }
    }

    // ---- depth renderer ----

    /**
     * Simulates a large-aperture lens from a depth map built out of what the camera can measure
     * itself: the subject's outline (a distance ramp away from it), the parallax between the main
     * and ultra-wide lenses, and how sharp each area comes out as the lens focuses near and far
     * ([cues]). Every pixel gets a blur size from how far its depth is from the focus plane (in
     * front or behind), the picture is split into layers by blur size, each layer is blurred on its
     * own, and the layers are stacked far to near. A sharp subject therefore can't bleed into the
     * blur behind it (no halo), and a blurred foreground can soften what is behind it.
     */
    private fun depthRender(work: IntArray, lum: FloatArray, ww: Int, wh: Int, params: Params, maxRadius: Float, cues: DepthCues?, tap: Pair<Float, Float>? = null): Render? {
        val n = ww * wh

        // The subject: a person if the model finds one, otherwise whatever is in focus.
        val subject = findSubject(work, lum, ww, wh, tap)

        // The outline cue: depth falls away smoothly with distance from the subject's edge.
        val ramp = FloatArray(n) { 1f }
        val subjectDistance: FloatArray? = if (subject != null) distanceFromSubject(subject, ww, wh) else null
        if (subjectDistance != null) {
            val reach = 0.16f * max(ww, wh)
            for (i in 0 until n) ramp[i] = 1f - 0.75f * smooth((subjectDistance[i] / reach).coerceIn(0f, 1f))
        }

        // The measured cues, each brought to 0..1 (larger = nearer) at working size.
        val stereo = cues?.stereo?.let { normalisedCue(it, ww, wh) }
        val focusCue = cues?.focus?.let { normalisedCue(it, ww, wh) }
        if (stereo != null) {
            // Which way is "near" along the baseline isn't known up front; the sharpness cue or, failing
            // that, the fact that the subject is nearer than its surroundings settles it.
            var score = 0f
            if (focusCue != null) {
                for (i in 0 until n) score += stereo.second[i] * focusCue.second[i] * (stereo.first[i] - 0.5f) * (focusCue.first[i] - 0.5f)
            } else if (subject != null) {
                var inside = 0f
                var insideN = 0f
                var outside = 0f
                var outsideN = 0f
                for (i in 0 until n) {
                    val c = stereo.second[i]
                    if (subject[i] > 0.7f) { inside += stereo.first[i] * c; insideN += c }
                    else if (subject[i] < 0.2f) { outside += stereo.first[i] * c; outsideN += c }
                }
                if (insideN > 1f && outsideN > 1f) score = inside / insideN - outside / outsideN
            }
            if (score < 0f) for (i in 0 until n) stereo.first[i] = 1f - stereo.first[i]
        }
        val hasCues = stereo != null || focusCue != null
        val cueDepth = FloatArray(n)
        val cueConf = FloatArray(n)
        if (hasCues) {
            for (i in 0 until n) {
                val sw = if (stereo != null) 0.8f * stereo.second[i] else 0f
                val fw = if (focusCue != null) 1.0f * focusCue.second[i] else 0f
                val total = sw + fw
                if (total > 1e-4f) {
                    cueDepth[i] = ((if (stereo != null) sw * stereo.first[i] else 0f) + (if (focusCue != null) fw * focusCue.first[i] else 0f)) / total
                    cueConf[i] = min(1f, total)
                }
            }
        }
        if (!hasCues && subject == null) return null

        // Depth: the measured cues where they are trustworthy, the outline ramp everywhere as a
        // gentle prior, then snapped to the picture's own edges.
        var depth = FloatArray(n)
        val rampWeight = if (hasCues) 0.6f else 1f
        for (i in 0 until n) depth[i] = (cueDepth[i] * cueConf[i] + ramp[i] * rampWeight) / (cueConf[i] + rampWeight)
        depth = guidedFilter(lum, depth, ww, wh, max(4, max(ww, wh) / 60), 0.002f)

        // Focus on the subject's depth, or on the middle of the frame.
        val hist = IntArray(256)
        var total = 0
        val seedMask = subject
        if (seedMask != null) {
            for (i in 0 until n) if (seedMask[i] > 0.6f) {
                hist[(depth[i] * 255f).toInt().coerceIn(0, 255)]++
                total++
            }
        }
        if (total < n / 200) {
            hist.fill(0)
            total = 0
            for (y in (wh * 0.4f).toInt() until (wh * 0.6f).toInt()) {
                for (x in (ww * 0.4f).toInt() until (ww * 0.6f).toInt()) {
                    hist[(depth[y * ww + x] * 255f).toInt().coerceIn(0, 255)]++
                    total++
                }
            }
        }
        if (total == 0) return null
        var seen = 0
        var bin = 0
        while (bin < 255 && seen + hist[bin] < total / 2) {
            seen += hist[bin]
            bin++
        }
        val focus = (bin + 0.5f) / 255f

        // The whole subject, not just the part the model outlines: grow outward from the subject
        // across connected pixels the measured depth says are at about the same distance (fingers,
        // a held object, a collar), the way Google's portrait keeps everything attached to the
        // subject crisp. Anything not connected (a pillow beside the hand) stays background even
        // at a similar depth. With no measured depth the outline alone is the subject.
        val band = 0.11f
        val stepLimit = 0.05f
        val region = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        if (seedMask != null) {
            for (i in 0 until n) if (seedMask[i] > 0.6f) {
                region[i] = true
                queue[tail++] = i
            }
        }
        if (tail == 0) {
            for (y in (wh * 0.42f).toInt() until (wh * 0.58f).toInt()) {
                for (x in (ww * 0.42f).toInt() until (ww * 0.58f).toInt()) {
                    region[y * ww + x] = true
                    queue[tail++] = y * ww + x
                }
            }
        }
        var focusCueDepth = -1f
        if (hasCues) {
            val values = ArrayList<Float>()
            for (k in 0 until tail) {
                val i = queue[k]
                if (cueConf[i] > 0.35f) values.add(cueDepth[i])
            }
            if (values.size > n / 400) {
                values.sort()
                focusCueDepth = values[values.size / 2]
            }
        }
        val reachLimit = 0.03f * max(ww, wh)
        val seedCount = tail
        if (focusCueDepth >= 0f) {
            while (head < tail) {
                val i = queue[head++]
                val x = i % ww
                val y = i / ww
                fun grow(j: Int) {
                    // Only things close to the subject can belong to it (a held object, fingers), so
                    // noisy depth can never flood the region out into the background.
                    if (!region[j] && cueConf[j] > 0.5f && kotlin.math.abs(cueDepth[j] - focusCueDepth) < band &&
                        kotlin.math.abs(cueDepth[j] - cueDepth[i]) < stepLimit &&
                        (subjectDistance == null || subjectDistance[j] < reachLimit)
                    ) {
                        region[j] = true
                        queue[tail++] = j
                    }
                }
                if (x > 0) grow(i - 1)
                if (x < ww - 1) grow(i + 1)
                if (y > 0) grow(i - ww)
                if (y < wh - 1) grow(i + ww)
            }
        }
        // If growth ran away anyway (far more than the subject itself), distrust it and keep the outline.
        if (tail > max(seedCount * 3, n / 3) && seedCount > 0) {
            region.fill(false)
            for (k in 0 until seedCount) region[queue[k]] = true
        }
        val grown = FloatArray(n) { if (region[it]) 1f else 0f }
        // Snap the grown region to the picture's own edges, and keep the subject's own shape too.
        val regionMask = refine(lum, grown, ww, wh)
        // Nothing may stay sharp unless it is joined to the main subject: stray islands are dropped.
        val keep = cleanMask(FloatArray(n) { i -> max(regionMask[i], subject?.get(i) ?: 0f) }, ww, wh)
        val bloom = bloomGain(lum, ww, wh, maxRadius, params.bloom)

        // Blur size per pixel (0..1), and whether it is in front of or behind the focus plane.
        // Behind the subject there is always a clear minimum blur (so nothing back there stays
        // crisp) that deepens with distance, as Apple's portrait does; in front of the subject the
        // blur is much lighter, as Google's is.
        val coc = FloatArray(n)
        val inFront = BooleanArray(n)
        var sharp = 0
        for (i in 0 until n) {
            val s = depth[i] - focus
            val ramp = smooth(((kotlin.math.abs(s) - 0.04f) / 0.5f).coerceIn(0f, 1f))
            // Only something clearly and confidently in front of the subject gets the light blur;
            // everything else outside the subject is background, however noisy the depth there is.
            val front = s > 0.22f && cueConf[i] > 0.6f
            val c = if (front) 0.3f * ramp else 0.38f + 0.62f * ramp
            coc[i] = c * (1f - keep[i])
            inFront[i] = front && keep[i] < 0.5f
            if (coc[i] < 0.05f) sharp++
        }
        val sharpShare = sharp.toFloat() / n
        if (sharpShare < 0.03f || sharpShare > 0.97f) return null

        // Layers: the in-focus layer plus [steps] blur sizes on each side, stacked far to near.
        val steps = 3
        val upper = FloatArray(n)
        val lowerStep = IntArray(n)
        for (i in 0 until n) {
            val u = coc[i] * steps
            val j0 = min(u.toInt(), steps - 1)
            lowerStep[i] = j0
            upper[i] = u - j0
        }
        val tmp = FloatArray(n)
        val accR = FloatArray(n)
        val accG = FloatArray(n)
        val accB = FloatArray(n)
        for (i in 0 until n) {
            val p = work[i]
            accR[i] = ((p shr 16) and 0xFF).toFloat()
            accG[i] = ((p shr 8) and 0xFF).toFloat()
            accB[i] = (p and 0xFF).toFloat()
        }
        // Underneath everything: the whole picture at the widest blur, so no gap ever shows.
        val baseRadius = maxRadius.roundToInt().coerceAtLeast(1)
        repeat(3) {
            boxBlurF(accR, tmp, ww, wh, baseRadius)
            boxBlurF(accG, tmp, ww, wh, baseRadius)
            boxBlurF(accB, tmp, ww, wh, baseRadius)
        }
        val layerR = FloatArray(n)
        val layerG = FloatArray(n)
        val layerB = FloatArray(n)
        val layerA = FloatArray(n)

        fun addLayer(front: Boolean, step: Int) {
            var used = 0
            for (i in 0 until n) {
                val j0 = lowerStep[i]
                val t = upper[i]
                var wgt = 0f
                if (step == 0) {
                    if (j0 == 0) wgt = 1f - t
                } else if (inFront[i] == front) {
                    if (j0 == step) wgt += 1f - t
                    if (j0 + 1 == step) wgt += t
                }
                if (wgt > 0f) {
                    used++
                    val p = work[i]
                    var r = ((p shr 16) and 0xFF).toFloat()
                    var g = ((p shr 8) and 0xFF).toFloat()
                    var b = (p and 0xFF).toFloat()
                    if (bloom != null && step > 0) {
                        // Bright lights swell before they blur, so they melt into soft discs.
                        val boost = bloom[i]
                        r = min(255f, r * boost); g = min(255f, g * boost); b = min(255f, b * boost)
                    }
                    layerR[i] = r * wgt
                    layerG[i] = g * wgt
                    layerB[i] = b * wgt
                    layerA[i] = wgt
                } else {
                    layerR[i] = 0f
                    layerG[i] = 0f
                    layerB[i] = 0f
                    layerA[i] = 0f
                }
            }
            if (used == 0) return
            val radius = (maxRadius * step / steps).roundToInt()
            if (radius >= 1) {
                repeat(3) {
                    boxBlurF(layerR, tmp, ww, wh, radius)
                    boxBlurF(layerG, tmp, ww, wh, radius)
                    boxBlurF(layerB, tmp, ww, wh, radius)
                    boxBlurF(layerA, tmp, ww, wh, radius)
                }
            }
            for (i in 0 until n) {
                val a = layerA[i].coerceIn(0f, 1f)
                accR[i] = accR[i] * (1f - a) + layerR[i]
                accG[i] = accG[i] * (1f - a) + layerG[i]
                accB[i] = accB[i] * (1f - a) + layerB[i]
            }
        }
        for (step in steps downTo 1) addLayer(false, step)
        addLayer(false, 0)
        for (step in 1..steps) addLayer(true, step)

        // Style tint, only where the picture is actually blurred.
        val image = IntArray(n)
        val weight = FloatArray(n)
        for (i in 0 until n) {
            var r = accR[i]
            var g = accG[i]
            var b = accB[i]
            val tint = smooth((coc[i] * 4f).coerceIn(0f, 1f))
            if (params.mono) {
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                r += (gray - r) * tint; g += (gray - g) * tint; b += (gray - b) * tint
            }
            val gain = 1f + (params.backgroundGain - 1f) * tint
            image[i] = (0xFF shl 24) or
                ((r * gain).toInt().coerceIn(0, 255) shl 16) or
                ((g * gain).toInt().coerceIn(0, 255) shl 8) or
                (b * gain).toInt().coerceIn(0, 255)
            weight[i] = smooth((coc[i] / 0.1f).coerceIn(0f, 1f))
        }
        return Render(image, weight)
    }

    /**
     * The subject as a soft mask, or null if none can be found. With no [tap] it is a person if the
     * model finds one, otherwise the in-focus area of the frame. With a [tap] (normalised 0..1, where
     * the user touched the viewfinder) it is the person under the finger, or else the in-focus object
     * around that spot.
     */
    private fun findSubject(work: IntArray, lum: FloatArray, ww: Int, wh: Int, tap: Pair<Float, Float>?): FloatArray? {
        val tx = tap?.let { (it.first * (ww - 1)).roundToInt().coerceIn(0, ww - 1) } ?: 0
        val ty = tap?.let { (it.second * (wh - 1)).roundToInt().coerceIn(0, wh - 1) } ?: 0
        val reach = max(3, (0.03f * max(ww, wh)).roundToInt())
        val person = personMask(work, ww, wh)?.let { cleanMask(it, ww, wh) }
        if (person != null) {
            val onPerson = tap == null || componentAt(person, ww, wh, tx, ty, reach) != null
            if (onPerson) return cleanMask(refine(lum, person, ww, wh), ww, wh)
        }
        val focus = if (tap != null) focusMask(lum, ww, wh, tap.first, tap.second) else focusMask(lum, ww, wh)
        val rough = when {
            focus == null -> null
            tap != null -> componentAt(focus, ww, wh, tx, ty, reach)
            else -> cleanMask(focus, ww, wh)
        }
        val chosen = rough ?: if (tap != null) {
            // Nothing stands out there: take a small patch around the touch so the tap still sets the subject.
            val radius = 0.05f * max(ww, wh)
            FloatArray(ww * wh) { i ->
                val dx = (i % ww) - tx
                val dy = (i / ww) - ty
                if (dx * dx + dy * dy <= radius * radius) 1f else 0f
            }
        } else null
        return chosen?.let { cleanMask(refine(lum, it, ww, wh), ww, wh) }
    }

    /** The piece of [mask] (above 0.5) that touches the point (x, y) or lies within [reach] pixels of it, or null if there is none. */
    private fun componentAt(mask: FloatArray, w: Int, h: Int, x: Int, y: Int, reach: Int): FloatArray? {
        var start = -1
        var best = Int.MAX_VALUE
        for (yy in max(0, y - reach)..min(h - 1, y + reach)) {
            for (xx in max(0, x - reach)..min(w - 1, x + reach)) {
                if (mask[yy * w + xx] > 0.5f) {
                    val d = (xx - x) * (xx - x) + (yy - y) * (yy - y)
                    if (d < best) { best = d; start = yy * w + xx }
                }
            }
        }
        if (start < 0) return null
        val out = FloatArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = start
        out[start] = mask[start]
        while (head < tail) {
            val i = queue[head++]
            val cx = i % w
            val cy = i / w
            fun visit(j: Int) {
                if (out[j] == 0f && mask[j] > 0.5f) {
                    out[j] = mask[j]
                    queue[tail++] = j
                }
            }
            if (cx > 0) visit(i - 1)
            if (cx < w - 1) visit(i + 1)
            if (cy > 0) visit(i - w)
            if (cy < h - 1) visit(i + w)
        }
        return out
    }

    /** A depth cue resized to the working size and spread over 0..1 (first = value, second = confidence), or null if it is too weak to use. */
    private fun normalisedCue(plane: DepthPlane, ww: Int, wh: Int): Pair<FloatArray, FloatArray>? {
        val n = ww * wh
        val value = resize(plane.value, plane.w, plane.h, ww, wh)
        val conf = resize(plane.conf, plane.w, plane.h, ww, wh)
        var sum = 0f
        for (c in conf) sum += c
        if (sum / n < 0.08f) return null
        val samples = ArrayList<Float>()
        for (i in 0 until n step 7) if (conf[i] > 0.3f) samples.add(value[i])
        if (samples.size < 50) return null
        samples.sort()
        val low = samples[(samples.size * 0.05f).toInt()]
        val high = samples[min(samples.size - 1, (samples.size * 0.95f).toInt())]
        if (high - low < 1e-5f) return null
        for (i in 0 until n) value[i] = ((value[i] - low) / (high - low)).coerceIn(0f, 1f)
        return Pair(value, conf)
    }

    // ---- outline renderer (the last resort when no depth can be built) ----

    private fun maskRender(work: IntArray, lum: FloatArray, ww: Int, wh: Int, params: Params, maxRadius: Float, tap: Pair<Float, Float>? = null): Render? {
        val n = ww * wh
        // The subject, snapped to the picture's real edges.
        val mask = findSubject(work, lum, ww, wh, tap) ?: return null
        var coverage = 0f
        for (m in mask) if (m > 0.5f) coverage += 1f
        coverage /= n
        if (coverage < 0.02f || coverage > 0.97f) return null

        // Blur grows with distance from the subject.
        val distance = distanceFromSubject(mask, ww, wh)
        val reach = 0.16f * max(ww, wh)
        val depth = FloatArray(n) { i -> smooth((distance[i] / reach).coerceIn(0f, 1f)) }
        val background = FloatArray(n) { i -> 1f - mask[i] }
        val bloom = bloomGain(lum, ww, wh, maxRadius, params.bloom)
        val levels = Array(3) { k ->
            blurredLevel(work, background, ww, wh, (maxRadius * (k + 1) / 3f).roundToInt().coerceAtLeast(1), bloom)
        }
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
        return Render(backdrop, FloatArray(n) { 1f - mask[it] })
    }

    // ---- full-resolution composite ----

    /** Mixes the blurred picture into the full-size photo by [Render.weight], with grain so the
     * smooth part doesn't look plastic next to the sharp part. */
    private fun composite(pixels: IntArray, w: Int, h: Int, render: Render, ww: Int, wh: Int, subjectLift: Boolean) {
        val backdrop = render.image
        val weight = render.weight
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
                val blurAmount = (weight[i00] * (1 - tx) + weight[i00 + 1] * tx) * (1 - ty) +
                    (weight[i00 + ww] * (1 - tx) + weight[i00 + ww + 1] * tx) * ty
                val i = y * w + x
                var p = pixels[i]
                val sharpAmount = 1f - blurAmount
                if (subjectLift && sharpAmount > 0.01f) p = lift(p, sharpAmount)
                if (blurAmount <= 0.001f) {
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
                val grain = (((hash(x, y) ushr 24) - 128) / 128f) * 1.4f * blurAmount
                fun mix(shift: Int): Int {
                    val sharp = (p shr shift) and 0xFF
                    val soft = channel(shift) + grain
                    return (sharp * sharpAmount + soft * blurAmount).toInt().coerceIn(0, 255)
                }
                pixels[i] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
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
    internal fun focusMask(lum: FloatArray, ww: Int, wh: Int, centreX: Float = 0.5f, centreY: Float = 0.5f): FloatArray? {
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
        for (y in ((centreY - 0.2f) * wh).toInt().coerceAtLeast(0) until ((centreY + 0.2f) * wh).toInt().coerceAtMost(wh)) {
            for (x in ((centreX - 0.2f) * ww).toInt().coerceAtLeast(0) until ((centreX + 0.2f) * ww).toInt().coerceAtMost(ww)) {
                centre += energy[y * ww + x]
                count++
            }
        }
        if (count == 0) return null
        val reference = centre / count
        if (reference < 0.004f) return null

        val conf = FloatArray(n)
        parallelFor(wh) { y ->
            val dy = (y - centreY * wh) / (wh / 2f)
            for (x in 0 until ww) {
                val dx = (x - centreX * ww) / (ww / 2f)
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

    /**
     * Keeps only the main subject of [mask]: pieces (above 0.5) that are far smaller than the
     * biggest one are dropped, together with a margin around them, so stray patches of wall or
     * clothing that the model half-believes in can't stay sharp.
     */
    internal fun cleanMask(mask: FloatArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val label = IntArray(n)
        val queue = IntArray(n)
        val sizes = ArrayList<Int>()
        var next = 0
        for (start in 0 until n) {
            if (mask[start] <= 0.5f || label[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0 && mask[i - 1] > 0.5f && label[i - 1] == 0) { label[i - 1] = next; queue[tail++] = i - 1 }
                if (x < w - 1 && mask[i + 1] > 0.5f && label[i + 1] == 0) { label[i + 1] = next; queue[tail++] = i + 1 }
                if (y > 0 && mask[i - w] > 0.5f && label[i - w] == 0) { label[i - w] = next; queue[tail++] = i - w }
                if (y < h - 1 && mask[i + w] > 0.5f && label[i + w] == 0) { label[i + w] = next; queue[tail++] = i + w }
            }
            sizes.add(tail)
        }
        if (sizes.size <= 1) return mask
        val biggest = sizes.maxOrNull() ?: return mask
        val keepLabel = BooleanArray(next + 1)
        for (k in 0 until next) keepLabel[k + 1] = sizes[k] >= biggest * 0.15f
        if (keepLabel.count { it } == next) return mask
        // Everything within a margin of a dropped piece is cleared; the rest is untouched.
        val dropped = FloatArray(n) { i -> if (label[i] != 0 && !keepLabel[label[i]]) 1f else 0f }
        val tmp = FloatArray(n)
        boxBlurF(dropped, tmp, w, h, max(3, max(w, h) / 120))
        return FloatArray(n) { i -> if (dropped[i] > 0.001f) 0f else mask[i] }
    }

    /** Snaps the rough mask to the picture's own edges with a guided filter, leaning outward. */
    internal fun refine(lum: FloatArray, rough: FloatArray, ww: Int, wh: Int): FloatArray {
        val radius = max(3, max(ww, wh) / 150)
        val q = guidedFilter(lum, rough, ww, wh, radius, 0.0006f)
        return FloatArray(q.size) { i -> smooth(((q[i] - 0.22f) / 0.42f).coerceIn(0f, 1f)) }
    }

    /** Edge-aware smoothing of [p] guided by [guide] (He et al.'s guided filter, grayscale). */
    internal fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
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
    private fun blurredLevel(work: IntArray, background: FloatArray, ww: Int, wh: Int, radius: Int, bloom: FloatArray?): IntArray {
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
            if (bloom != null) {
                // Bright points swell a little before blurring, so they melt into soft discs.
                val boost = bloom[i]
                r = min(255f, r * boost); g = min(255f, g * boost); b = min(255f, b * boost)
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
    internal fun boxBlurF(a: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
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

    internal fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun lerp(a: Int, b: Int, t: Float): Float = a + (b - a) * t

    private fun hash(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    /** Area-averaged shrink of [src] to [dw] x [dh]. */
    internal fun downscale(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
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
    internal fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
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
