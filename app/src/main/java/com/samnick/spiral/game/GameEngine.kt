package com.samnick.spiral.game

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure Kotlin game simulation, deliberately free of any Android/Compose types so it can be
 * unit-tested and reasoned about in isolation from rendering. [update] advances the whole
 * simulation by [dt] seconds and returns an immutable [GameSnapshot] for the renderer.
 */
class GameEngine(private val random: Random = Random.Default) {

    companion object {
        const val LANE_COUNT = 3
        private const val CENTER_LANE = 1

        private const val LANE_SWITCH_DURATION = 0.16f
        private const val JUMP_DURATION = 0.50f
        private const val SLIDE_DURATION = 0.46f

        /** World units/second an obstacle travels from spawn (z=0) to the player (z=PLAYER_Z). */
        private const val BASE_SPEED = 0.62f
        private const val MAX_SPEED = 1.55f
        private const val SPEED_PER_TIER = 0.11f

        private const val PLAYER_Z = 0.92f
        private const val REMOVE_Z = 1.08f
        private const val HIT_WINDOW = 0.05f

        private const val BASE_SPAWN_INTERVAL = 1.35f
        private const val MIN_SPAWN_INTERVAL = 0.62f
        private const val SPAWN_INTERVAL_PER_TIER = 0.06f

        private const val ENERGY_PER_CLEAR = 0.12f
        private const val GAP_PER_CLEAR = 0.05f
        private const val GAP_HIT_PENALTY = 0.24f

        private const val REVERSAL_DURATION = 6.5f
        private const val CLOSENESS_PER_CLEAR = 0.16f
        private const val CLOSENESS_HIT_PENALTY = 0.09f

        private const val CATCH_SCORE_BASE = 500L
        private const val CATCH_SCORE_PER_TIER = 100L
        private const val CLEAR_SCORE = 25L
        private const val DISTANCE_SCORE_RATE = 12f

        /** Run-cycle radians/second at BASE_SPEED; scales up with current game speed. */
        private const val BASE_CYCLE_RATE = 9.5f

        private const val BASE_SPIRAL_RATE = 0.35f
        private const val MAX_SPIRAL_RATE = 1.3f

        private const val HIT_FLASH_DECAY = 2.6f
        private const val CATCH_FLASH_DECAY = 1.4f
    }

    private var phase = Phase.CHASE
    private var score = 0L
    private var energy = 0f
    private var gap = 1f
    private var closeness = 0f
    private var reversalTimeLeft = 0f
    private var difficultyTier = 0
    private var isGameOver = false

    private var playerLane = CENTER_LANE
    private var laneAnimFrom = CENTER_LANE
    private var laneAnimT = 1f
    private var vertical = VerticalAction.NONE
    private var verticalT = 1f

    /** Accumulated run-cycle phase in radians; advanced every frame regardless of phase/game-over. */
    private var runCyclePhase = 0f
    private var spiralRotation = 0f

    private var hitFlash = 0f
    private var catchFlash = 0f

    private val obstacles = mutableListOf<Obstacle>()
    private var nextObstacleId = 0L
    private var timeToNextSpawn = BASE_SPAWN_INTERVAL

    private fun currentSpeed(): Float =
        min(MAX_SPEED, BASE_SPEED + difficultyTier * SPEED_PER_TIER)

    private fun currentSpawnInterval(): Float =
        max(MIN_SPAWN_INTERVAL, BASE_SPAWN_INTERVAL - difficultyTier * SPAWN_INTERVAL_PER_TIER)

    fun requestLaneLeft() = requestLane(playerLane - 1)
    fun requestLaneRight() = requestLane(playerLane + 1)

    private fun requestLane(target: Int) {
        if (isGameOver) return
        val clamped = target.coerceIn(0, LANE_COUNT - 1)
        if (clamped == playerLane) return
        laneAnimFrom = playerLane
        playerLane = clamped
        laneAnimT = 0f
    }

    fun requestJump() {
        if (isGameOver) return
        if (vertical == VerticalAction.NONE) {
            vertical = VerticalAction.JUMP
            verticalT = 0f
        }
    }

    fun requestSlide() {
        if (isGameOver) return
        if (vertical == VerticalAction.NONE) {
            vertical = VerticalAction.SLIDE
            verticalT = 0f
        }
    }

    fun reset() {
        phase = Phase.CHASE
        score = 0
        energy = 0f
        gap = 1f
        closeness = 0f
        reversalTimeLeft = 0f
        difficultyTier = 0
        isGameOver = false
        playerLane = CENTER_LANE
        laneAnimFrom = CENTER_LANE
        laneAnimT = 1f
        vertical = VerticalAction.NONE
        verticalT = 1f
        runCyclePhase = 0f
        spiralRotation = 0f
        hitFlash = 0f
        catchFlash = 0f
        obstacles.clear()
        timeToNextSpawn = BASE_SPAWN_INTERVAL
    }

    fun update(dt: Float): GameSnapshot {
        val clampedDt = dt.coerceIn(0f, 1f / 15f) // guard against huge jumps after a dropped frame

        advanceCosmetics(clampedDt)

        if (!isGameOver) {
            advanceLaneAnim(clampedDt)
            advanceVertical(clampedDt)
            advanceObstacles(clampedDt)

            when (phase) {
                Phase.CHASE -> {
                    score += (currentSpeed() * DISTANCE_SCORE_RATE * clampedDt).toLong()
                    if (gap <= 0f) {
                        isGameOver = true
                    } else if (energy >= 1f) {
                        enterReversal()
                    }
                }
                Phase.REVERSAL -> {
                    reversalTimeLeft -= clampedDt
                    if (closeness >= 1f) {
                        resolveCatch()
                    } else if (reversalTimeLeft <= 0f) {
                        exitReversalOnTimeout()
                    }
                }
            }
        }

        return snapshot()
    }

    private fun advanceCosmetics(dt: Float) {
        val speedMultiplier = currentSpeed() / BASE_SPEED
        runCyclePhase += BASE_CYCLE_RATE * speedMultiplier * dt
        if (runCyclePhase > 4000f) runCyclePhase %= (2f * PI.toFloat())

        val spiralRate = min(MAX_SPIRAL_RATE, BASE_SPIRAL_RATE * speedMultiplier)
        val direction = if (phase == Phase.REVERSAL) -1f else 1f
        spiralRotation += spiralRate * direction * dt

        hitFlash = max(0f, hitFlash - HIT_FLASH_DECAY * dt)
        catchFlash = max(0f, catchFlash - CATCH_FLASH_DECAY * dt)
    }

    private fun advanceLaneAnim(dt: Float) {
        if (laneAnimT < 1f) {
            laneAnimT = min(1f, laneAnimT + dt / LANE_SWITCH_DURATION)
        }
    }

    private fun advanceVertical(dt: Float) {
        if (vertical == VerticalAction.NONE) return
        val duration = if (vertical == VerticalAction.JUMP) JUMP_DURATION else SLIDE_DURATION
        verticalT += dt / duration
        if (verticalT >= 1f) {
            vertical = VerticalAction.NONE
            verticalT = 1f
        }
    }

    private fun advanceObstacles(dt: Float) {
        val speed = currentSpeed()
        val iter = obstacles.iterator()
        while (iter.hasNext()) {
            val obstacle = iter.next()
            val wasBeforePlayer = obstacle.z < PLAYER_Z
            obstacle.z += speed * dt
            if (!obstacle.resolved && wasBeforePlayer && obstacle.z >= PLAYER_Z - HIT_WINDOW) {
                resolveObstacle(obstacle)
            }
            if (obstacle.z >= REMOVE_Z) {
                iter.remove()
            }
        }

        timeToNextSpawn -= dt
        if (timeToNextSpawn <= 0f) {
            spawnObstacle()
            timeToNextSpawn = currentSpawnInterval()
        }
    }

    private fun spawnObstacle() {
        val type = ObstacleType.entries[random.nextInt(ObstacleType.entries.size)]
        val lane = random.nextInt(LANE_COUNT)
        obstacles += Obstacle(id = nextObstacleId++, lane = lane, z = 0f, type = type)
    }

    private fun resolveObstacle(obstacle: Obstacle) {
        obstacle.resolved = true
        val cleared = isCleared(obstacle)
        if (cleared) onCleared() else onHit()
    }

    private fun isCleared(obstacle: Obstacle): Boolean {
        if (obstacle.lane != playerLane) return true
        return when (obstacle.type) {
            ObstacleType.FULL_LANE -> false // only a lane switch avoids it; already false since lanes matched
            ObstacleType.LOW -> vertical == VerticalAction.JUMP
            ObstacleType.OVERHEAD -> vertical == VerticalAction.SLIDE
        }
    }

    private fun onCleared() {
        score += CLEAR_SCORE
        when (phase) {
            Phase.CHASE -> {
                energy = min(1f, energy + ENERGY_PER_CLEAR)
                gap = min(1f, gap + GAP_PER_CLEAR)
            }
            Phase.REVERSAL -> {
                closeness = min(1f, closeness + CLOSENESS_PER_CLEAR)
            }
        }
    }

    private fun onHit() {
        hitFlash = 1f
        when (phase) {
            Phase.CHASE -> gap = max(0f, gap - GAP_HIT_PENALTY)
            Phase.REVERSAL -> closeness = max(0f, closeness - CLOSENESS_HIT_PENALTY)
        }
    }

    private fun enterReversal() {
        phase = Phase.REVERSAL
        energy = 0f
        closeness = 0f
        reversalTimeLeft = REVERSAL_DURATION
        obstacles.clear()
        timeToNextSpawn = currentSpawnInterval()
    }

    private fun resolveCatch() {
        catchFlash = 1f
        score += CATCH_SCORE_BASE + CATCH_SCORE_PER_TIER * difficultyTier
        difficultyTier += 1
        gap = min(1f, gap + 0.2f)
        backToChase()
    }

    private fun exitReversalOnTimeout() {
        backToChase()
    }

    private fun backToChase() {
        phase = Phase.CHASE
        energy = 0f
        closeness = 0f
        reversalTimeLeft = 0f
        obstacles.clear()
        timeToNextSpawn = currentSpawnInterval()
    }

    private fun snapshot() = GameSnapshot(
        phase = phase,
        score = score,
        energy = energy,
        gap = gap,
        closeness = closeness,
        reversalTimeLeft = reversalTimeLeft,
        reversalDuration = REVERSAL_DURATION,
        difficultyTier = difficultyTier,
        speed = currentSpeed(),
        playerLane = playerLane,
        playerLaneAnimFrom = laneAnimFrom,
        playerLaneAnimT = laneAnimT,
        playerVertical = vertical,
        playerVerticalT = verticalT,
        runCyclePhase = runCyclePhase,
        obstacles = obstacles.map { it.copy() },
        spiralRotation = spiralRotation,
        hitFlash = hitFlash,
        catchFlash = catchFlash,
        isGameOver = isGameOver,
    )
}
