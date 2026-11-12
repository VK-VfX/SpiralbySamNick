package com.nikoezveracamspiral

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.exp
import kotlin.math.pow

enum class FilterCategory(val label: String) {
    BASIC("Basic"), FILM("Film"), CINEMA("Cinema"), RETRO("Retro"), MONO("Mono"), CREATIVE("Creative")
}

/**
 * A color look. Each is a recipe of global color math (saturation, contrast, brightness, channel
 * gains, faded blacks) that also drives the live preview, plus effects only the saved photo gets:
 * split toning (a tint pushed into the shadows and into the highlights), grain and vignette.
 * Tint values are offsets in 0..255 units at full strength.
 */
enum class PhotoFilter(
    val label: String,
    val category: FilterCategory,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val gainR: Float = 1f,
    val gainG: Float = 1f,
    val gainB: Float = 1f,
    val fade: Float = 0f,
    val shadowR: Float = 0f, val shadowG: Float = 0f, val shadowB: Float = 0f,
    val highR: Float = 0f, val highG: Float = 0f, val highB: Float = 0f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
) {
    NATURAL("Natural", FilterCategory.BASIC),
    VIVID("Vivid", FilterCategory.BASIC, saturation = 1.4f, contrast = 1.1f),
    PORTRAIT("Portrait", FilterCategory.BASIC, saturation = 0.95f, brightness = 6f, gainR = 1.05f, gainB = 0.96f),
    LANDSCAPE("Landscape", FilterCategory.BASIC, saturation = 1.3f, contrast = 1.15f, gainG = 1.03f, gainB = 1.03f),
    CRISP("Crisp", FilterCategory.BASIC, saturation = 1.1f, contrast = 1.25f),
    SOFT("Soft", FilterCategory.BASIC, saturation = 0.95f, contrast = 0.85f, brightness = 8f, fade = 0.06f),

    CLASSIC_FILM("Classic Film", FilterCategory.FILM, saturation = 0.9f, contrast = 1.1f, gainR = 1.04f, gainB = 0.95f, fade = 0.05f, shadowB = 8f, grain = 0.35f, vignette = 0.25f),
    PORTRA("Portra 400", FilterCategory.FILM, saturation = 0.92f, gainR = 1.05f, gainB = 0.94f, fade = 0.04f, highR = 8f, highG = 4f, grain = 0.25f),
    EKTAR("Ektar 100", FilterCategory.FILM, saturation = 1.35f, contrast = 1.15f, gainR = 1.05f, grain = 0.15f),
    VELVIA("Velvia", FilterCategory.FILM, saturation = 1.55f, contrast = 1.2f, gainG = 1.03f, grain = 0.1f),
    GOLD("Gold 200", FilterCategory.FILM, saturation = 1.1f, contrast = 1.05f, gainR = 1.08f, gainG = 1.03f, gainB = 0.88f, fade = 0.05f, grain = 0.3f),
    CINE_800T("Cine 800T", FilterCategory.FILM, saturation = 1.05f, contrast = 1.12f, gainR = 0.96f, gainB = 1.1f, shadowB = 14f, highR = 18f, highG = 6f, grain = 0.4f, vignette = 0.3f),
    PRO_400H("Pro 400H", FilterCategory.FILM, saturation = 0.9f, contrast = 0.95f, gainG = 1.03f, gainB = 1.03f, fade = 0.07f, shadowG = 6f, highR = 6f, grain = 0.2f),

    TEAL_ORANGE("Teal & Orange", FilterCategory.CINEMA, saturation = 1.15f, contrast = 1.15f, shadowG = 8f, shadowB = 16f, highR = 22f, highG = 8f, highB = -12f, vignette = 0.2f),
    CINEMATIC("Cinematic", FilterCategory.CINEMA, saturation = 0.85f, contrast = 1.2f, brightness = -8f, fade = 0.03f, shadowB = 10f, highR = 10f, vignette = 0.35f),
    MOODY("Moody", FilterCategory.CINEMA, saturation = 0.75f, contrast = 1.2f, brightness = -12f, gainR = 0.97f, gainB = 1.06f, vignette = 0.3f),
    BLOCKBUSTER("Blockbuster", FilterCategory.CINEMA, saturation = 1.1f, contrast = 1.3f, shadowB = 14f, highR = 16f, highG = 6f, vignette = 0.25f),
    DUSK("Dusk", FilterCategory.CINEMA, saturation = 1.1f, contrast = 1.1f, gainR = 1.08f, gainB = 1.04f, shadowB = 10f, highR = 18f, vignette = 0.2f),

    POLAROID("Polaroid", FilterCategory.RETRO, saturation = 0.85f, contrast = 0.95f, gainR = 1.06f, gainB = 0.92f, fade = 0.1f, grain = 0.25f, vignette = 0.3f),
    FADED("Faded", FilterCategory.RETRO, saturation = 0.75f, contrast = 0.9f, brightness = 6f, fade = 0.14f, grain = 0.15f),
    SEPIA("Sepia", FilterCategory.RETRO, saturation = 0f, contrast = 1.05f, gainR = 1.2f, gainB = 0.78f, fade = 0.05f, grain = 0.2f, vignette = 0.25f),
    SEVENTIES("70s", FilterCategory.RETRO, saturation = 1.1f, gainR = 1.1f, gainB = 0.85f, fade = 0.08f, grain = 0.3f, vignette = 0.35f),
    CROSS("Cross Process", FilterCategory.RETRO, saturation = 1.3f, contrast = 1.25f, shadowG = 8f, shadowB = 18f, highR = 16f, highG = 14f, highB = -14f),
    LOFI("Lo-Fi", FilterCategory.RETRO, saturation = 1.3f, contrast = 1.35f, grain = 0.2f, vignette = 0.5f),

    MONO("Mono", FilterCategory.MONO, saturation = 0f, contrast = 1.15f),
    SILVER("Silver", FilterCategory.MONO, saturation = 0f, contrast = 1.05f, brightness = 4f, fade = 0.05f),
    NOIR("Noir", FilterCategory.MONO, saturation = 0f, contrast = 1.45f, brightness = -10f, grain = 0.35f, vignette = 0.4f),
    HIGH_KEY("High Key", FilterCategory.MONO, saturation = 0f, contrast = 0.9f, brightness = 28f),
    COOL_MONO("Cool Mono", FilterCategory.MONO, saturation = 0f, contrast = 1.15f, gainR = 0.94f, gainB = 1.1f),

    COOL("Cool Blue", FilterCategory.CREATIVE, saturation = 1.05f, gainR = 0.92f, gainB = 1.12f),
    WARM("Warm Glow", FilterCategory.CREATIVE, saturation = 1.1f, brightness = 5f, gainR = 1.1f, gainG = 1.03f, gainB = 0.9f),
    PASTEL("Pastel", FilterCategory.CREATIVE, saturation = 0.8f, contrast = 0.85f, brightness = 18f, fade = 0.08f),
    CYBERPUNK("Cyberpunk", FilterCategory.CREATIVE, saturation = 1.35f, contrast = 1.2f, shadowR = 14f, shadowB = 26f, highR = 20f, highG = -6f, highB = 18f),
    FOREST("Forest", FilterCategory.CREATIVE, saturation = 1.2f, contrast = 1.1f, gainR = 0.95f, gainG = 1.1f, shadowG = 8f),
    SUNSET("Sunset", FilterCategory.CREATIVE, saturation = 1.25f, gainR = 1.15f, gainG = 0.98f, gainB = 0.85f, highR = 20f, highG = 6f);

    /** The global color math of this look as a 4x5 color matrix (0..255 offset units). */
    fun baseMatrix(): FloatArray {
        val m = ColorMatrix()
        m.setSaturation(saturation)
        m.postConcat(contrastMatrix(contrast, brightness))
        m.postConcat(ColorMatrix(floatArrayOf(
            gainR, 0f, 0f, 0f, 0f,
            0f, gainG, 0f, 0f, 0f,
            0f, 0f, gainB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        if (fade > 0f) m.postConcat(affineMatrix(1f - fade, fade * 255f * 0.35f))
        return m.array
    }
}

internal fun contrastMatrix(c: Float, offset: Float): ColorMatrix {
    val t = (1f - c) * 127.5f + offset
    return affineMatrix(c, t)
}

internal fun affineMatrix(scale: Float, offset: Float) = ColorMatrix(floatArrayOf(
    scale, 0f, 0f, 0f, offset,
    0f, scale, 0f, 0f, offset,
    0f, 0f, scale, 0f, offset,
    0f, 0f, 0f, 1f, 0f,
))

/** Everything that shapes how a photo looks, independent of how it was captured: a filter (with
 * its own strength) plus separate develop sliders. The same [Look] drives the live preview, the
 * saved capture, and the post-capture editor. */
data class Look(
    val filter: PhotoFilter = PhotoFilter.NATURAL,
    val strength: Float = 1f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val sharpness: Float = 0f,
) {
    /** The color-matrix-expressible part: filter (scaled by strength) plus exposure, saturation,
     * contrast, warmth and tint. This is exactly what the live preview shows. */
    fun toColorMatrix(): ColorMatrix {
        val base = filter.baseMatrix()
        val identity = ColorMatrix().array
        val blended = FloatArray(20) { identity[it] + (base[it] - identity[it]) * strength }
        val m = ColorMatrix(blended)
        if (exposure != 0f) m.postConcat(ColorMatrix(floatArrayOf(
            2f.pow(exposure), 0f, 0f, 0f, 0f,
            0f, 2f.pow(exposure), 0f, 0f, 0f,
            0f, 0f, 2f.pow(exposure), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        if (saturation != 0f) {
            val s = ColorMatrix()
            s.setSaturation((1f + saturation).coerceAtLeast(0f))
            m.postConcat(s)
        }
        if (contrast != 0f) m.postConcat(contrastMatrix(1f + contrast * 0.6f, 0f))
        if (warmth != 0f || tint != 0f) {
            val r = 1f + 0.18f * warmth + 0.04f * tint
            val g = 1f - 0.12f * tint
            val b = 1f - 0.18f * warmth + 0.04f * tint
            m.postConcat(ColorMatrix(floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, g, 0f, 0f, 0f,
                0f, 0f, b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        return m
    }

    val isNeutral: Boolean
        get() = (filter == PhotoFilter.NATURAL || strength == 0f) && exposure == 0f && contrast == 0f &&
            saturation == 0f && warmth == 0f && tint == 0f && highlights == 0f && shadows == 0f &&
            vignette == 0f && grain == 0f && sharpness == 0f
}

/** One develop slider: which [Look] field it drives and its range. */
enum class LookParam(val label: String, val min: Float, val max: Float) {
    EXPOSURE("Exposure", -2f, 2f),
    CONTRAST("Contrast", -1f, 1f),
    SATURATION("Saturation", -1f, 1f),
    WARMTH("Warmth", -1f, 1f),
    TINT("Tint", -1f, 1f),
    HIGHLIGHTS("Highlights", -1f, 1f),
    SHADOWS("Shadows", -1f, 1f),
    VIGNETTE("Vignette", 0f, 1f),
    GRAIN("Grain", 0f, 1f),
    SHARPNESS("Sharpness", 0f, 1f);

    fun get(l: Look): Float = when (this) {
        EXPOSURE -> l.exposure
        CONTRAST -> l.contrast
        SATURATION -> l.saturation
        WARMTH -> l.warmth
        TINT -> l.tint
        HIGHLIGHTS -> l.highlights
        SHADOWS -> l.shadows
        VIGNETTE -> l.vignette
        GRAIN -> l.grain
        SHARPNESS -> l.sharpness
    }

    fun set(l: Look, v: Float): Look = when (this) {
        EXPOSURE -> l.copy(exposure = v)
        CONTRAST -> l.copy(contrast = v)
        SATURATION -> l.copy(saturation = v)
        WARMTH -> l.copy(warmth = v)
        TINT -> l.copy(tint = v)
        HIGHLIGHTS -> l.copy(highlights = v)
        SHADOWS -> l.copy(shadows = v)
        VIGNETTE -> l.copy(vignette = v)
        GRAIN -> l.copy(grain = v)
        SHARPNESS -> l.copy(sharpness = v)
    }

    fun display(l: Look): String {
        val v = get(l)
        return if (this == EXPOSURE) String.format(java.util.Locale.US, "%+.1f EV", v) else "${(v * 100).toInt()}"
    }
}

/** Output crop, as width:height of the portrait frame. */
enum class AspectOption(val label: String, val widthOverHeight: Float) {
    R4_3("4:3", 3f / 4f),
    R16_9("16:9", 9f / 16f),
    R1_1("1:1", 1f),
    R2_1("2:1", 1f / 2f),
}

enum class GridStyle(val label: String) { OFF("Off"), THIRDS("Thirds"), GOLDEN("Golden"), SQUARE("Square") }

/** The per-pixel engine behind both the saved capture and the editor. */
object LookProcessor {
    fun apply(pixels: IntArray, w: Int, h: Int, look: Look) {
        if (look.isNeutral) return
        val m = look.toColorMatrix().array
        val f = look.filter
        val s = look.strength
        val shR = f.shadowR * s; val shG = f.shadowG * s; val shB = f.shadowB * s
        val hiR = f.highR * s; val hiG = f.highG * s; val hiB = f.highB * s
        val grain = (f.grain * s + look.grain).coerceAtMost(1.2f) * 30f
        val vig = (f.vignette * s + look.vignette).coerceAtMost(1f)
        val shadowsAdd = look.shadows * 40f
        val highlightsAdd = look.highlights * 40f
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxD2 = (cx * cx + cy * cy).coerceAtLeast(1f)
        var seed = 0x2545F491
        for (y in 0 until h) {
            val dy = y - cy
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                val r0 = ((p shr 16) and 0xFF).toFloat()
                val g0 = ((p shr 8) and 0xFF).toFloat()
                val b0 = (p and 0xFF).toFloat()
                var r = m[0] * r0 + m[1] * g0 + m[2] * b0 + m[4]
                var g = m[5] * r0 + m[6] * g0 + m[7] * b0 + m[9]
                var b = m[10] * r0 + m[11] * g0 + m[12] * b0 + m[14]
                val l = ((0.299f * r + 0.587f * g + 0.114f * b) / 255f).coerceIn(0f, 1f)
                val inv = 1f - l
                val sw = inv * inv
                val hw = l * l
                val add = shadowsAdd * sw + highlightsAdd * hw
                r += add + shR * sw + hiR * hw
                g += add + shG * sw + hiG * hw
                b += add + shB * sw + hiB * hw
                if (vig > 0f) {
                    val dx = x - cx
                    val d2 = (dx * dx + dy * dy) / maxD2
                    val factor = 1f - vig * 0.8f * d2 * d2
                    r *= factor; g *= factor; b *= factor
                }
                if (grain > 0f) {
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    val n = ((seed and 0xFFFF) / 32768f - 1f) * grain
                    r += n; g += n; b += n
                }
                val ri = r.toInt().coerceIn(0, 255)
                val gi = g.toInt().coerceIn(0, 255)
                val bi = b.toInt().coerceIn(0, 255)
                pixels[row + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }

    fun sharpen(pixels: IntArray, w: Int, h: Int, k: Float) {
        if (k <= 0.01f || w < 3 || h < 3) return
        val src = pixels.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = src[i]
                val u = src[i - w]
                val d = src[i + w]
                val l = src[i - 1]
                val r = src[i + 1]
                var out = 0xFF shl 24
                var shift = 16
                while (shift >= 0) {
                    val cv = (c shr shift) and 0xFF
                    val nb = ((u shr shift) and 0xFF) + ((d shr shift) and 0xFF) + ((l shr shift) and 0xFF) + ((r shr shift) and 0xFF)
                    val v = (cv + k * (4 * cv - nb)).toInt().coerceIn(0, 255)
                    out = out or (v shl shift)
                    shift -= 8
                }
                pixels[i] = out
            }
        }
    }

    /** Renders [look] over a copy of [src] (used by the editor's live preview). */
    fun render(src: Bitmap, look: Look): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        apply(pixels, w, h, look)
        sharpen(pixels, w, h, look.sharpness * 0.8f)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** Exposure fusion of a bracket (under/normal/over): each pixel takes mostly from whichever frame
 * exposed it best, with the per-frame weights computed on a coarse grid and smoothed so the
 * blend has no visible seams. Shadows come from the bright frame, highlights from the dark one. */
object HdrFusion {
    private const val CELL = 8

    fun fuse(frames: List<IntArray>, w: Int, h: Int): IntArray {
        val n = frames.size
        val gw = (w + CELL - 1) / CELL
        val gh = (h + CELL - 1) / CELL
        val grids = Array(n) { FloatArray(gw * gh) }
        for (i in 0 until n) {
            val px = frames[i]
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    var sum = 0f
                    var count = 0
                    val y0 = gy * CELL
                    val x0 = gx * CELL
                    for (yy in 0 until CELL step 2) {
                        val y = y0 + yy
                        if (y >= h) break
                        for (xx in 0 until CELL step 2) {
                            val x = x0 + xx
                            if (x >= w) break
                            val p = px[y * w + x]
                            sum += (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
                            count++
                        }
                    }
                    val l = if (count > 0) sum / count else 0.5f
                    grids[i][gy * gw + gx] = exp(-((l - 0.5f) * (l - 0.5f)) / (2f * 0.2f * 0.2f)) + 1e-3f
                }
            }
        }
        repeat(3) { for (i in 0 until n) grids[i] = blur(grids[i], gw, gh) }
        for (c in 0 until gw * gh) {
            var total = 0f
            for (i in 0 until n) total += grids[i][c]
            for (i in 0 until n) grids[i][c] /= total
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / CELL - 0.5f).coerceIn(0f, gh - 1f)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, gh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / CELL - 0.5f).coerceIn(0f, gw - 1f)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, gw - 1)
                val tx = fx - x0
                var r = 0f
                var g = 0f
                var b = 0f
                for (i in 0 until n) {
                    val gr = grids[i]
                    val wgt = (gr[y0 * gw + x0] * (1 - tx) + gr[y0 * gw + x1] * tx) * (1 - ty) +
                        (gr[y1 * gw + x0] * (1 - tx) + gr[y1 * gw + x1] * tx) * ty
                    val p = frames[i][y * w + x]
                    r += wgt * ((p shr 16) and 0xFF)
                    g += wgt * ((p shr 8) and 0xFF)
                    b += wgt * (p and 0xFF)
                }
                out[y * w + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        val dst = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) { sum += src[yy * w + xx]; count++ }
            }
            dst[y * w + x] = sum / count
        }
        return dst
    }
}

/** The after-capture pipeline: decode, rotate upright from EXIF, mirror (selfies), crop to the
 * chosen ratio, fuse an HDR bracket when there is one, then run the [Look] and re-encode. */
object PhotoProcessor {
    private const val SINGLE_MAX_PIXELS = 16_800_000
    private const val BRACKET_MAX_PIXELS = 8_000_000

    /** Decodes [bytes] upright (EXIF rotation applied), subsampled so it stays under [maxPixels]. */
    fun decodeUpright(bytes: ByteArray, maxPixels: Int): Bitmap? {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (m.isIdentity) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun cropAndMirror(src: Bitmap, ratio: AspectOption, mirror: Boolean): Bitmap {
        val target = ratio.widthOverHeight
        val current = src.width.toFloat() / src.height
        var cx = 0
        var cy = 0
        var cw = src.width
        var ch = src.height
        if (current > target) {
            cw = (src.height * target).toInt().coerceAtLeast(1)
            cx = (src.width - cw) / 2
        } else if (current < target) {
            ch = (src.width / target).toInt().coerceAtLeast(1)
            cy = (src.height - ch) / 2
        }
        val flip = Matrix().apply { if (mirror) postScale(-1f, 1f) }
        val out = Bitmap.createBitmap(src, cx, cy, cw, ch, flip, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val p = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(p, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return p
    }

    private fun encode(pixels: IntArray, w: Int, h: Int, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream(1 shl 20)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    /** [frames] is one JPEG, or an HDR bracket (dark, normal, bright) fused with [hdrStrength]
     * 0..1 (0 = just the normal frame, 1 = fully fused). */
    fun process(frames: List<ByteArray>, look: Look, hdrStrength: Float, ratio: AspectOption, mirror: Boolean, quality: Int): ByteArray {
        val maxPixels = if (frames.size > 1) BRACKET_MAX_PIXELS else SINGLE_MAX_PIXELS
        val bitmaps = frames.map { bytes ->
            val upright = decodeUpright(bytes, maxPixels) ?: return frames[frames.size / 2]
            cropAndMirror(upright, ratio, mirror)
        }
        val w = bitmaps[0].width
        val h = bitmaps[0].height
        val pixelFrames = bitmaps.map { pixelsOf(it) }
        bitmaps.forEach { it.recycle() }

        var pixels = pixelFrames[pixelFrames.size / 2]
        if (pixelFrames.size > 1 && pixelFrames.all { it.size == pixels.size } && hdrStrength > 0f) {
            val fused = HdrFusion.fuse(pixelFrames, w, h)
            if (hdrStrength >= 0.999f) {
                pixels = fused
            } else {
                for (i in pixels.indices) {
                    val a = pixels[i]
                    val b = fused[i]
                    fun mix(shift: Int): Int {
                        val av = (a shr shift) and 0xFF
                        val bv = (b shr shift) and 0xFF
                        return (av + (bv - av) * hdrStrength).toInt().coerceIn(0, 255)
                    }
                    pixels[i] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                }
            }
        }

        LookProcessor.apply(pixels, w, h, look)
        LookProcessor.sharpen(pixels, w, h, 0.2f + look.sharpness * 0.8f)
        return encode(pixels, w, h, quality)
    }

    // ---- post-capture editing ----

    /** Rotation/flip/crop applied in the editor, before the look. */
    data class Transform(val quarterTurns: Int = 0, val flipH: Boolean = false, val crop: AspectOption? = null)

    fun transform(src: Bitmap, t: Transform): Bitmap {
        val m = Matrix()
        if (t.quarterTurns % 4 != 0) m.postRotate(90f * (t.quarterTurns % 4))
        if (t.flipH) m.postScale(-1f, 1f)
        var out = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val crop = t.crop
        if (crop != null) {
            val portrait = out.width < out.height
            val target = if (portrait) crop.widthOverHeight else 1f / crop.widthOverHeight
            val current = out.width.toFloat() / out.height
            var cx = 0
            var cy = 0
            var cw = out.width
            var ch = out.height
            if (current > target) {
                cw = (out.height * target).toInt().coerceAtLeast(1)
                cx = (out.width - cw) / 2
            } else if (current < target) {
                ch = (out.width / target).toInt().coerceAtLeast(1)
                cy = (out.height - ch) / 2
            }
            if (cw != out.width || ch != out.height) {
                val cropped = Bitmap.createBitmap(out, cx, cy, cw, ch)
                if (out !== src) out.recycle()
                out = cropped
            }
        }
        return out
    }

    /** Re-renders a saved photo at full resolution with the editor's transform and look. */
    fun processEdit(bytes: ByteArray, look: Look, t: Transform, quality: Int): ByteArray? {
        val upright = decodeUpright(bytes, SINGLE_MAX_PIXELS) ?: return null
        val transformed = transform(upright, t)
        if (transformed !== upright) upright.recycle()
        val w = transformed.width
        val h = transformed.height
        val pixels = pixelsOf(transformed)
        transformed.recycle()
        LookProcessor.apply(pixels, w, h, look)
        LookProcessor.sharpen(pixels, w, h, look.sharpness * 0.8f)
        return encode(pixels, w, h, quality)
    }
}
