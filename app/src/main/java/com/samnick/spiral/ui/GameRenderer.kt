package com.samnick.spiral.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.samnick.spiral.game.Obstacle
import com.samnick.spiral.game.ObstacleType
import com.samnick.spiral.game.Phase
import com.samnick.spiral.ui.theme.EntityInner
import com.samnick.spiral.ui.theme.EntityOuter
import com.samnick.spiral.ui.theme.ReversalInner
import com.samnick.spiral.ui.theme.ReversalOuter
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Shared pseudo-3D corridor geometry: world depth z in [0,1] (0 = horizon, 1 = at the player). */
object Corridor {
    const val PLAYER_Z = 0.92f
    private const val HORIZON_FRACTION = 0.30f
    private const val BOTTOM_FRACTION = 0.90f
    private const val PERSPECTIVE_POW = 2.3f
    private const val MAX_HALF_WIDTH_FRACTION = 0.44f

    private fun depthT(z: Float): Float = z.coerceIn(0f, 1f).toDouble().pow(PERSPECTIVE_POW.toDouble()).toFloat()

    fun screenY(z: Float, canvasSize: Size): Float {
        val horizonY = canvasSize.height * HORIZON_FRACTION
        val bottomY = canvasSize.height * BOTTOM_FRACTION
        return horizonY + (bottomY - horizonY) * depthT(z)
    }

    fun roadHalfWidth(z: Float, canvasSize: Size): Float =
        canvasSize.width * MAX_HALF_WIDTH_FRACTION * depthT(z)

    /** lane: 0 = left, 1 = center, 2 = right. */
    fun laneX(lane: Int, z: Float, canvasSize: Size): Float {
        val half = roadHalfWidth(z, canvasSize)
        val laneWidth = (half * 2f) / 3f
        return canvasSize.width / 2f + (lane - 1) * laneWidth
    }

    /** Continuous lane position for a mid-switch player: interpolates between two lane indices. */
    fun laneXInterpolated(fromLane: Int, toLane: Int, t: Float, z: Float, canvasSize: Size): Float {
        val fromX = laneX(fromLane, z, canvasSize)
        val toX = laneX(toLane, z, canvasSize)
        return fromX + (toX - fromX) * t.coerceIn(0f, 1f)
    }
}

fun DrawScope.drawCorridor(canvasSize: Size, entityColorEdge: Color) {
    val horizonY = Corridor.screenY(0f, canvasSize)
    val bottomY = Corridor.screenY(1f, canvasSize)
    val centerX = canvasSize.width / 2f

    // Side walls converging to the vanishing point, faint against the void.
    val bottomHalf = Corridor.roadHalfWidth(1f, canvasSize)
    drawLine(
        entityColorEdge.copy(alpha = 0.25f),
        Offset(centerX, horizonY),
        Offset(centerX - bottomHalf, bottomY),
        strokeWidth = 2f,
    )
    drawLine(
        entityColorEdge.copy(alpha = 0.25f),
        Offset(centerX, horizonY),
        Offset(centerX + bottomHalf, bottomY),
        strokeWidth = 2f,
    )

    // Lane divider lines (between lane 0/1 and lane 1/2).
    for (divider in listOf(0.5f, 1.5f)) {
        val path = androidx.compose.ui.graphics.Path()
        val steps = 24
        for (i in 0..steps) {
            val z = i / steps.toFloat()
            val half = Corridor.roadHalfWidth(z, canvasSize)
            val laneWidth = (half * 2f) / 3f
            val x = centerX + (divider - 1f) * laneWidth
            val y = Corridor.screenY(z, canvasSize)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, entityColorEdge.copy(alpha = 0.16f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
    }
}

fun DrawScope.drawSpiralVignette(rotation: Float, phase: Phase, canvasSize: Size) {
    val (outer, inner) = if (phase == Phase.REVERSAL) ReversalOuter to ReversalInner else EntityOuter to EntityInner
    val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val maxR = maxOf(canvasSize.width, canvasSize.height) * 0.78f
    val armCount = 4
    val samples = 28
    val curl = 0.018f

    for (arm in 0 until armCount) {
        val baseAngle = rotation + arm * (2f * Math.PI.toFloat() / armCount)
        var prev: Offset? = null
        for (s in 0..samples) {
            val r = maxR * (0.18f + 0.82f * (s / samples.toFloat()))
            val theta = baseAngle + r * curl
            val point = Offset(center.x + cos(theta) * r, center.y + sin(theta) * r)
            val edgeT = (s / samples.toFloat())
            prev?.let { from ->
                val color = lerpColor(inner, outer, edgeT)
                drawLine(
                    color.copy(alpha = 0.05f + 0.22f * edgeT),
                    from,
                    point,
                    strokeWidth = 3f + 5f * edgeT,
                    cap = StrokeCap.Round,
                )
            }
            prev = point
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val ct = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * ct,
        green = a.green + (b.green - a.green) * ct,
        blue = a.blue + (b.blue - a.blue) * ct,
        alpha = a.alpha + (b.alpha - a.alpha) * ct,
    )
}

fun DrawScope.drawObstacle(obstacle: Obstacle, canvasSize: Size, color: Color) {
    val z = obstacle.z
    val y = Corridor.screenY(z, canvasSize)
    val half = Corridor.roadHalfWidth(z, canvasSize)
    val laneWidth = (half * 2f) / 3f
    val x = Corridor.laneX(obstacle.lane, z, canvasSize)
    val blockHeight = laneWidth * 0.9f

    when (obstacle.type) {
        ObstacleType.FULL_LANE -> {
            val left = canvasSize.width / 2f - half
            val right = canvasSize.width / 2f + half
            drawLine(color.copy(alpha = 0.85f), Offset(left, y), Offset(right, y), strokeWidth = blockHeight * 0.5f, cap = StrokeCap.Square)
            drawLine(color.copy(alpha = 0.25f), Offset(left, y), Offset(right, y), strokeWidth = blockHeight * 0.9f, cap = StrokeCap.Square)
        }
        ObstacleType.LOW -> {
            // Low block sitting on the ground: clear it by jumping.
            drawRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(x - laneWidth * 0.32f, y - blockHeight * 0.28f),
                size = Size(laneWidth * 0.64f, blockHeight * 0.28f),
            )
        }
        ObstacleType.OVERHEAD -> {
            // Overhead bar: clear it by sliding under.
            drawRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(x - laneWidth * 0.34f, y - blockHeight * 0.95f),
                size = Size(laneWidth * 0.68f, blockHeight * 0.32f),
            )
        }
    }
}

/** The formless, writhing shadow entity, "Undead Light". */
fun DrawScope.drawEntity(z: Float, lane: Int, canvasSize: Size, jitterPhase: Float, phase: Phase) {
    val (outer, inner) = if (phase == Phase.REVERSAL) ReversalOuter to ReversalInner else EntityOuter to EntityInner
    val y = Corridor.screenY(z, canvasSize)
    val x = Corridor.laneX(lane, z, canvasSize)
    val half = Corridor.roadHalfWidth(z, canvasSize)
    val baseR = half * 0.5f

    val blobCount = 5
    for (i in 0 until blobCount) {
        val angle = jitterPhase * 0.6f + i * 1.3f
        val jx = x + cos(angle) * baseR * 0.35f
        val jy = y - baseR * 0.4f + sin(angle * 1.4f) * baseR * 0.3f
        val r = baseR * (0.55f + 0.18f * sin(jitterPhase + i))
        drawCircle(outer.copy(alpha = 0.22f), r * 1.6f, Offset(jx, jy))
        drawCircle(inner.copy(alpha = 0.5f), r, Offset(jx, jy))
    }
}
