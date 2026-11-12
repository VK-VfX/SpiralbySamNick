@file:OptIn(UnstableApi::class)

package com.nikoezveracamspiral

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Applies the current [Look] to a finished recording in the background. The recording itself is
 * unfiltered (no per-frame work while filming); afterwards the colour part of the look (filter,
 * exposure, contrast, saturation, warmth, tint) is rendered into a new file on the GPU. Effects
 * that need neighbouring pixels (grain, vignette, sharpening, shadows/highlights) are photo-only.
 */
object VideoFilter {
    fun needed(look: Look): Boolean =
        !(look.filter == PhotoFilter.NATURAL && look.exposure == 0f && look.contrast == 0f &&
            look.saturation == 0f && look.warmth == 0f && look.tint == 0f)

    /** Android's row-major 4x5 colour matrix as a column-major 4x4 for the GPU: the offsets ride
     * in the last column, which multiplies alpha (always 1). */
    private fun glMatrix(look: Look): FloatArray {
        val a = look.toColorMatrix().array
        val m = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 3) m[col * 4 + row] = a[row * 5 + col]
            m[12 + row] = a[row * 5 + 3] + a[row * 5 + 4] / 255f
        }
        return m
    }

    suspend fun apply(context: Context, input: File, look: Look): File {
        val output = File(input.parentFile, input.nameWithoutExtension + "_filtered.mp4")
        if (output.exists()) output.delete()
        val matrix = glMatrix(look)
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val rgb = object : RgbMatrix {
                    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
                }
                val videoEffects: List<Effect> = listOf(rgb)
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                    .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
                    .build()
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(edited, output.absolutePath)
            }
        }
        return output
    }
}
