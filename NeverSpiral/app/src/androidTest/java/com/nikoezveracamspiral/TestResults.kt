package com.nikoezveracamspiral

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Where the emulator tests leave what they find: a report and a few pictures, pulled out by CI. */
object TestResults {
    val dir: File by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("emulator")!!.also { it.mkdirs() }
    }

    fun log(line: String) {
        android.util.Log.i("EmulatorTest", line)
        File(dir, "report.txt").appendText(line + "\n")
    }

    fun memory(label: String) {
        val rt = Runtime.getRuntime()
        log("$label: java heap ${(rt.totalMemory() - rt.freeMemory()) / MB} of ${rt.maxMemory() / MB} MB, native ${Debug.getNativeHeapAllocatedSize() / MB} MB")
    }

    /** Saves a JPEG (shrunk so the files stay small to publish). */
    fun saveJpeg(name: String, bytes: ByteArray, maxSide: Int = 1200) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        saveBitmap(name, decoded, maxSide)
    }

    fun saveBitmap(name: String, bitmap: Bitmap, maxSide: Int = 1200) {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val shown = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        File(dir, name).outputStream().use { shown.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private const val MB = 1024 * 1024
}
