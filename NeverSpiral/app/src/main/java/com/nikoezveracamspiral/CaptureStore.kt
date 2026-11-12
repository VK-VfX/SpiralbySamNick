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
object CaptureStore {
    private const val ALBUM_NAME = "Never Spiral"

    fun newStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Every file the app writes is named bynikezvera_<date>.<ext>. */
    private fun timestampName(extension: String, stamp: String = newStamp()): String =
        "bynikezvera_$stamp.$extension"

    fun saveJpeg(context: Context, bytes: ByteArray, stamp: String = newStamp(), suffix: String = ""): Uri? {
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
        markNotPending(context, uri, isVideo = false)
        return uri
    }

    /** [rawImage] is consumed (and closed) by this call either way -- on a failed insert it's
     * closed immediately instead of leaking, since there's no DngCreator write to do. */
    fun saveDng(
        context: Context,
        rawImage: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        stamp: String = newStamp(),
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

    fun publishVideo(context: Context, file: File, displayName: String = file.name): Uri? {
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
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
        file.delete()
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
