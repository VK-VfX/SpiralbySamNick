package com.samnick.spiral.game

/** Which side of the reversal the entity is on: chasing the player, or fleeing from her. */
enum class Phase { CHASE, REVERSAL }

/** How an obstacle in a lane must be dealt with. */
enum class ObstacleType {
    /** Low obstacle in a single lane; cleared by jumping. */
    LOW,
    /** Overhead obstacle in a single lane; cleared by sliding. */
    OVERHEAD,
    /** Blocks an entire lane top-to-bottom; only avoidable by switching lanes. */
    FULL_LANE,
}

/** The player's current vertical pose, driven by a fixed-duration action rather than physics. */
enum class VerticalAction { NONE, JUMP, SLIDE }

data class Obstacle(
    val id: Long,
    val lane: Int,
    var z: Float,
    val type: ObstacleType,
    var resolved: Boolean = false,
)

/**
 * Immutable snapshot of engine state for one frame, consumed by the renderer.
 * Kept separate from the mutable engine internals so Compose recomposition
 * always sees a consistent, fully-computed frame.
 */
data class GameSnapshot(
    val phase: Phase,
    val score: Long,
    val energy: Float,
    val gap: Float,
    val closeness: Float,
    val reversalTimeLeft: Float,
    val reversalDuration: Float,
    val difficultyTier: Int,
    val speed: Float,
    val playerLane: Int,
    val playerLaneAnimFrom: Int,
    val playerLaneAnimT: Float,
    val playerVertical: VerticalAction,
    val playerVerticalT: Float,
    val runCyclePhase: Float,
    val obstacles: List<Obstacle>,
    val spiralRotation: Float,
    val hitFlash: Float,
    val catchFlash: Float,
    val isGameOver: Boolean,
)
