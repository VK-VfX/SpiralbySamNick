package com.nikoezveracamspiral

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes captured photos (JPEG + optional RAW/DNG) and videos into the shared MediaStore
 * collections, so they land in the system gallery immediately like any other camera app's
 * output -- no app-private gallery of our own to build or maintain. Every write uses the
 * IS_PENDING flag on API 29+ (scoped storage) so a half-written file never shows up as a
 * zero-byte/corrupt entry to other apps watching the collection.
 */
/** What gets written into a saved photo's EXIF block, all best-effort. */
class PhotoMeta(
    val exposureNanos: Long? = null,
    val iso: Int? = null,
    val aperture: Float? = null,
    val focalLengthMm: Float? = null,
    val software: String = "",
    val description: String = "",
    /** Where the photo was taken, when Location tagging is on. */
    val location: android.location.Location? = null,
)

object CaptureStore {
    private const val ALBUM_NAME = "Niko's Cam"

    fun newStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Every file the app writes is named bynikezvera_<date>.<ext>. */
    private fun timestampName(extension: String, stamp: String = newStamp()): String =
        "bynikezvera_$stamp.$extension"

    fun saveJpeg(context: Context, bytes: ByteArray, stamp: String = newStamp(), suffix: String = "", meta: PhotoMeta? = null): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, timestampName("jpeg", stamp).let { if (suffix.isEmpty()) it else it.replace(".jpeg", "$suffix.jpeg") })
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (meta != null) storeMeta(context, uri, bytes, meta, timestampName("jpeg", stamp).let { if (suffix.isEmpty()) it else it.replace(".jpeg", "$suffix.jpeg") })
        markNotPending(context, uri, isVideo = false)
        return uri
    }

    /** Writes the details into the file's EXIF, except for an Ultra HDR file: rewriting its
     * header would shift the offsets that point at the embedded HDR gain map, so those keep the
     * camera's own EXIF and have their details kept in a small side record instead. */
    private fun storeMeta(context: Context, uri: Uri, bytes: ByteArray, meta: PhotoMeta, name: String) {
        if (UltraHdr.isUltraHdr(bytes)) {
            PhotoMetaStore.save(context, name, meta)
        } else {
            writeExif(context, uri, meta)
        }
    }

    /** The date stamp of a photo this app saved (bynikezvera_<stamp>.jpeg), or null for anything else, including "_original" copies. */
    fun stampOf(context: Context, uri: Uri): String? {
        val name = displayNameOf(context, uri) ?: return null
        return Regex("^bynikezvera_(\\d{8}_\\d{6})\\.jpeg$").find(name)?.groupValues?.get(1)
    }

    private fun displayNameOf(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }

    /** Replaces the contents of an already-saved photo with its processed version. */
    fun overwriteJpeg(context: Context, uri: Uri, bytes: ByteArray, meta: PhotoMeta): Boolean {
        return try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            stream.use { it.write(bytes) }
            storeMeta(context, uri, bytes, meta, displayNameOf(context, uri) ?: "")
            true
        } catch (e: Exception) {
            android.util.Log.w("CaptureStore", "Couldn't overwrite photo", e)
            false
        }
    }

    /** Stamps camera, exposure and processing details into the saved JPEG so the Details tab (and
     * any other app) can read them back. A failure here never loses the photo itself. */
    private fun writeExif(context: Context, uri: Uri, meta: PhotoMeta) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                if (meta.software.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_SOFTWARE, meta.software)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
                meta.exposureNanos?.let { exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
                meta.iso?.let { exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
                meta.aperture?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
                meta.focalLengthMm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000") }
                if (meta.description.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, meta.description)
                meta.location?.let { exif.setGpsInfo(it) }
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            android.util.Log.w("CaptureStore", "Couldn't write EXIF", e)
        }
    }

    /** Writes a DNG. With [merged] the already-merged samples are written; otherwise [rawImage]
     * is, and is consumed (closed) by this call either way. Merged frames' images are the
     * caller's to close. */
    fun saveDng(
        context: Context,
        rawImage: Image?,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        stamp: String = newStamp(),
        orientationDegrees: Int = 0,
        thumbnailJpeg: ByteArray? = null,
        merged: RawMerge.Merged? = null,
        location: android.location.Location? = null,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, timestampName("dng", stamp))
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            rawImage?.close()
            return null
        }
        try {
            DngCreator(characteristics, captureResult).use { dngCreator ->
                if (location != null) dngCreator.setLocation(location)
                // Without this the file opens sideways: the DNG holds raw sensor rows, so the same
                // rotation the JPEG gets has to be recorded as its orientation tag.
                dngCreator.setOrientation(
                    when (((orientationDegrees % 360) + 360) % 360) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    },
                )
                if (thumbnailJpeg != null) {
                    try {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(thumbnailJpeg, 0, thumbnailJpeg.size, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        android.graphics.BitmapFactory.decodeByteArray(thumbnailJpeg, 0, thumbnailJpeg.size, opts)?.let { decoded ->
                            // DngCreator wants both sides strictly under 256 px.
                            val scale = minOf(1f, 255f / maxOf(decoded.width, decoded.height))
                            val thumb = if (scale < 1f) {
                                android.graphics.Bitmap.createScaledBitmap(
                                    decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true,
                                ).also { if (it !== decoded) decoded.recycle() }
                            } else decoded
                            dngCreator.setThumbnail(thumb)
                            thumb.recycle()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("CaptureStore", "No DNG thumbnail", e)
                    }
                }
                resolver.openOutputStream(uri)?.use { out ->
                    if (merged != null) {
                        val buffer = ByteBuffer.allocate(merged.width * merged.height * 2).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.asShortBuffer().put(merged.samples)
                        dngCreator.writeByteBuffer(out, android.util.Size(merged.width, merged.height), buffer, 0)
                    } else if (rawImage != null) {
                        dngCreator.writeImage(out, rawImage)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CaptureStore", "Couldn't write the DNG", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            rawImage?.close()
            return null
        }
        rawImage?.close()
        markNotPending(context, uri, isVideo = false)
        return uri
    }

    /** A private cache file for [android.media.MediaRecorder] to write into directly --
     * MediaRecorder needs a real file path, not a MediaStore [Uri], so recording always lands
     * here first and [publishVideo] copies it into the shared collection once recording stops. */
    fun newVideoCacheFile(context: Context): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, timestampName("mp4"))
    }

    /** Copies [file] into the shared Movies collection and leaves [file] in place. */
    fun publishVideoCopy(context: Context, file: File, displayName: String = file.name): Uri? =
        publishVideo(context, file, displayName, deleteSource = false)

    fun deleteMedia(context: Context, uri: Uri) {
        // A Portrait's editable data goes with the photo.
        PortraitStore.delete(context, stampOf(context, uri))
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            android.util.Log.w("CaptureStore", "Couldn't delete $uri", e)
        }
    }

    fun publishVideo(context: Context, file: File, displayName: String = file.name, deleteSource: Boolean = true): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) }; true } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        if (deleteSource) file.delete()
        markNotPending(context, uri, isVideo = true)
        return uri
    }

    private fun markNotPending(context: Context, uri: Uri, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            val column = if (isVideo) MediaStore.Video.Media.IS_PENDING else MediaStore.Images.Media.IS_PENDING
            put(column, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }
}
