package com.samnick.neverspiral

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val context = LocalContext.current
    val haptics = remember(context) { Haptics(context) }
    val pointerCount = remember { mutableIntStateOf(1) }
    var visualizerOn by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioCaptureService.start(context, result.resultCode, data)
            visualizerOn = true
        } else {
            visualizerOn = false
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    LaunchedEffect(Unit) {
        AudioAnalyzer.snapshots.collect { snapshot ->
            if (visualizerOn) {
                engine.applyAudio(snapshot.loudness, snapshot.beatId, snapshot.brightness)
            }
        }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    onTap = { offset ->
                        engine.onTap(pointerCount.intValue, offset.x, offset.y)
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

        for (ripple in engine.ripples) {
            drawRipple(ripple, engine.elapsed)
        }
    }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Button(
                onClick = {
                    if (visualizerOn) {
                        AudioCaptureService.stop(context)
                        engine.stopAudio()
                        visualizerOn = false
                    } else {
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            ) {
                Text(if (visualizerOn) "Stop visualizer" else "Visualize music")
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRipple(ripple: TapRipple, elapsed: Float) {
    val t = ((elapsed - ripple.birth) / 0.5f).coerceIn(0f, 1f)
    if (t >= 1f) return
    val eased = 1f - (1f - t) * (1f - t)
    val radius = 8f + eased * 130f
    val alpha = 1f - t
    drawCircle(
        color = Color.hsv(ripple.hue, 0.75f, 1f, alpha * 0.9f),
        radius = radius,
        center = Offset(ripple.x, ripple.y),
        style = Stroke(width = (6f * (1f - t)).coerceAtLeast(1f)),
    )
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
    val strokeWidth = (2.5f + scale * 1.1f).coerceAtMost(14f)
    val saturation = ((0.65f + engine.energy * 0.35f) * (1f - engine.restfulness * 0.45f)).coerceIn(0f, 1f)
    val armSpacing = 2f * PI.toFloat() / generation.armCount

    for (arm in 0 until generation.armCount) {
        val armOffset = arm * armSpacing
        val paths = Array(HUE_BUCKETS) { Path() }
        val started = BooleanArray(HUE_BUCKETS)

        for (i in 0..n) {
            val p = i / n.toFloat()
            val theta = armOffset + p * TURNS * 2f * PI.toFloat()
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

        for (b in 0 until HUE_BUCKETS) {
            val p = b / (HUE_BUCKETS - 1).toFloat()
            val hue = (generation.hueOffset + arm * 40f + p * 260f).mod(360f)
            val color = Color.hsv(hue, saturation, 1f, opacity)
            drawPath(paths[b], color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        }
    }
}
