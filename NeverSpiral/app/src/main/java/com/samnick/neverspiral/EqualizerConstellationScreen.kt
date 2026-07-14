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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Fewer, bigger stars read as an actual constellation; the original one-node-per-FFT-band
 * layout (28 of them) packed a dense ring of dots that looked more like a wiring diagram. Each
 * star aggregates a contiguous range of [SpectrumEngine.bands] rather than reading one band
 * directly, so the full bass-to-treble sweep is still represented, just at a coarser resolution. */
private const val STAR_COUNT = 12

private const val LAYOUT_RADIUS_FRACTION = 0.36f
private const val NODE_BASE_RADIUS_FRACTION = 0.014f
private const val NODE_MAX_GROWTH_FRACTION = 0.034f
private const val AMPLITUDE_SENSITIVITY_GAMMA = 0.8f

private const val CONNECTION_BASE_WIDTH_FRACTION = 0.0018f
private const val CONNECTION_MAX_WIDTH_FRACTION = 0.008f

/** Connections never fully vanish at silence -- a faint, always-visible network that brightens
 * with the music, rather than snapping in and out of existence. */
private const val CONNECTION_MIN_ALPHA = 40
private const val CONNECTION_MAX_ALPHA = 220

private const val GLOW_RADIUS_FRACTION = 0.020f
private const val GLOW_ALPHA = 170

/** Max angular drift from a star's base position, in degrees -- small relative to the 30°
 * spacing between adjacent stars (at [STAR_COUNT] = 12), so stars visibly wander without ever
 * crossing past a neighbor or scrambling the ring's overall shape. */
private const val ORBIT_DRIFT_DEGREES = 6f

/** Baseline drift period; each star's actual period is offset slightly from this (see the
 * per-star phase/period calculation below) so they drift out of sync with each other instead of
 * breathing in unison, which would read as one pulsing shape rather than independent stars. */
private const val ORBIT_DRIFT_BASE_PERIOD_SECONDS = 9f

/** How far a star pushes outward from its own current level, as a fraction of the layout radius
 * -- on top of the angular drift above, this is what makes a star feel like it's physically
 * responding to a hit rather than just glowing brighter in place. */
private const val RADIAL_PULSE_FRACTION = 0.06f

/**
 * A small constellation of [STAR_COUNT] stars, each a coarse aggregate of a contiguous range of
 * [SpectrumEngine]'s bands (band 0's range at 12 o'clock, sweeping clockwise, the same angular
 * convention every radial mode in this app uses) rather than one dot per raw FFT band -- 28 dots
 * crammed around one ring read as a wiring diagram, not stars. Each star continuously drifts
 * around its own base angle (see [ORBIT_DRIFT_DEGREES]) at a slightly different period than its
 * neighbors, and pushes radially outward with its own current level (see
 * [RADIAL_PULSE_FRACTION]) -- real motion, not just size/brightness changing in a fixed spot.
 *
 * Thin lines connect each star to its immediate neighbors around the ring -- not every star to
 * every other star, which would turn into visual noise -- with width and alpha driven by the
 * average of the two connected stars' levels. Color is [frequencyZoneColor] keyed to each star's
 * position in the sequence, the same bass-to-treble mapping Neon Cyan Pulse uses; a connection's
 * color is the midpoint between its two endpoints' colors. Glow is the same
 * draw-solid-then-blur-once technique used everywhere else.
 */
@Composable
fun EqualizerConstellationScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val nodesHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Reading elapsed (Compose state) here is what makes this Canvas redraw every frame --
        // it also directly drives the orbital drift below, so unlike before, this mode now does
        // have its own per-frame animation rather than being a pure function of band levels alone.
        val elapsed = engine.elapsed

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
        val totalBands = engine.bands.size

        val levels = FloatArray(STAR_COUNT) { i ->
            val bandStart = i * totalBands / STAR_COUNT
            val bandEnd = ((i + 1) * totalBands / STAR_COUNT).coerceAtLeast(bandStart + 1).coerceAtMost(totalBands)
            var sum = 0f
            for (b in bandStart until bandEnd) sum += engine.bands[b].coerceIn(0f, 1f)
            val avg = sum / (bandEnd - bandStart)
            (avg.pow(AMPLITUDE_SENSITIVITY_GAMMA) * settings.scale).coerceIn(0f, 1f)
        }
        val positions = Array(STAR_COUNT) { i ->
            val baseAngleDeg = (i.toFloat() / STAR_COUNT) * 360f - 90f
            val driftPeriod = ORBIT_DRIFT_BASE_PERIOD_SECONDS + i * 0.37f
            val driftPhase = i * 0.9f
            val drift = ORBIT_DRIFT_DEGREES * sin(2f * PI.toFloat() * (elapsed / driftPeriod) + driftPhase)
            val angle = Math.toRadians((baseAngleDeg + drift).toDouble())
            val r = layoutRadius * (1f + RADIAL_PULSE_FRACTION * levels[i])
            Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
        }
        val colors = Array(STAR_COUNT) { i ->
            frequencyZoneColor(i.toFloat() / (STAR_COUNT - 1).coerceAtLeast(1))
        }

        val nodesCanvas = AndroidCanvas(nodesBitmap)
        nodesCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val connectionPaint = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
        }
        for (i in 0 until STAR_COUNT) {
            val next = (i + 1) % STAR_COUNT
            val avgLevel = (levels[i] + levels[next]) / 2f
            connectionPaint.color = lerpGradientColor(colors[i], colors[next], 0.5f).toArgb()
            connectionPaint.alpha = (CONNECTION_MIN_ALPHA + (CONNECTION_MAX_ALPHA - CONNECTION_MIN_ALPHA) * avgLevel)
                .toInt().coerceIn(0, 255)
            connectionPaint.strokeWidth = minDim * (CONNECTION_BASE_WIDTH_FRACTION +
                CONNECTION_MAX_WIDTH_FRACTION * avgLevel) * settings.strokeWeight
            nodesCanvas.drawLine(positions[i].x, positions[i].y, positions[next].x, positions[next].y, connectionPaint)
        }

        val nodePaint = AndroidPaint().apply { isAntiAlias = true }
        for (i in 0 until STAR_COUNT) {
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
