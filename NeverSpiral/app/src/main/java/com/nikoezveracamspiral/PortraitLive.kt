package com.nikoezveracamspiral

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Portrait mode's live viewfinder effect. Every few frames a small copy of the picture goes through
 * the person model (in its fast streaming mode). On Android 12+ the phone's graphics chip does the
 * blurring: [mask] only finds the subject, and the viewfinder blurs the small copy on the GPU and
 * shows it wherever the mask says "background". Older phones use [render], which blurs on the
 * processor. The saved photo is still made from the full-resolution frames.
 */
object PortraitLive {
    private var segmenter: Segmenter? = null
    private var previous: FloatArray? = null

    /** A small copy of the picture and, for it, an alpha mask that is opaque over the background. */
    class LiveFrame(val frame: Bitmap, val mask: Bitmap)

    /** The subject mask for [frame], or null when no subject stands out. */
    @Synchronized
    fun mask(frame: Bitmap): LiveFrame? {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = lumaOf(pixels)
        val rough = segment(frame, w, h) ?: PortraitEffect.focusMask(lum, w, h) ?: return null
        val subject = PortraitEffect.refine(lum, rough, w, h)
        val out = IntArray(w * h) { i -> ((1f - subject[i]).coerceIn(0f, 1f) * 255f).toInt() shl 24 }
        return LiveFrame(frame, Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888))
    }

    /** The blurred-background layer for [frame] made on the processor (phones before Android 12). */
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

    private fun lumaOf(pixels: IntArray) = FloatArray(pixels.size) { i ->
        val p = pixels[i]
        (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
    }

    /** The person model's confidence at [w] x [h], smoothly resampled, or null if nobody is there. */
    private fun segment(frame: Bitmap, w: Int, h: Int): FloatArray? {
        return try {
            val seg = segmenter ?: Segmentation.getClient(
                SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build(),
            ).also { segmenter = it }
            // The model works at about 256 pixels whatever it is given, so don't hand it more.
            val side = 256f / max(w, h)
            val input = if (side < 1f) {
                Bitmap.createScaledBitmap(frame, (w * side).roundToInt().coerceAtLeast(1), (h * side).roundToInt().coerceAtLeast(1), true)
            } else frame
            val mask = try {
                Tasks.await(seg.process(InputImage.fromBitmap(input, 0)))
            } finally {
                if (input !== frame) input.recycle()
            }
            val mw = mask.width
            val mh = mask.height
            val buffer = mask.buffer
            buffer.rewind()
            val small = FloatArray(mw * mh) { buffer.float }
            val conf = PortraitEffect.resize(small, mw, mh, w, h)
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
