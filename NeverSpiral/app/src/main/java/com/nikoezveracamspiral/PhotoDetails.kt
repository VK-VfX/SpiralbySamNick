package com.nikoezveracamspiral

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

/** Everything the Details tab shows about a saved photo, read back from the file itself. */
class PhotoDetails(
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val camera: String,
    val exposure: String,
    val taken: String,
    val software: String,
    val processing: String,
    val quality: Int?,
) {
    val megapixels: Double get() = width.toDouble() * height / 1_000_000.0

    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", sizeBytes / 1_048_576.0)
            else -> String.format(Locale.US, "%.0f KB", sizeBytes / 1024.0)
        }

    val qualityLabel: String
        get() = when {
            quality == null -> "Not recorded"
            quality >= 100 -> "Maximum ($quality)"
            quality >= 95 -> "High ($quality)"
            else -> "Standard ($quality)"
        }

    /** Compression density: how many bits the file spends per pixel. */
    val bitsPerPixel: String
        get() = if (width > 0 && height > 0) String.format(Locale.US, "%.2f bits per pixel", sizeBytes * 8.0 / (width.toLong() * height)) else "-"

    companion object {
        fun load(context: Context, uri: Uri): PhotoDetails {
            var name = "Photo"
            var size = 0L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = c.getLong(1)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var camera = "-"
            var exposure = "-"
            var taken = "-"
            var software = "-"
            var processing = ""
            var quality: Int? = null
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
                if (make.isNotEmpty() || model.isNotEmpty()) camera = "$make $model".trim()
                val parts = mutableListOf<String>()
                exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0.0 }?.let {
                    parts += if (it >= 1.0) String.format(Locale.US, "%.1f s", it) else "1/${(1.0 / it).toInt()} s"
                }
                exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { parts += "ISO $it" }
                exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0.0 }?.let { parts += String.format(Locale.US, "f/%.1f", it) }
                exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0.0 }?.let { parts += String.format(Locale.US, "%.1f mm", it) }
                if (parts.isNotEmpty()) exposure = parts.joinToString("  ·  ")
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { taken = it.replaceFirst(':', '-').replaceFirst(':', '-') }
                exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.let { software = it }
                val description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()
                processing = description
                Regex("Quality (\\d+)").find(description)?.let { quality = it.groupValues[1].toIntOrNull() }
            }
            return PhotoDetails(name, size, bounds.outWidth, bounds.outHeight, camera, exposure, taken, software, processing, quality)
        }
    }
}
