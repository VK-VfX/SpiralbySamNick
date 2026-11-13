package com.nikoezveracamspiral

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.pow

enum class FilterCategory(val label: String) {
    PIXEL("Pixel"), BASIC("Basic"), FILM("Film"), CINEMA("Cinema"), RETRO("Retro"), MONO("Mono"), CREATIVE("Creative")
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
    /** Extra saturation for the reds, greens and blues only (1 = untouched). Saved photo only. */
    val satR: Float = 1f, val satG: Float = 1f, val satB: Float = 1f,
    /** Leaves the picture as the pipeline made it: no extra sharpening on top. */
    val minimal: Boolean = false,
) {
    // Pixel's looks: four base styles, then six creative presets. The saved photo gets the whole
    // recipe (including the red/green/blue saturation); the live preview shows the global part.
    PX_ORIGINAL("Original", FilterCategory.PIXEL, saturation = 1.06f, contrast = 1.06f),
    PX_NATURAL("Px Natural", FilterCategory.PIXEL, saturation = 0.88f, contrast = 0.92f, fade = 0.03f, highR = -3f, highG = -3f, highB = -3f),
    PX_SHADOWS("Px Shadows", FilterCategory.PIXEL, saturation = 1.04f, contrast = 1.22f, brightness = -8f, shadowR = -12f, shadowG = -12f, shadowB = -10f, highR = -8f, highG = -8f, highB = -8f),
    PX_VANILLA("Px Vanilla", FilterCategory.PIXEL, saturation = 1.3f, contrast = 0.97f, brightness = 6f, gainR = 1.07f, gainG = 1.03f, gainB = 0.9f, fade = 0.04f, highR = 10f, highG = 6f),
    EDITORIAL("Editorial", FilterCategory.PIXEL, saturation = 0.95f, contrast = 1.2f, brightness = -6f, shadowR = -8f, shadowG = -8f, shadowB = -8f, satB = 0.55f, highB = 6f),
    VELVET("Velvet", FilterCategory.PIXEL, saturation = 1.12f, contrast = 1.08f, gainR = 1.03f, satR = 1.25f, satG = 0.85f, satB = 1.3f, shadowB = 4f, highB = 4f),
    PX_CLASSIC("Px Classic", FilterCategory.PIXEL, saturation = 0.85f, contrast = 0.9f, gainR = 1.07f, gainG = 1.01f, gainB = 0.9f, fade = 0.08f, shadowR = 6f, highR = 10f, highG = 4f, grain = 0.45f, vignette = 0.15f),
    DIGI("Digi", FilterCategory.PIXEL, saturation = 0.7f, contrast = 1.35f),
    BLACK_TIE("Black Tie", FilterCategory.PIXEL, saturation = 0f, contrast = 1.38f, brightness = -4f),
    MINIMAL("Minimal", FilterCategory.PIXEL, saturation = 0.97f, contrast = 0.98f, brightness = 3f, fade = 0.02f, minimal = true),

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
    /** An imported LUT replaces [filter] when set; [strength] then blends the LUT in. */
    val lut: Lut? = null,
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
    /** Boosts muted colours more than strong ones. */
    val vibrance: Float = 0f,
) {
    /** The sharpening every shot gets before its own slider; Minimal adds none. */
    val baseSharpen: Float get() = if (lut == null && filter.minimal) 0f else 0.2f

    /** The color-matrix-expressible part: filter (scaled by strength) plus exposure, saturation,
     * contrast, warmth and tint. This is exactly what the live preview shows. */
    fun toColorMatrix(useLutFit: Boolean = true): ColorMatrix {
        val base = when {
            lut != null && useLutFit -> lut.fit
            lut != null -> ColorMatrix().array
            else -> filter.baseMatrix()
        }
        val identity = ColorMatrix().array
        val blended = FloatArray(20) { identity[it] + (base[it] - identity[it]) * strength }
        val m = ColorMatrix(blended)
        if (exposure != 0f) m.postConcat(ColorMatrix(floatArrayOf(
            2f.pow(exposure), 0f, 0f, 0f, 0f,
            0f, 2f.pow(exposure), 0f, 0f, 0f,
            0f, 0f, 2f.pow(exposure), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        // Vibrance is per pixel in the saved photo; the live preview approximates it with a little saturation.
        val sat = saturation + vibrance * 0.4f
        if (sat != 0f) {
            val s = ColorMatrix()
            s.setSaturation((1f + sat).coerceAtLeast(0f))
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
        get() = (filter == PhotoFilter.NATURAL || strength == 0f) && (lut == null || strength == 0f) && exposure == 0f && contrast == 0f &&
            saturation == 0f && warmth == 0f && tint == 0f && highlights == 0f && shadows == 0f &&
            vignette == 0f && grain == 0f && sharpness == 0f && vibrance == 0f
}

/** One develop slider: which [Look] field it drives and its range. */
enum class LookParam(val label: String, val min: Float, val max: Float, val steps: Int = 40) {
    EXPOSURE("Exposure", -2f, 2f),
    CONTRAST("Contrast", -1f, 1f),
    SATURATION("Saturation", -1f, 1f),
    VIBRANCE("Vibrance", -1f, 1f),
    WARMTH("Warmth", -1f, 1f, 20),
    TINT("Tint", -1f, 1f),
    HIGHLIGHTS("Highlights", -1f, 1f),
    SHADOWS("Shadows", -1f, 1f),
    VIGNETTE("Vignette", 0f, 1f),
    GRAIN("Grain", 0f, 1f, 3),
    SHARPNESS("Sharpness", 0f, 1f, 15);

    fun get(l: Look): Float = when (this) {
        EXPOSURE -> l.exposure
        CONTRAST -> l.contrast
        SATURATION -> l.saturation
        VIBRANCE -> l.vibrance
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
        VIBRANCE -> l.copy(vibrance = v)
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
        return when (this) {
            EXPOSURE -> String.format(java.util.Locale.US, "%+.1f EV", v)
            // Pixel's scales: warmth -10..+10, sharpness 0..15, grain in four steps.
            WARMTH -> String.format(java.util.Locale.US, "%+d", Math.round(v * 10f))
            SHARPNESS -> "${Math.round(v * 15f)}"
            GRAIN -> listOf("None", "Weak", "Moderate", "Strong")[Math.round(v * 3f).coerceIn(0, 3)]
            else -> "${(v * 100).toInt()}"
        }
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
        val m = look.toColorMatrix(useLutFit = false).array
        val lut = look.lut
        val f = if (lut != null) PhotoFilter.NATURAL else look.filter
        val s = look.strength
        val shR = f.shadowR * s; val shG = f.shadowG * s; val shB = f.shadowB * s
        val hiR = f.highR * s; val hiG = f.highG * s; val hiB = f.highB * s
        val grain = (f.grain * s + look.grain).coerceAtMost(1.2f) * 30f
        val vig = (f.vignette * s + look.vignette).coerceAtMost(1f)
        val shadowsAdd = look.shadows * 40f
        val highlightsAdd = look.highlights * 40f
        val vib = look.vibrance
        val sR = 1f + (f.satR - 1f) * s
        val sG = 1f + (f.satG - 1f) * s
        val sB = 1f + (f.satB - 1f) * s
        val selective = sR != 1f || sG != 1f || sB != 1f
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxD2 = (cx * cx + cy * cy).coerceAtLeast(1f)
        parallelFor(h) { y ->
            val lutOut = FloatArray(3)
            var seed = 0x2545F491 xor ((y + 1) * -1640531535)
            val dy = y - cy
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                val r0 = ((p shr 16) and 0xFF).toFloat()
                val g0 = ((p shr 8) and 0xFF).toFloat()
                val b0 = (p and 0xFF).toFloat()
                var ir = r0
                var ig = g0
                var ib = b0
                if (lut != null) {
                    lut.sample(r0, g0, b0, lutOut)
                    ir = r0 + (lutOut[0] - r0) * s
                    ig = g0 + (lutOut[1] - g0) * s
                    ib = b0 + (lutOut[2] - b0) * s
                }
                var r = m[0] * ir + m[1] * ig + m[2] * ib + m[4]
                var g = m[5] * ir + m[6] * ig + m[7] * ib + m[9]
                var b = m[10] * ir + m[11] * ig + m[12] * ib + m[14]
                if (selective || vib != 0f) {
                    val mx = maxOf(r, g, b)
                    val c = mx - minOf(r, g, b)
                    if (c > 1f) {
                        val lum = 0.299f * r + 0.587f * g + 0.114f * b
                        var k = 1f
                        if (selective) {
                            val wr = ((r - maxOf(g, b)) / c).coerceIn(0f, 1f)
                            val wg = ((g - maxOf(r, b)) / c).coerceIn(0f, 1f)
                            val wb = ((b - maxOf(r, g)) / c).coerceIn(0f, 1f)
                            k += wr * (sR - 1f) + wg * (sG - 1f) + wb * (sB - 1f)
                        }
                        if (vib != 0f) {
                            val sat = c / mx.coerceAtLeast(1f)
                            k *= (1f + vib * 0.9f * (if (vib > 0f) 1f - sat else 1f)).coerceAtLeast(0f)
                        }
                        r = lum + (r - lum) * k
                        g = lum + (g - lum) * k
                        b = lum + (b - lum) * k
                    }
                }
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
        parallelFor(h - 2) { yy ->
            val y = yy + 1
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

/** The after-capture pipeline: decode, rotate upright from EXIF, mirror (selfies), crop to the
 * chosen ratio, fuse an HDR bracket when there is one, then run the [Look] and re-encode. */
object PhotoProcessor {
    private var singleMaxPixels = 16_800_000
    private var burstMaxPixels = 13_000_000

    /** Sets the pixel budgets from the phone's [DeviceProfile]. */
    fun configure(single: Int, burst: Int) {
        singleMaxPixels = single
        burstMaxPixels = burst
    }

    /** Display P3, the colour space photos are processed and saved in (null on very old Android). */
    internal val wideGamut: android.graphics.ColorSpace? by lazy {
        try {
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        } catch (e: Exception) {
            null
        }
    }

    /** The matrix that turns pixels stored with this EXIF [orientation] upright. */
    fun orientationMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        return m
    }

    /** Decodes [bytes] upright (EXIF rotation applied), subsampled so it stays under [maxPixels]. */
    fun decodeUpright(bytes: ByteArray, maxPixels: Int): Bitmap? {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Subsample by powers of two only while the image is more than twice the budget, then scale
        // the rest of the way: a 16 MP Pixel 8a frame under a 13 MP budget stays ~13 MP instead of
        // dropping straight to 4 MP.
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels.toLong() * 2) sample *= 2
        var decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                // Decode into Display P3 so wide-gamut colours from the camera survive the whole pipeline.
                wideGamut?.let { inPreferredColorSpace = it }
            },
        ) ?: return null
        if (decoded.width.toLong() * decoded.height > maxPixels) {
            val scale = kotlin.math.sqrt(maxPixels.toDouble() / (decoded.width.toDouble() * decoded.height))
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== decoded) decoded.recycle()
            decoded = scaled
        }
        val m = orientationMatrix(orientation)
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

    /** The bitmap's pixels as ARGB ints, in the bitmap's own colour space (Display P3 for photos
     * decoded here). getPixels would convert them to sRGB and clip the wide-gamut colours. */
    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val n = bitmap.width * bitmap.height
        val p = IntArray(n)
        if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap.copyPixelsToBuffer(java.nio.IntBuffer.wrap(p))
            // Memory order is R, G, B, A; as a little-endian int that is 0xAABBGGRR.
            parallelFor(bitmap.height) { y ->
                val row = y * bitmap.width
                for (i in row until row + bitmap.width) {
                    val v = p[i]
                    p[i] = (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v shr 16) and 0xFF)
                }
            }
        } else {
            bitmap.getPixels(p, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        return p
    }

    private fun encode(pixels: IntArray, w: Int, h: Int, quality: Int, gainmap: android.graphics.Gainmap? = null): ByteArray {
        val space = wideGamut
        val bitmap: Bitmap
        if (space != null) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, false, space)
            val raw = IntArray(w * h)
            parallelFor(h) { y ->
                val row = y * w
                for (i in row until row + w) {
                    val v = pixels[i]
                    raw[i] = (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v shr 16) and 0xFF)
                }
            }
            bitmap.copyPixelsFromBuffer(java.nio.IntBuffer.wrap(raw))
        } else {
            bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
        val stream = ByteArrayOutputStream(1 shl 20)
        if (gainmap != null && android.os.Build.VERSION.SDK_INT >= 34) {
            bitmap.gainmap = gainmap
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (!UltraHdr.isUltraHdr(stream.toByteArray())) {
                // This Android didn't write the HDR map; save the ordinary JPEG instead.
                android.util.Log.w("PhotoProcessor", "Ultra HDR encode not supported here; saving a plain JPEG")
                bitmap.gainmap = null
                stream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
        bitmap.recycle()
        return stream.toByteArray()
    }

    /** How a burst is turned into one frame. */
    sealed interface ToneMode {
        data object None : ToneMode
        /** Underexposed burst (by [underEv] stops) merged and tone-mapped; [strength] is 0..1. */
        data class HdrPlus(val strength: Float, val underEv: Float) : ToneMode
        data object Night : ToneMode
        /** Frames at normal exposure aligned and averaged (less noise, more detail), no tone change. */
        data object Stack : ToneMode
    }

    private fun loadFrame(bytes: ByteArray, ratio: AspectOption, mirror: Boolean, maxPixels: Int): DecodedFrame? {
        val upright = decodeUpright(bytes, maxPixels) ?: return null
        val cropped = cropAndMirror(upright, ratio, mirror)
        val pixels = pixelsOf(cropped)
        val frame = DecodedFrame(pixels, cropped.width, cropped.height)
        cropped.recycle()
        return frame
    }

    /**
     * The whole after-capture pipeline, run off the main thread after the shutter has already
     * returned: [frames] is one JPEG or a burst. A burst is aligned and merged and tone-mapped
     * per [tone]; Portrait then blurs the background behind the subject ([portraitBlur] 0..1, 0 =
     * off); finally the [Look] is applied and the result encoded.
     */
    fun processShots(
        frames: List<ByteArray>,
        tone: ToneMode,
        look: Look,
        ratio: AspectOption,
        mirror: Boolean,
        quality: Int,
        portraitBlur: Float,
        noiseScale: Float = 1f,
        zoomRatio: Float = 1f,
        keepUltraHdr: Boolean = false,
        portraitStyle: PortraitStyle = PortraitStyle.NATURAL,
        depth: DepthCapture? = null,
        subjectTap: Pair<Float, Float>? = null,
        portraitWorkSize: Int = 1024,
        portraitSink: ((ByteArray, PortraitAnalysis) -> Unit)? = null,
        watermark: WatermarkSettings? = null,
        watermarkLogo: Bitmap? = null,
        watermarkDetails: String = "",
    ): ByteArray {
        val refIndex = frames.size / 2
        val maxPixels = if (frames.size > 1) burstMaxPixels else singleMaxPixels
        val merged: DecodedFrame = if (frames.size > 1 && tone != ToneMode.None) {
            BurstMerge.merge(
                frames, refIndex,
                load = { loadFrame(it, ratio, mirror, maxPixels) },
                lutFor = { refMean ->
                    when (tone) {
                        is ToneMode.HdrPlus -> ToneMap.hdrPlus(tone.strength, tone.underEv)
                        ToneMode.Night -> ToneMap.night(refMean)
                        ToneMode.None, ToneMode.Stack -> IntArray(1024) { (it / 4).coerceIn(0, 255) }
                    }
                },
                noiseScale = noiseScale,
            ) ?: return frames[refIndex]
        } else {
            loadFrame(frames[refIndex], ratio, mirror, maxPixels) ?: return frames[refIndex]
        }
        val w = merged.w
        val h = merged.h
        val pixels = merged.pixels

        // Colour, look and sharpening come first, so the Portrait blur (and the editable copy kept
        // before it) works on the finished picture.
        LookProcessor.apply(pixels, w, h, look)
        LookProcessor.sharpen(pixels, w, h, look.baseSharpen + look.sharpness * 0.8f)
        if (zoomRatio >= 2f) {
            // Digital zoom leaves a soft, upscaled image: a wider-radius unsharp mask brings the
            // detail back, more of it the further in the shot was zoomed.
            val strength = (0.35f * (kotlin.math.ln(zoomRatio) / kotlin.math.ln(2f))).coerceAtMost(1.1f)
            ImageOps.unsharp(pixels, w, h, radius = 2, amount = strength, threshold = 3)
        }
        if (portraitBlur > 0f) {
            try {
                // The picture before the blur is kept (privately) so the blur can be changed later.
                val before = if (portraitSink != null) encode(pixels, w, h, 95, null) else null
                // The measured depth cues are made before anything touches the pixels.
                val cues = depth?.let { PortraitDepth.cues(it, pixels, w, h, ratio, mirror) }
                val found = arrayOfNulls<PortraitAnalysis>(1)
                val blurred = PortraitEffect.apply(
                    pixels, w, h, portraitBlur, portraitStyle, cues, subjectTap, portraitWorkSize,
                    analysisSink = { found[0] = it },
                )
                val analysis = found[0]
                if (blurred && before != null && analysis != null) portraitSink?.invoke(before, analysis)
            } catch (e: Exception) {
                android.util.Log.w("PhotoProcessor", "Portrait blur failed; keeping the unblurred photo", e)
            }
        }
        var gainmap: android.graphics.Gainmap? = null
        if (keepUltraHdr && android.os.Build.VERSION.SDK_INT >= 34 && UltraHdr.isUltraHdr(frames[refIndex])) {
            gainmap = try {
                UltraHdr.gainmapFor(frames[refIndex], ratio, mirror)
            } catch (e: Exception) {
                android.util.Log.w("PhotoProcessor", "Couldn't carry over the HDR gain map", e)
                null
            }
        }
        if (watermark != null && watermark.on) {
            val marked = Watermark.apply(watermarkLogo, pixels, w, h, watermark, watermarkDetails)
            // A frame bar makes the picture taller, so the HDR gain map would no longer fit it.
            if (marked.width != w || marked.height != h) gainmap = null
            return encode(marked.pixels, marked.width, marked.height, quality, gainmap)
        }
        return encode(pixels, w, h, quality, gainmap)
    }

    /** A finished photo (a JPEG) with the watermark put on it, for photos that skip the usual processing. */
    fun watermarkOnly(jpeg: ByteArray, quality: Int, logo: Bitmap?, settings: WatermarkSettings, details: String, look: Look? = null): ByteArray? {
        val bitmap = decodeUpright(jpeg, singleMaxPixels) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val pixels = pixelsOf(bitmap)
        bitmap.recycle()
        // A finished picture from the system's Night engine still gets the chosen look.
        if (look != null && !look.isNeutral) {
            LookProcessor.apply(pixels, w, h, look)
            LookProcessor.sharpen(pixels, w, h, look.sharpness * 0.8f)
        }
        if (!settings.on) return encode(pixels, w, h, quality, null)
        val marked = Watermark.apply(logo, pixels, w, h, settings, details)
        return encode(marked.pixels, marked.width, marked.height, quality, null)
    }

    // ---- re-doing a saved portrait's blur ----

    /** A saved portrait with its blur re-done, as a bitmap at most [maxPixels] big (for the editor's live preview), or null. */
    fun portraitPreview(preBlurJpeg: ByteArray, analysis: PortraitAnalysis, style: PortraitStyle, strength: Float, tap: Pair<Float, Float>?, maxPixels: Int): Bitmap? {
        val bitmap = decodeUpright(preBlurJpeg, maxPixels) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val pixels = pixelsOf(bitmap)
        bitmap.recycle()
        if (!PortraitEffect.rerender(pixels, w, h, analysis, style, strength, tap)) return null
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A saved portrait with its blur re-done at full size, as a JPEG, or null. */
    fun portraitRerender(
        preBlurJpeg: ByteArray, analysis: PortraitAnalysis, style: PortraitStyle, strength: Float, tap: Pair<Float, Float>?, quality: Int,
        watermark: WatermarkSettings? = null, watermarkLogo: Bitmap? = null, watermarkDetails: String = "",
    ): ByteArray? {
        val bitmap = decodeUpright(preBlurJpeg, singleMaxPixels) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val pixels = pixelsOf(bitmap)
        bitmap.recycle()
        if (!PortraitEffect.rerender(pixels, w, h, analysis, style, strength, tap)) return null
        if (watermark != null && watermark.on) {
            val marked = Watermark.apply(watermarkLogo, pixels, w, h, watermark, watermarkDetails)
            return encode(marked.pixels, marked.width, marked.height, quality, null)
        }
        return encode(pixels, w, h, quality, null)
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

    /** A copy of [src] with the hot-pixel [spots] (0..1 positions) painted out. */
    fun healSpots(src: Bitmap, spots: List<Pair<Float, Float>>): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = pixelsOf(src)
        SpotHeal.heal(pixels, w, h, spots)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Moves a tap on [bmp] to the brightest point right next to it. */
    fun snapSpot(bmp: Bitmap, nx: Float, ny: Float): Pair<Float, Float> {
        val w = bmp.width
        val h = bmp.height
        val reach = maxOf(4, (maxOf(w, h) * 0.012f).toInt())
        val x0 = ((nx * (w - 1)).toInt() - reach).coerceAtLeast(0)
        val y0 = ((ny * (h - 1)).toInt() - reach).coerceAtLeast(0)
        val x1 = ((nx * (w - 1)).toInt() + reach).coerceAtMost(w - 1)
        val y1 = ((ny * (h - 1)).toInt() + reach).coerceAtMost(h - 1)
        val ww = x1 - x0 + 1
        val hh = y1 - y0 + 1
        if (ww < 2 || hh < 2) return Pair(nx, ny)
        val window = IntArray(ww * hh)
        bmp.getPixels(window, 0, ww, x0, y0, ww, hh)
        val local = SpotHeal.snap(window, ww, hh, (nx * (w - 1) - x0) / (ww - 1).coerceAtLeast(1), (ny * (h - 1) - y0) / (hh - 1).coerceAtLeast(1))
        return Pair((x0 + local.first * (ww - 1)) / (w - 1), (y0 + local.second * (hh - 1)) / (h - 1))
    }

    /** Isolated bright specks in the dark parts of a photo (hot pixels, mostly), as 0..1 positions; at most [limit]. */
    fun findSpecks(bytes: ByteArray, limit: Int = 8): List<Pair<Float, Float>> {
        val bmp = decodeUpright(bytes, singleMaxPixels) ?: return emptyList()
        try {
            val w = bmp.width
            val h = bmp.height
            val luma = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    luma[y * w + x] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
                }
            }
            return HotPixels.find(luma, w, h, minPeak = 60, minContrast = 40, maxRing = 45, limit = limit).map { Pair(it.x, it.y) }
        } finally {
            bmp.recycle()
        }
    }

    /** Re-renders a saved photo at full resolution with the editor's transform and look. */
    fun processEdit(bytes: ByteArray, look: Look, t: Transform, quality: Int, spots: List<Pair<Float, Float>> = emptyList()): ByteArray? {
        val upright = decodeUpright(bytes, singleMaxPixels) ?: return null
        val healedFrame = if (spots.isEmpty()) upright else healSpots(upright, spots).also { upright.recycle() }
        val transformed = transform(healedFrame, t)
        if (transformed !== healedFrame && healedFrame !== upright) healedFrame.recycle()
        if (transformed !== upright && transformed !== healedFrame) upright.recycle()
        val w = transformed.width
        val h = transformed.height
        val pixels = pixelsOf(transformed)
        transformed.recycle()
        LookProcessor.apply(pixels, w, h, look)
        LookProcessor.sharpen(pixels, w, h, look.sharpness * 0.8f)
        return encode(pixels, w, h, quality)
    }
}
