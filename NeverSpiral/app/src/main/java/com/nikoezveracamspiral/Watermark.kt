package com.nikoezveracamspiral

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import java.util.Locale
import kotlin.math.roundToInt

/** Where the watermark sits: small on the photo itself, or in a bar added below it. */
enum class WatermarkStyle(val label: String) {
    CORNER("On the photo"),
    BAR("Frame bar"),
}

enum class WatermarkPosition(val label: String) {
    LEFT("Left"),
    CENTER("Centre"),
    RIGHT("Right"),
}

/**
 * What the watermark says and how it looks.
 *
 * @property text the main line, "Shot on Niko's Cam" unless changed.
 * @property logo show the app logo (the N and camera, white on black) beside the text.
 * @property device add the phone's name under the text.
 * @property details add the lens, aperture, shutter and ISO of the shot.
 * @property dark for the frame bar: dark instead of white.
 */
data class WatermarkSettings(
    val on: Boolean = false,
    val style: WatermarkStyle = WatermarkStyle.CORNER,
    val text: String = DEFAULT_TEXT,
    val logo: Boolean = true,
    val device: Boolean = true,
    val details: Boolean = true,
    val dark: Boolean = false,
    val position: WatermarkPosition = WatermarkPosition.LEFT,
) {
    /** The main line, falling back to the default if it was cleared. */
    val shownText: String get() = text.trim().ifEmpty { DEFAULT_TEXT }

    companion object {
        const val DEFAULT_TEXT = "Shot on Niko's Cam"
    }
}

/**
 * Stamps a custom "Shot on Niko's Cam" watermark, with the app's logo, onto a finished photo.
 * Everything is sized from the photo's shorter side, so it looks the same on a 12 MP photo and a
 * small one.
 */
object Watermark {
    /** The picture with the watermark on it (a frame bar makes it taller). */
    class Result(val pixels: IntArray, val width: Int, val height: Int)

    private var cachedLogo: Bitmap? = null

    /** The app's logo as a square bitmap (the glyph from the launcher icon, white on black), or null if it can't be loaded. */
    @Synchronized
    fun logo(context: Context): Bitmap? {
        cachedLogo?.let { return it }
        return try {
            val glyph = BitmapFactory.decodeResource(
                context.resources, R.drawable.ic_launcher_foreground,
                BitmapFactory.Options().apply { inScaled = false },
            ) ?: return null
            val size = 256
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val square = RectF(0f, 0f, size.toFloat(), size.toFloat())
            canvas.drawRoundRect(square, size * 0.22f, size * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            // An adaptive icon's foreground keeps a margin all round; only its middle two thirds shows.
            val margin = glyph.width / 6
            canvas.drawBitmap(glyph, Rect(margin, margin, glyph.width - margin, glyph.height - margin), square, Paint(Paint.FILTER_BITMAP_FLAG))
            glyph.recycle()
            cachedLogo = out
            out
        } catch (e: Exception) {
            android.util.Log.w("Watermark", "Couldn't load the logo", e)
            null
        }
    }

    /** "6.9 mm · f/1.7 · 1/120 s · ISO 50" from a photo's details, with whatever of them is known. */
    fun detailLine(meta: PhotoMeta): String {
        val parts = ArrayList<String>()
        meta.focalLengthMm?.let { parts.add(String.format(Locale.US, "%.1f mm", it)) }
        meta.aperture?.let { parts.add("f/" + String.format(Locale.US, "%.1f", it)) }
        meta.exposureNanos?.let { if (it > 0) parts.add(shutterText(it)) }
        meta.iso?.let { parts.add("ISO $it") }
        return parts.joinToString("  ·  ")
    }

    internal fun shutterText(nanos: Long): String {
        val seconds = nanos / 1e9
        return if (seconds >= 0.5) String.format(Locale.US, "%.1f s", seconds) else "1/${(1.0 / seconds).roundToInt()} s"
    }

    /** Puts the watermark on [pixels] ([w] x [h], ARGB). The array is changed in place unless the style adds a bar, which returns a taller picture. */
    fun apply(logo: Bitmap?, pixels: IntArray, w: Int, h: Int, s: WatermarkSettings, detailLine: String, deviceName: String = Build.MODEL ?: ""): Result {
        val unit = minOf(w, h) / 100f
        val lines = ArrayList<Pair<String, Float>>() // text and its relative size
        lines.add(s.shownText to 1f)
        if (s.device && deviceName.isNotBlank()) lines.add(deviceName to 0.7f)
        val details = if (s.details) detailLine else ""
        return when (s.style) {
            WatermarkStyle.CORNER -> corner(logo, pixels, w, h, s, lines, details, unit)
            WatermarkStyle.BAR -> bar(logo, pixels, w, h, s, lines, details, unit)
        }
    }

    private fun titlePaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun plainPaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** Small and unobtrusive on the photo's lower edge, with a soft shadow so it reads on any picture. */
    private fun corner(
        logo: Bitmap?, pixels: IntArray, w: Int, h: Int, s: WatermarkSettings,
        lines: List<Pair<String, Float>>, details: String, unit: Float,
    ): Result {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val canvas = Canvas(bitmap)
        val margin = 3.2f * unit
        val logoSize = if (s.logo && logo != null) 6.8f * unit else 0f
        val gap = if (logoSize > 0f) 1.9f * unit else 0f

        val textLines = ArrayList<Triple<String, Paint, Float>>() // text, paint, line height
        lines.forEachIndexed { index, (text, relative) ->
            val size = 2.9f * unit * relative
            val paint = if (index == 0) titlePaint(size, Color.WHITE) else plainPaint(size, 0xE6FFFFFF.toInt())
            paint.setShadowLayer(0.8f * unit, 0f, 0.25f * unit, 0xB0000000.toInt())
            textLines.add(Triple(text, paint, size * 1.28f))
        }
        if (details.isNotEmpty()) {
            val size = 1.95f * unit
            textLines.add(Triple(details, plainPaint(size, 0xD9FFFFFF.toInt()).apply { setShadowLayer(0.8f * unit, 0f, 0.25f * unit, 0xB0000000.toInt()) }, size * 1.35f))
        }
        val textWidth = textLines.maxOfOrNull { it.second.measureText(it.first) } ?: 0f
        val textHeight = textLines.sumOf { it.third.toDouble() }.toFloat()
        val totalWidth = logoSize + gap + textWidth
        val totalHeight = maxOf(logoSize, textHeight)
        val x0 = when (s.position) {
            WatermarkPosition.LEFT -> margin
            WatermarkPosition.CENTER -> (w - totalWidth) / 2f
            WatermarkPosition.RIGHT -> w - margin - totalWidth
        }.coerceAtLeast(unit)
        val y0 = h - margin - totalHeight

        // On a bright or busy part of the picture white text washes out: add a soft dark pill behind it.
        val pad = 1.7f * unit
        val box = RectF(
            (x0 - pad).coerceAtLeast(0f), (y0 - pad).coerceAtLeast(0f),
            (x0 + totalWidth + pad).coerceAtMost(w.toFloat()), (y0 + totalHeight + pad).coerceAtMost(h.toFloat()),
        )
        val stats = regionStats(pixels, w, h, box)
        if (needsBacking(stats.first, stats.second)) {
            canvas.drawRoundRect(box, 2.2f * unit, 2.2f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99101010.toInt() })
        }

        if (logoSize > 0f && logo != null) {
            val top = y0 + (totalHeight - logoSize) / 2f
            canvas.drawBitmap(logo, null, RectF(x0, top, x0 + logoSize, top + logoSize), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        var baseline = y0 + (totalHeight - textHeight) / 2f
        val textX = x0 + logoSize + gap
        for ((text, paint, lineHeight) in textLines) {
            baseline += lineHeight
            canvas.drawText(text, textX, baseline - lineHeight * 0.22f, paint)
        }
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return Result(pixels, w, h)
    }

    /** The average brightness (0..255) and how much it varies (standard deviation) in [box] of the picture. */
    internal fun regionStats(pixels: IntArray, w: Int, h: Int, box: RectF): Pair<Float, Float> {
        val x0 = box.left.toInt().coerceIn(0, w - 1)
        val x1 = box.right.toInt().coerceIn(x0 + 1, w)
        val y0 = box.top.toInt().coerceIn(0, h - 1)
        val y1 = box.bottom.toInt().coerceIn(y0 + 1, h)
        var n = 0
        var sum = 0.0
        var sum2 = 0.0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = pixels[y * w + x]
                val l = 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)
                sum += l
                sum2 += l * l
                n++
                x += 2
            }
            y += 2
        }
        if (n == 0) return Pair(0f, 0f)
        val mean = sum / n
        return Pair(mean.toFloat(), kotlin.math.sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0)).toFloat())
    }

    /** True where white text would be hard to read: a bright area, or a busy one. */
    internal fun needsBacking(mean: Float, deviation: Float): Boolean = mean > 135f || deviation > 38f

    /** A frame bar added under the photo, in the style of the bars on some phone makers' photos. */
    private fun bar(
        logo: Bitmap?, pixels: IntArray, w: Int, h: Int, s: WatermarkSettings,
        lines: List<Pair<String, Float>>, details: String, unit: Float,
    ): Result {
        val barHeight = (11.5f * unit).roundToInt()
        val totalHeight = h + barHeight
        val bitmap = Bitmap.createBitmap(w, totalHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val canvas = Canvas(bitmap)
        val background = if (s.dark) 0xFF0B0B0B.toInt() else Color.WHITE
        val primary = if (s.dark) Color.WHITE else 0xFF111111.toInt()
        val secondary = if (s.dark) 0xFFB5B5B5.toInt() else 0xFF6A6A6A.toInt()
        canvas.drawRect(0f, h.toFloat(), w.toFloat(), totalHeight.toFloat(), Paint().apply { color = background })

        val margin = 3.4f * unit
        val logoSize = if (s.logo && logo != null) 7.2f * unit else 0f
        val gap = if (logoSize > 0f) 2.2f * unit else 0f
        val textLines = ArrayList<Triple<String, Paint, Float>>()
        lines.forEachIndexed { index, (text, relative) ->
            val size = 3.3f * unit * relative
            textLines.add(Triple(text, if (index == 0) titlePaint(size, primary) else plainPaint(size, secondary), size * 1.3f))
        }
        val textWidth = textLines.maxOfOrNull { it.second.measureText(it.first) } ?: 0f
        val textHeight = textLines.sumOf { it.third.toDouble() }.toFloat()
        val blockWidth = logoSize + gap + textWidth
        val blockHeight = maxOf(logoSize, textHeight)
        val centreY = h + barHeight / 2f

        // The details go on the opposite side from the text (under it, when the text is centred).
        val detailPaint = plainPaint(2.4f * unit, secondary)
        val detailWidth = if (details.isNotEmpty()) detailPaint.measureText(details) else 0f
        val blockX = when (s.position) {
            WatermarkPosition.LEFT -> margin
            WatermarkPosition.CENTER -> (w - blockWidth) / 2f
            WatermarkPosition.RIGHT -> w - margin - blockWidth
        }
        val blockTop = centreY - blockHeight / 2f - if (s.position == WatermarkPosition.CENTER && details.isNotEmpty()) 1.2f * unit else 0f

        if (logoSize > 0f && logo != null) {
            val top = blockTop + (blockHeight - logoSize) / 2f
            canvas.drawBitmap(logo, null, RectF(blockX, top, blockX + logoSize, top + logoSize), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        var baseline = blockTop + (blockHeight - textHeight) / 2f
        val textX = blockX + logoSize + gap
        for ((text, paint, lineHeight) in textLines) {
            baseline += lineHeight
            canvas.drawText(text, textX, baseline - lineHeight * 0.22f, paint)
        }
        if (details.isNotEmpty()) {
            when (s.position) {
                WatermarkPosition.LEFT -> canvas.drawText(details, w - margin - detailWidth, centreY + 0.8f * unit, detailPaint)
                WatermarkPosition.RIGHT -> canvas.drawText(details, margin, centreY + 0.8f * unit, detailPaint)
                WatermarkPosition.CENTER -> canvas.drawText(details, (w - detailWidth) / 2f, blockTop + blockHeight + 3.4f * unit, detailPaint)
            }
        }
        val out = IntArray(w * totalHeight)
        bitmap.getPixels(out, 0, w, 0, 0, w, totalHeight)
        bitmap.recycle()
        return Result(out, w, totalHeight)
    }

    /** A sample photo with the watermark on it, for the settings screen's preview. */
    fun preview(logo: Bitmap?, s: WatermarkSettings, detailLine: String, width: Int = 720): Bitmap {
        val height = width * 4 / 3
        val pixels = IntArray(width * height) { i ->
            val x = (i % width).toFloat() / width
            val y = (i / width).toFloat() / height
            // A soft sky-to-ground gradient, so white and dark text are both tested.
            val r = (70 + 120 * (1f - y) + 30 * x).toInt().coerceIn(0, 255)
            val g = (110 + 90 * (1f - y)).toInt().coerceIn(0, 255)
            val b = (150 + 80 * (1f - y) - 40 * x).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val result = apply(logo, pixels, width, height, s, detailLine)
        return Bitmap.createBitmap(result.pixels, result.width, result.height, Bitmap.Config.ARGB_8888)
    }
}
