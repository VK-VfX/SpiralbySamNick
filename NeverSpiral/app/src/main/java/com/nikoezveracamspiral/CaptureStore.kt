package com.nikoezveracamspiral

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
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
        if (meta != null) writeExif(context, uri, meta)
        markNotPending(context, uri, isVideo = false)
        return uri
    }

    /** Replaces the contents of an already-saved photo with its processed version. */
    fun overwriteJpeg(context: Context, uri: Uri, bytes: ByteArray, meta: PhotoMeta): Boolean {
        return try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            stream.use { it.write(bytes) }
            writeExif(context, uri, meta)
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
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            android.util.Log.w("CaptureStore", "Couldn't write EXIF", e)
        }
    }

    /** [rawImage] is consumed (and closed) by this call either way -- on a failed insert it's
     * closed immediately instead of leaking, since there's no DngCreator write to do. */
    fun saveDng(
        context: Context,
        rawImage: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        stamp: String = newStamp(),
        orientationDegrees: Int = 0,
        thumbnailJpeg: ByteArray? = null,
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
            rawImage.close()
            return null
        }
        DngCreator(characteristics, captureResult).use { dngCreator ->
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
                    while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeByteArray(thumbnailJpeg, 0, thumbnailJpeg.size, opts)?.let { thumb ->
                        dngCreator.setThumbnail(thumb)
                        thumb.recycle()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CaptureStore", "No DNG thumbnail", e)
                }
            }
            resolver.openOutputStream(uri)?.use { out -> dngCreator.writeImage(out, rawImage) }
        }
        rawImage.close()
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
