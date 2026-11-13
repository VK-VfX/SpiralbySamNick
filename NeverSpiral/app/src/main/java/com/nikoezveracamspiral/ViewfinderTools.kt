package com.nikoezveracamspiral

import android.content.Context
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val BINS = 32

/** The filter paint currently on each preview layer, so [snapshot] can set it aside. */
internal object PreviewPaint {
    val paints = java.util.WeakHashMap<TextureView, android.graphics.Paint?>()
}

/**
 * A small copy of the viewfinder frame. With a colour-filter paint on the layer, reading the
 * layer back fails on current Android ("Unable to draw content from GPU into the provided
 * bitmap"), so the paint is lifted for the moment of the read; the frame is the camera's own,
 * unfiltered, which is also what the filter thumbnails and histogram want.
 */
internal fun snapshot(view: TextureView, width: Int, height: Int): android.graphics.Bitmap? {
    if (!view.isAvailable) return null
    val paint = PreviewPaint.paints[view]
    if (paint != null) view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    val bitmap = try {
        view.getBitmap(width, height)
    } catch (e: Exception) {
        null
    } finally {
        if (paint != null) view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
    }
    if (bitmap == null) return null
    // A failed read can still hand back a fully transparent bitmap.
    var opaque = false
    for (i in 0 until 16) {
        val x = (bitmap.width - 1) * (i % 4) / 3
        val y = (bitmap.height - 1) * (i / 4) / 3
        if ((bitmap.getPixel(x, y) ushr 24) != 0) {
            opaque = true
            break
        }
    }
    if (!opaque) {
        bitmap.recycle()
        return null
    }
    return bitmap
}

/** A small live luminance histogram of the viewfinder, refreshed a couple of times a second. */
@Composable
internal fun HistogramOverlay(view: TextureView?, modifier: Modifier = Modifier) {
    var bins by remember { mutableStateOf(IntArray(BINS)) }
    LaunchedEffect(view) {
        while (view != null) {
            val bitmap = try {
                snapshot(view, 96, 128)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                bins = withContext(Dispatchers.Default) {
                    val out = IntArray(BINS)
                    for (p in pixels) {
                        val luma = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                        out[(luma * BINS / 256).coerceIn(0, BINS - 1)]++
                    }
                    out
                }
            }
            delay(500)
        }
    }
    val peak = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(
        modifier = modifier
            .size(width = 110.dp, height = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
            .padding(4.dp),
    ) {
        val barWidth = size.width / BINS
        for (i in 0 until BINS) {
            val h = size.height * bins[i] / peak
            drawRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(i * barWidth, size.height - h),
                size = androidx.compose.ui.geometry.Size((barWidth - 1f).coerceAtLeast(1f), h),
            )
        }
    }
}
