package com.nikoezveracamspiral

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.pow

/** Color looks shared by the live preview (as a layer color filter), the style thumbnails, and
 * the saved photo, so what the viewfinder shows is what gets written. */
enum class PhotoStyle(val label: String) {
    NATURAL("Natural"),
    VIVID("Vivid"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    MOODY("Moody"),
    MONO("Mono");

    fun colorMatrix(): ColorMatrix {
        val m = ColorMatrix()
        when (this) {
            NATURAL -> Unit
            VIVID -> { m.setSaturation(1.4f); m.postConcat(contrast(1.1f, 0f)) }
            PORTRAIT -> {
                m.setSaturation(0.95f)
                m.postConcat(ColorMatrix(floatArrayOf(
                    1.05f, 0f, 0f, 0f, 4f,
                    0f, 1.0f, 0f, 0f, 2f,
                    0f, 0f, 0.96f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            LANDSCAPE -> { m.setSaturation(1.25f); m.postConcat(contrast(1.15f, 0f)) }
            MOODY -> {
                m.setSaturation(0.75f)
                m.postConcat(contrast(1.2f, -12f))
                m.postConcat(ColorMatrix(floatArrayOf(
                    0.97f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1.06f, 0f, 4f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            MONO -> { m.setSaturation(0f); m.postConcat(contrast(1.15f, 0f)) }
        }
        return m
    }

    fun filter(): ColorMatrixColorFilter? = if (this == NATURAL) null else ColorMatrixColorFilter(colorMatrix())

    private fun contrast(c: Float, offset: Float): ColorMatrix {
        val t = (1f - c) * 127.5f + offset
        return ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
}

/** Output crop, as width:height of the portrait frame. */
enum class AspectOption(val label: String, val widthOverHeight: Float) {
    R4_3("4:3", 3f / 4f),
    R16_9("16:9", 9f / 16f),
    R1_1("1:1", 1f),
    R2_1("2:1", 1f / 2f),
}

enum class GridStyle(val label: String) { OFF("Off"), THIRDS("Thirds"), GOLDEN("Golden"), SQUARE("Square") }

/**
 * The after-capture pipeline: the camera's JPEG (already encoded at max quality with high-quality
 * noise reduction and edge enhancement) is decoded, rotated upright from its EXIF tag, optionally
 * mirrored (selfies), cropped to the chosen ratio, run through an HDR-style shadow/highlight
 * tone curve, lightly sharpened, color-styled, and re-encoded at quality 97.
 */
object PhotoProcessor {
    fun process(bytes: ByteArray, style: PhotoStyle, hdrLevel: Int, ratio: AspectOption, mirror: Boolean): ByteArray {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 24_000_000L) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return bytes

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        // Rotate first, then decide the crop in the upright frame.
        val rotated = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (rotated !== decoded) decoded.recycle()

        val target = ratio.widthOverHeight
        val current = rotated.width.toFloat() / rotated.height
        var cx = 0
        var cy = 0
        var cw = rotated.width
        var ch = rotated.height
        if (current > target) {
            cw = (rotated.height * target).toInt().coerceAtLeast(1)
            cx = (rotated.width - cw) / 2
        } else if (current < target) {
            ch = (rotated.width / target).toInt().coerceAtLeast(1)
            cy = (rotated.height - ch) / 2
        }
        val flip = Matrix().apply { if (mirror) postScale(-1f, 1f) }
        val cropped = Bitmap.createBitmap(rotated, cx, cy, cw, ch, flip, true)
        if (cropped !== rotated) rotated.recycle()

        val w = cropped.width
        val h = cropped.height
        val pixels = IntArray(w * h)
        cropped.getPixels(pixels, 0, w, 0, 0, w, h)
        cropped.recycle()

        if (hdrLevel > 0) applyToneCurve(pixels, hdrLevel / 5f)
        sharpen(pixels, w, h, 0.35f)

        val out = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val styled = style.filter()
        val finalBitmap = if (styled == null) out else {
            val target2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(target2).drawBitmap(out, 0f, 0f, Paint().apply { colorFilter = styled })
            out.recycle()
            target2
        }
        val stream = ByteArrayOutputStream(1 shl 20)
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        finalBitmap.recycle()
        return stream.toByteArray()
    }

    /** Lifts shadows and gently tames highlights, then restores midtone contrast: the core of a
     * single-frame "HDR" look. [amount] is 0..1. */
    private fun applyToneCurve(pixels: IntArray, amount: Float) {
        val lut = IntArray(256) { i ->
            val x = i / 255f
            val lifted = x.pow(1f - 0.32f * amount)
            val smooth = lifted * lifted * (3f - 2f * lifted)
            val v = lifted + (smooth - lifted) * 0.35f * amount
            (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or
                (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
        }
    }

    private fun sharpen(pixels: IntArray, w: Int, h: Int, k: Float) {
        if (w < 3 || h < 3) return
        val src = pixels.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = src[i]
                val u = src[i - w]
                val d = src[i + w]
                val l = src[i - 1]
                val r = src[i + 1]
                var out = c and 0xFF000000.toInt()
                var shift = 16
                while (shift >= 0) {
                    val cv = (c shr shift) and 0xFF
                    val nb = ((u shr shift) and 0xFF) + ((d shr shift) and 0xFF) + ((l shr shift) and 0xFF) + ((r shr shift) and 0xFF)
                    val v = (cv + k * (4 * cv - nb)).toInt().coerceIn(0, 255)
                    out = out or (v shl shift)
                    shift -= 8
                }
                pixels[i] = out
            }
        }
    }
}
