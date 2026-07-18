package com.samnick.neverspiral

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Captures the current on-screen visualizer frame and offers it through the system share sheet --
 * whose targets already include "Set as Wallpaper" (Android's own wallpaper picker, with its
 * native crop/preview step) alongside Gallery, Messages, and everything else, so this needs no
 * separate WallpaperManager call or SET_WALLPAPER permission of its own.
 */
object FrameCapture {
    private const val FILE_NAME = "visualizer_frame.png"

    /**
     * Draws [view] (the window's root, in its own local coordinates) into a bitmap, crops to
     * [boundsInView] -- the visualizer Canvas's own on-screen rectangle relative to that same
     * root, so surrounding UI chrome like the mode strip and gear icon aren't included -- writes
     * the result to the app's cache, and launches a share chooser.
     */
    fun captureAndShare(context: Context, view: View, boundsInView: Rect) {
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))

        val left = boundsInView.left.coerceIn(0, full.width)
        val top = boundsInView.top.coerceIn(0, full.height)
        val width = boundsInView.width().coerceIn(1, full.width - left)
        val height = boundsInView.height().coerceIn(1, full.height - top)
        val cropped = Bitmap.createBitmap(full, left, top, width, height)

        val cacheDir = File(context.cacheDir, "shared_frames").apply { mkdirs() }
        val file = File(cacheDir, FILE_NAME)
        FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.PNG, 100, out) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share visualizer frame"))
    }
}
