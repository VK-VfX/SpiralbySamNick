@file:OptIn(UnstableApi::class)

package com.nikoezveracamspiral

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies the current [Look] to a finished recording in the background. The recording itself is
 * unfiltered (no per-frame work while filming); afterwards the colour part of the look (filter or
 * LUT, exposure, contrast, saturation, warmth, tint) is rendered into a new file on the GPU, with
 * progress reported as it goes. Effects that need neighbouring pixels (grain, vignette,
 * sharpening, shadows/highlights) are photo-only.
 */
object VideoFilter {
    fun needed(look: Look): Boolean =
        !(look.filter == PhotoFilter.NATURAL && look.lut == null && look.exposure == 0f && look.contrast == 0f &&
            look.saturation == 0f && look.warmth == 0f && look.tint == 0f)

    /** Android's row-major 4x5 colour matrix as a column-major 4x4 for the GPU: the offsets ride
     * in the last column, which multiplies alpha (always 1). */
    private fun glMatrix(look: Look): FloatArray {
        val a = look.toColorMatrix(useLutFit = false).array
        val m = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 3) m[col * 4 + row] = a[row * 5 + col]
            m[12 + row] = a[row * 5 + 3] + a[row * 5 + 4] / 255f
        }
        return m
    }

    /** The imported LUT as the cube the GPU effect wants, blended with identity by [strength]. */
    private fun cubeFor(lut: Lut, strength: Float): Array<Array<IntArray>> {
        val n = lut.size
        return Array(n) { r ->
            Array(n) { g ->
                IntArray(n) { b ->
                    val i = (r + g * n + b * n * n) * 3
                    fun mix(inValue: Float, lutValue: Float) = (inValue + (lutValue * 255f - inValue) * strength).toInt().coerceIn(0, 255)
                    val rr = mix(r * 255f / (n - 1), lut.data[i])
                    val gg = mix(g * 255f / (n - 1), lut.data[i + 1])
                    val bb = mix(b * 255f / (n - 1), lut.data[i + 2])
                    (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
    }

    suspend fun apply(context: Context, input: File, look: Look, onProgress: (Float) -> Unit): File {
        val output = File(input.parentFile, input.nameWithoutExtension + "_filtered.mp4")
        if (output.exists()) output.delete()
        val matrix = glMatrix(look)
        val lutEffect: Effect? = look.lut?.let { SingleColorLut.createFromCube(cubeFor(it, look.strength)) }
        withContext(Dispatchers.Main) {
            val rgb = object : RgbMatrix {
                override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
            }
            val videoEffects: List<Effect> = listOfNotNull(lutEffect, rgb)
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
                .build()
            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.completeExceptionally(exportException)
                    }
                })
                .build()
            transformer.start(edited, output.absolutePath)
            val holder = ProgressHolder()
            try {
                while (!done.isCompleted) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(300)
                }
                done.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            }
        }
        return output
    }
}
