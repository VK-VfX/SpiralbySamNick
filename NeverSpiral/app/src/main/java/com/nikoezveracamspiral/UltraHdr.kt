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
        return android.graphics.Gainmap(original, result)
    }
}
