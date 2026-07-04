package com.samnick.neverspiral

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.isActive

private const val TURNS = 3.4f
private const val POINTS_PER_TURN = 46
private const val HUE_BUCKETS = 20

@Composable
fun SpiralScreen() {
    val engine = remember { SpiralEngine() }
    val haptics = remember { Haptics(LocalContext.current) }
    val pointerCount = remember { mutableIntStateOf(1) }

    LaunchedEffect(engine) {
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                engine.step(dt)
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Tracks how many fingers are on screen right now; a bigger, non-interfering
                // signal source for "multi-finger tap" excitement.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var event = awaitPointerEvent()
                    while (event.changes.any { it.pressed }) {
                        pointerCount.intValue = event.changes.count { it.pressed }.coerceAtLeast(1)
                        event = awaitPointerEvent()
                    }
                    pointerCount.intValue = 1
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        engine.onTap(pointerCount.intValue)
                        haptics.tapTick(engine.energy)
                    },
                    onLongPress = {
                        engine.onLongPress()
                        haptics.pulseThud()
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        engine.onDrag(dragAmount.x)
                    },
                )
            },
    ) {
        drawRect(color = Color(0xFF05000A))

        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = min(size.width, size.height) / 2f * 0.92f
        val rotationDegrees = engine.phase * (180f / PI.toFloat())
        val breathing = engine.breathe(engine.elapsed)

        rotate(degrees = rotationDegrees, pivot = center) {
            for (generation in engine.generations) {
                drawGeneration(generation, engine, baseRadius, center, breathing)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGeneration(
    generation: Generation,
    engine: SpiralEngine,
    baseRadius: Float,
    center: Offset,
    breathing: Float,
) {
    val age = engine.elapsed - generation.birth
    val t = (age / generation.lifetime).coerceIn(0f, 1.3f)

    val opacity = when {
        t < 0.08f -> t / 0.08f
        t > 0.78f -> ((1f - t) / (1f - 0.78f)).coerceIn(0f, 1f)
        else -> 1f
    }
    if (opacity <= 0.01f) return

    val growth = t.coerceIn(0f, 1.3f).pow(0.6f)
    val scale = 0.04f + growth * 6.5f
    val radius = baseRadius * scale * breathing

    val n = (TURNS * POINTS_PER_TURN).toInt()
    val paths = Array(HUE_BUCKETS) { Path() }
    val started = BooleanArray(HUE_BUCKETS)

    for (i in 0..n) {
        val p = i / n.toFloat()
        val theta = p * TURNS * 2f * PI.toFloat()
        val r = p * radius
        val x = center.x + r * cos(theta)
        val y = center.y + r * sin(theta)
        val bucket = (p * (HUE_BUCKETS - 1)).toInt().coerceIn(0, HUE_BUCKETS - 1)
        if (!started[bucket]) {
            paths[bucket].moveTo(x, y)
            started[bucket] = true
        } else {
            paths[bucket].lineTo(x, y)
        }
    }

    val strokeWidth = (2.5f + scale * 1.1f).coerceAtMost(14f)
    val saturation = (0.65f + engine.energy * 0.35f).coerceIn(0f, 1f)
    for (b in 0 until HUE_BUCKETS) {
        val p = b / (HUE_BUCKETS - 1).toFloat()
        val hue = (generation.hueOffset + p * 260f).mod(360f)
        val color = Color.hsv(hue, saturation, 1f, opacity)
        drawPath(paths[b], color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
    }
}
