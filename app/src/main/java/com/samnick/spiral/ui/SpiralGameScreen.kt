package com.samnick.spiral.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.samnick.spiral.game.GameEngine
import com.samnick.spiral.game.GameSnapshot
import com.samnick.spiral.game.Phase
import com.samnick.spiral.ui.theme.PlayerAmber
import com.samnick.spiral.ui.theme.PlayerReversalWhiteGold
import com.samnick.spiral.ui.theme.VoidBlack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SpiralGameScreen(modifier: Modifier = Modifier) {
    val engine = remember { GameEngine() }
    var snapshot by remember { mutableStateOf(engine.update(0f)) }
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 32.dp.toPx() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        var lastFrameNanos = -1L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos < 0) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                snapshot = engine.update(dt)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VoidBlack)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.A -> { engine.requestLaneLeft(); true }
                    Key.DirectionRight, Key.D -> { engine.requestLaneRight(); true }
                    Key.DirectionUp, Key.W, Key.Spacebar -> {
                        if (snapshot.isGameOver) engine.reset() else engine.requestJump()
                        true
                    }
                    Key.DirectionDown, Key.S -> { engine.requestSlide(); true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                coroutineScope {
                    launch {
                        detectTapGestures(onTap = {
                            if (snapshot.isGameOver) engine.reset() else engine.requestJump()
                        })
                    }
                    launch {
                        var totalDrag = Offset.Zero
                        detectDragGestures(
                            onDragStart = { totalDrag = Offset.Zero },
                            onDrag = { change, amount ->
                                change.consume()
                                totalDrag += amount
                            },
                            onDragEnd = {
                                val dx = totalDrag.x
                                val dy = totalDrag.y
                                if (kotlin.math.abs(dx) < swipeThresholdPx && kotlin.math.abs(dy) < swipeThresholdPx) {
                                    return@detectDragGestures
                                }
                                if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                    if (dx < 0) engine.requestLaneLeft() else engine.requestLaneRight()
                                } else {
                                    if (dy < 0) engine.requestJump() else engine.requestSlide()
                                }
                            },
                        )
                    }
                }
            },
    ) {
        GameCanvas(snapshot = snapshot)
        GameHud(snapshot = snapshot, modifier = Modifier.align(Alignment.TopCenter))
        if (snapshot.isGameOver) {
            GameOverOverlay(score = snapshot.score, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GameCanvas(snapshot: GameSnapshot) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasSize = Size(size.width, size.height)
        val edgeColor = if (snapshot.phase == Phase.REVERSAL) PlayerReversalWhiteGold else PlayerAmber

        drawSpiralVignette(snapshot.spiralRotation, snapshot.phase, canvasSize)
        drawCorridor(canvasSize, edgeColor)

        for (obstacle in snapshot.obstacles) {
            drawObstacle(obstacle, canvasSize, edgeColor)
        }

        val entityProgress = if (snapshot.phase == Phase.REVERSAL) snapshot.closeness else (1f - snapshot.gap)
        val entityZ = Corridor.PLAYER_Z * (1f - entityProgress)
        val entityLane = 1 // the entity looms in the corridor's center regardless of the player's lane
        drawEntity(entityZ, entityLane, canvasSize, snapshot.runCyclePhase, snapshot.phase)

        val playerGround = Offset(
            Corridor.laneXInterpolated(
                snapshot.playerLaneAnimFrom,
                snapshot.playerLane,
                snapshot.playerLaneAnimT,
                Corridor.PLAYER_Z,
                canvasSize,
            ),
            Corridor.screenY(Corridor.PLAYER_Z, canvasSize),
        )
        val laneDelta = snapshot.playerLane - snapshot.playerLaneAnimFrom
        val lean = laneDelta * 0.18f * (1f - snapshot.playerLaneAnimT)
        val figureHeight = canvasSize.height * 0.24f
        val playerColor = if (snapshot.phase == Phase.REVERSAL) PlayerReversalWhiteGold else PlayerAmber

        drawRunnerFigure(
            groundPoint = playerGround,
            height = figureHeight,
            runCyclePhase = snapshot.runCyclePhase,
            vertical = snapshot.playerVertical,
            verticalT = snapshot.playerVerticalT,
            lean = lean,
            color = playerColor,
        )

        if (snapshot.hitFlash > 0f) {
            drawRect(edgeColor.copy(alpha = snapshot.hitFlash * 0.18f), size = canvasSize)
        }
        if (snapshot.catchFlash > 0f) {
            drawRect(PlayerReversalWhiteGold.copy(alpha = snapshot.catchFlash * 0.35f), size = canvasSize)
        }
    }
}
