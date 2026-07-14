package com.samnick.neverspiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val LAYOUT_RADIUS_FRACTION = 0.36f
private const val NODE_BASE_RADIUS_FRACTION = 0.010f
private const val NODE_MAX_GROWTH_FRACTION = 0.026f
private const val AMPLITUDE_SENSITIVITY_GAMMA = 0.8f

private const val CONNECTION_BASE_WIDTH_FRACTION = 0.0015f
private const val CONNECTION_MAX_WIDTH_FRACTION = 0.007f

/** Connections never fully vanish at silence -- a faint, always-visible network that brightens
 * with the music, rather than snapping in and out of existence. */
private const val CONNECTION_MIN_ALPHA = 40
private const val CONNECTION_MAX_ALPHA = 220

/** Center spokes read as background structure, not the main event -- dimmer than the ring
 * connections between neighboring nodes. */
private const val SPOKE_ALPHA_SCALE = 0.5f

private const val GLOW_RADIUS_FRACTION = 0.018f
private const val GLOW_ALPHA = 160

/**
 * A fixed star-chart layout -- deliberately the opposite of Audio Fireflies' spawn-and-die
 * particles: one node per [SpectrumEngine] band, laid out once at a permanent position around a
 * circle (band 0 at 12 o'clock, sweeping clockwise, the same angular convention every radial mode
 * in this app uses) and never moved, spawned, or killed. Only each node's own size/brightness and
 * the connections between them react to the music -- the layout itself is static structure, like a
 * constellation's stars don't move, only how brightly they seem to shine.
 *
 * Each node reads its own band level directly from [SpectrumEngine] (already fast-rise/slow-fall
 * smoothed, so no extra smoothing pass is needed here, the same reasoning Rainbow Spectrum and
 * every other pure-rendering mode relies on) and grows/brightens with it. Thin lines connect each
 * node to its immediate neighbors around the ring -- not every node to every other node, which
 * would turn into visual noise well before 28 nodes -- with width and alpha driven by the average
 * of the two connected nodes' levels, plus a fainter spoke from each node back to center. Color is
 * [frequencyZoneColor] keyed to each node's position in the band sequence, the same bass-to-treble
 * mapping Neon Cyan Pulse uses; a connection's color is the midpoint between its two endpoints'
 * colors. Glow is the same draw-solid-then-blur-once technique used everywhere else.
 */
@Composable
fun EqualizerConstellationScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val nodesHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // unlike most modes, nothing here is delta-time-integrated (no physics, no smoothing pass
        // of its own), every value is a direct function of the current band levels, but the redraw
        // still needs to be triggered somehow since the band array itself isn't Compose-observable.
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var nodesBitmap = nodesHolder[0]
        if (nodesBitmap == null || nodesBitmap.width != widthPx || nodesBitmap.height != heightPx) {
            nodesBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            nodesHolder[0] = nodesBitmap
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        val minDim = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val layoutRadius = minDim * LAYOUT_RADIUS_FRACTION
        val heightScale = settings.height / BarSpectrumSettings.HEIGHT_MAX
        val nodeCount = engine.bands.size

        val positions = Array(nodeCount) { i ->
            val angle = Math.toRadians((i.toFloat() / nodeCount) * 360.0 - 90.0)
            Offset(center.x + layoutRadius * cos(angle).toFloat(), center.y + layoutRadius * sin(angle).toFloat())
        }
        val levels = FloatArray(nodeCount) { i ->
            (engine.bands[i].coerceIn(0f, 1f).pow(AMPLITUDE_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
        }
        val colors = Array(nodeCount) { i ->
            frequencyZoneColor(i.toFloat() / (nodeCount - 1).coerceAtLeast(1))
        }

        val nodesCanvas = AndroidCanvas(nodesBitmap)
        nodesCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val spokePaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
        }
        for (i in 0 until nodeCount) {
            val alpha = (CONNECTION_MIN_ALPHA + (CONNECTION_MAX_ALPHA - CONNECTION_MIN_ALPHA) * levels[i]) * SPOKE_ALPHA_SCALE
            spokePaint.color = colors[i].toArgb()
            spokePaint.alpha = alpha.toInt().coerceIn(0, 255)
            spokePaint.strokeWidth = minDim * CONNECTION_BASE_WIDTH_FRACTION * settings.strokeWeight
            nodesCanvas.drawLine(center.x, center.y, positions[i].x, positions[i].y, spokePaint)
        }

        val connectionPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
        }
        for (i in 0 until nodeCount) {
            val next = (i + 1) % nodeCount
            val avgLevel = (levels[i] + levels[next]) / 2f
            connectionPaint.color = lerpGradientColor(colors[i], colors[next], 0.5f).toArgb()
            connectionPaint.alpha = (CONNECTION_MIN_ALPHA + (CONNECTION_MAX_ALPHA - CONNECTION_MIN_ALPHA) * avgLevel)
                .toInt().coerceIn(0, 255)
            connectionPaint.strokeWidth = minDim * (CONNECTION_BASE_WIDTH_FRACTION +
                CONNECTION_MAX_WIDTH_FRACTION * avgLevel) * settings.strokeWeight
            nodesCanvas.drawLine(positions[i].x, positions[i].y, positions[next].x, positions[next].y, connectionPaint)
        }

        val nodePaint = AndroidPaint().apply { isAntiAlias = true }
        for (i in 0 until nodeCount) {
            val nodeRadius = minDim * (NODE_BASE_RADIUS_FRACTION + NODE_MAX_GROWTH_FRACTION * levels[i] * heightScale)
            nodePaint.color = colors[i].toArgb()
            nodesCanvas.drawCircle(positions[i].x, positions[i].y, nodeRadius, nodePaint)
        }

        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(nodesBitmap, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(nodesBitmap.asImageBitmap())
    }
}
