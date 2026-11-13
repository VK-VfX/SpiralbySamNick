package com.nikoezveracamspiral

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/**
 * Ultra HDR helpers. An Ultra HDR photo is an ordinary JPEG with a second, small image (the "gain
 * map") appended that tells an HDR screen how much brighter each part may be shown. On other
 * screens and apps it is just a normal JPEG, so keeping it is free.
 *
 * The camera produces the gain map for the frame it takes. When the app then re-processes a shot
 * (merging frames, a look, a crop or a mirror), the gain map is carried over by applying the same
 * rotation, crop and mirror to it, so it still lines up with the finished picture.
 */
object UltraHdr {
    private val MARKER = "hdrgm".toByteArray(Charsets.US_ASCII)

    /** True if [bytes] is a JPEG that carries an HDR gain map (its XMP header names "hdrgm"). */
    fun isUltraHdr(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 262_144) - MARKER.size
        var i = 0
        while (i < limit) {
            if (bytes[i] == MARKER[0]) {
                var j = 1
                while (j < MARKER.size && bytes[i + j] == MARKER[j]) j++
                if (j == MARKER.size) return true
            }
            i++
        }
        return false
    }

    /** The EXIF orientation of [bytes] as the matrix that turns the stored pixels upright. */
    fun uprightMatrix(bytes: ByteArray): Matrix {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return PhotoProcessor.orientationMatrix(orientation)
    }

    /**
     * The gain map of [referenceJpeg] (an Ultra HDR file), rotated, cropped to [ratio] and
     * mirrored the same way the processed picture was, ready to attach to it. Null when the file
     * has no gain map or this Android can't read it.
     */
    @SuppressLint("NewApi")
    fun gainmapFor(referenceJpeg: ByteArray, ratio: AspectOption, mirror: Boolean): android.graphics.Gainmap? {
        if (Build.VERSION.SDK_INT < 34) return null
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(referenceJpeg))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(8)
        }
        val original = decoded.gainmap ?: return null
        var contents = original.gainmapContents

        val upright = uprightMatrix(referenceJpeg)
        if (!upright.isIdentity) {
            val turned = Bitmap.createBitmap(contents, 0, 0, contents.width, contents.height, upright, true)
            contents = turned
        }
        // The same centre crop to the chosen ratio that the picture itself got.
        val target = ratio.widthOverHeight
        val current = contents.width.toFloat() / contents.height
        var cx = 0
        var cy = 0
        var cw = contents.width
        var ch = contents.height
        if (current > target) {
            cw = (contents.height * target).toInt().coerceAtLeast(1)
            cx = (contents.width - cw) / 2
        } else if (current < target) {
            ch = (contents.width / target).toInt().coerceAtLeast(1)
            cy = (contents.height - ch) / 2
        }
        val flip = Matrix().apply { if (mirror) postScale(-1f, 1f) }
        val result = Bitmap.createBitmap(contents, cx, cy, cw, ch, flip, true)
        // Reuse the camera's own gain map (it carries the brightness range and curve settings) and
        // swap in the contents turned and cropped to match the finished picture.
        original.gainmapContents = result
        return original
    }

    /** 0 for dim and middle tones, 1 for bright ones, smooth in between: how much of the HDR boost a spot of luminance [l] (0..1) keeps. */
    fun highlightWeight(l: Float): Float {
        val t = ((l - 0.55f) / 0.30f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The stored gain-map value that means "no boost" for a channel with these limits (ratios of brightness) and gamma. */
    fun neutralEncoded(ratioMin: Float, ratioMax: Float, gamma: Float): Float {
        val lo = kotlin.math.ln(ratioMin.coerceAtLeast(1e-4f))
        val hi = kotlin.math.ln(ratioMax.coerceAtLeast(1e-4f))
        if (hi - lo < 1e-4f) return 0f
        val recovery = ((0f - lo) / (hi - lo)).coerceIn(0f, 1f)
        return Math.pow(recovery.toDouble(), gamma.coerceAtLeast(1e-3f).toDouble()).toFloat()
    }

    /**
     * Makes the HDR boost apply to the bright parts of the finished picture only (lamps, sky,
     * screens), so mid-tones and shadows look the same on an HDR screen as on any other. The camera's
     * map was made from one underexposed frame of the burst, before the merge lifted the shadows, so
     * left alone it boosts parts that are no longer dark. Returns null when the picture has hardly
     * any bright part: then there is nothing for HDR to add and a plain JPEG is saved.
     *
     * @param pixels the finished picture, [w] x [h] ARGB, which the gain map already lines up with.
     */
    @SuppressLint("NewApi")
    fun highlightsOnly(gainmap: android.graphics.Gainmap, pixels: IntArray, w: Int, h: Int): android.graphics.Gainmap? {
        if (Build.VERSION.SDK_INT < 34) return gainmap
        val src = gainmap.gainmapContents
        val gw = src.width
        val gh = src.height
        if (gw < 2 || gh < 2 || w < 2 || h < 2) return gainmap
        val weights = FloatArray(gw * gh)
        var bright = 0
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val x = (((gx + 0.25f + 0.5f * dx) / gw) * w).toInt().coerceIn(0, w - 1)
                    val y = (((gy + 0.25f + 0.5f * dy) / gh) * h).toInt().coerceIn(0, h - 1)
                    val p = pixels[y * w + x]
                    sum += (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
                }
                val l = sum / 4f
                weights[gy * gw + gx] = highlightWeight(l)
                if (l > 0.85f) bright++
            }
        }
        if (bright < gw * gh * 0.015f) return null
        val config = if (src.config == null || src.config == Bitmap.Config.HARDWARE) Bitmap.Config.ARGB_8888 else src.config
        val out = src.copy(config, true) ?: return gainmap
        val alphaOnly = out.config == Bitmap.Config.ALPHA_8
        val px = IntArray(gw * gh)
        out.getPixels(px, 0, gw, 0, 0, gw, gh)
        val rMin = gainmap.ratioMin
        val rMax = gainmap.ratioMax
        val gam = gainmap.gamma
        val n = FloatArray(3) { neutralEncoded(rMin[it], rMax[it], gam[it]) }
        for (i in px.indices) {
            val p = px[i]
            val wt = weights[i]
            px[i] = if (alphaOnly) {
                val a = (p ushr 24) / 255f
                val na = ((n[0] + (a - n[0]) * wt) * 255f + 0.5f).toInt().coerceIn(0, 255)
                na shl 24
            } else {
                fun ch(shift: Int, k: Int): Int {
                    val v = ((p shr shift) and 0xFF) / 255f
                    return ((n[k] + (v - n[k]) * wt) * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
                (0xFF shl 24) or (ch(16, 0) shl 16) or (ch(8, 1) shl 8) or ch(0, 2)
            }
        }
        out.setPixels(px, 0, gw, 0, 0, gw, gh)
        gainmap.gainmapContents = out
        return gainmap
    }
}
