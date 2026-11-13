package com.nikoezveracamspiral

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

/**
 * Portrait mode's live viewfinder effect: every few frames a small copy of the picture goes through
 * the person model (in its fast streaming mode) and the chosen style's blurred background is laid
 * over the live view, so the look can be judged before the shutter. The saved photo is still made
 * from the full-resolution frames (with AI depth when the model is installed).
 */
object PortraitLive {
    private var segmenter: Segmenter? = null
    private var previous: FloatArray? = null

    /** The blurred-background layer for [frame], or null when no subject stands out. */
    @Synchronized
    fun render(frame: Bitmap, strength: Float, style: PortraitStyle): Bitmap? {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = segment(frame, w, h)
        val out = PortraitEffect.overlay(pixels, w, h, strength, style, mask) ?: return null
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun segment(frame: Bitmap, w: Int, h: Int): FloatArray? {
        return try {
            val seg = segmenter ?: Segmentation.getClient(
                SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build(),
            ).also { segmenter = it }
            val mask = Tasks.await(seg.process(InputImage.fromBitmap(frame, 0)))
            val mw = mask.width
            val mh = mask.height
            val buffer = mask.buffer
            buffer.rewind()
            val conf = FloatArray(w * h)
            for (y in 0 until h) {
                val sy = (y * mh / h).coerceAtMost(mh - 1)
                for (x in 0 until w) {
                    val sx = (x * mw / w).coerceAtMost(mw - 1)
                    conf[y * w + x] = buffer.getFloat((sy * mw + sx) * 4)
                }
            }
            // Blend with the last frame so the edge doesn't flicker.
            val last = previous
            if (last != null && last.size == conf.size) {
                for (i in conf.indices) conf[i] = conf[i] * 0.65f + last[i] * 0.35f
            }
            previous = conf
            var covered = 0f
            for (c in conf) if (c > 0.5f) covered += 1f
            covered /= conf.size
            if (covered < 0.03f || covered > 0.95f) null else conf
        } catch (e: Exception) {
            android.util.Log.w("PortraitLive", "Live person model failed", e)
            null
        }
    }

    @Synchronized
    fun release() {
        segmenter?.close()
        segmenter = null
        previous = null
    }
}
