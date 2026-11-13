package com.nikoezveracamspiral

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * What Portrait worked out about a photo, kept at its working size so the background blur can be
 * re-done later with another strength, style or subject without running the depth model again.
 *
 * @property depth 0..1 for every pixel, larger = nearer.
 * @property keep 0..1, how much of the pixel belongs to the subject and stays sharp.
 * @property confident where the depth is trusted enough to call something "in front of" the subject.
 * @property focus the depth of the focus plane (the subject's).
 */
class PortraitAnalysis(
    val width: Int,
    val height: Int,
    val depth: FloatArray,
    val keep: FloatArray,
    val confident: BooleanArray,
    val focus: Float,
)

/**
 * Keeps, for each Portrait photo, the picture as it was before the blur and the analysis above, in
 * the app's private storage (the saved photo itself stays an ordinary JPEG). The editor's Portrait
 * tab uses them to change the blur afterwards. Only the newest few are kept, and a photo's pair is
 * removed when the photo is deleted from the app.
 */
object PortraitStore {
    class Saved(val preBlur: File, val analysis: PortraitAnalysis, val style: PortraitStyle, val strength: Float)

    private const val DIR = "portrait"
    private const val MAGIC = 0x4E504131 // "NPA1"
    private const val KEEP_PAIRS = 40

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun jpegFile(context: Context, stamp: String) = File(dir(context), "$stamp.jpg")
    private fun dataFile(context: Context, stamp: String) = File(dir(context), "$stamp.pa")

    /** Saves the pair for the photo with this [stamp]. Best effort: a failure only means no later editing. */
    fun save(context: Context, stamp: String, preBlurJpeg: ByteArray, analysis: PortraitAnalysis, style: PortraitStyle, strength: Float) {
        if (stamp.isEmpty()) return
        try {
            jpegFile(context, stamp).writeBytes(preBlurJpeg)
            dataFile(context, stamp).writeBytes(encode(analysis, style, strength))
            prune(context)
        } catch (e: Exception) {
            android.util.Log.w("PortraitStore", "Couldn't keep the portrait data", e)
            delete(context, stamp)
        }
    }

    fun exists(context: Context, stamp: String?): Boolean =
        !stamp.isNullOrEmpty() && jpegFile(context, stamp).isFile && dataFile(context, stamp).isFile

    fun load(context: Context, stamp: String?): Saved? {
        if (stamp.isNullOrEmpty() || !exists(context, stamp)) return null
        return try {
            val decoded = decode(dataFile(context, stamp).readBytes()) ?: return null
            Saved(jpegFile(context, stamp), decoded.analysis, decoded.style, decoded.strength)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context, stamp: String?) {
        if (stamp.isNullOrEmpty()) return
        try {
            jpegFile(context, stamp).delete()
            dataFile(context, stamp).delete()
        } catch (_: Exception) {
        }
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".pa") } ?: return
        if (files.size <= KEEP_PAIRS) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP_PAIRS).forEach { old ->
            old.delete()
            File(old.parentFile, old.name.removeSuffix(".pa") + ".jpg").delete()
        }
    }

    internal class Decoded(val analysis: PortraitAnalysis, val style: PortraitStyle, val strength: Float)

    /** The analysis and the settings it was made with as a compact, compressed record. */
    internal fun encode(a: PortraitAnalysis, style: PortraitStyle, strength: Float): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes, Deflater(Deflater.BEST_SPEED))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(a.width)
            out.writeInt(a.height)
            out.writeFloat(a.focus)
            out.writeInt(style.ordinal)
            out.writeFloat(strength)
            val n = a.width * a.height
            out.write(ByteArray(n) { (a.depth[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() })
            out.write(ByteArray(n) { (a.keep[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() })
            out.write(ByteArray(n) { if (a.confident[it]) 1 else 0 })
        }
        return bytes.toByteArray()
    }

    internal fun decode(data: ByteArray): Decoded? {
        return try {
            DataInputStream(InflaterInputStream(ByteArrayInputStream(data))).use { input ->
                if (input.readInt() != MAGIC) return null
                val w = input.readInt()
                val h = input.readInt()
                if (w < 16 || h < 16 || w.toLong() * h > 8_000_000L) return null
                val focus = input.readFloat()
                val style = PortraitStyle.entries.getOrElse(input.readInt()) { PortraitStyle.NATURAL }
                val strength = input.readFloat()
                val n = w * h
                val depth = ByteArray(n)
                val keep = ByteArray(n)
                val confident = ByteArray(n)
                input.readFully(depth)
                input.readFully(keep)
                input.readFully(confident)
                Decoded(
                    PortraitAnalysis(
                        w, h,
                        FloatArray(n) { (depth[it].toInt() and 0xFF) / 255f },
                        FloatArray(n) { (keep[it].toInt() and 0xFF) / 255f },
                        BooleanArray(n) { confident[it].toInt() != 0 },
                        focus,
                    ),
                    style, strength,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
