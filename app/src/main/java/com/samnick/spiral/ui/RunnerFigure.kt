package com.samnick.spiral.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.samnick.spiral.game.VerticalAction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Procedural forward-kinematics runner figure: every limb is a chain of two segments rotated
 * from a shared hip/shoulder anchor, so the skeleton always holds together as one body instead
 * of independently animated line segments. The run cycle itself is driven purely by
 * [runCyclePhase] (a continuous, ever-increasing radian value owned by the game engine — an
 * accumulated time-times-cycle-speed integral) fed into sine waves per limb; nothing here is
 * keyed off wall-clock time directly, so the animation always advances in lockstep with the
 * simulation and never stalls.
 */

private const val HIP_SWING_AMP = 0.62f
private const val KNEE_BASE = 0.16f
private const val KNEE_AMP = 0.95f

private const val ARM_BASE_ANGLE = 0.5f
private const val ARM_SWING_AMP = 0.5f
private const val ELBOW_BASE = 0.4f
private const val ELBOW_AMP = 0.5f

private const val TORSO_BASE_LEAN = 0.07f
private const val BOB_FRACTION = 0.035f

private const val JUMP_TUCK_KNEE_AMP = 1.35f
private const val JUMP_ARM_RAISE = 0.9f
private const val SLIDE_THIGH_ANGLE = 1.15f
private const val SLIDE_KNEE_BEND = 0.15f

/** One side of a paired limb (leg or arm): its phase offset in the gait cycle and left/right sign. */
private data class LimbSide(val phaseOffset: Float, val side: Float)

private val LEG_SIDES = listOf(LimbSide(0f, side = 1f), LimbSide(PI.toFloat(), side = -1f))

// Each arm swings opposite its same-side leg, so it reuses the *other* leg's phase offset.
private val ARM_SIDES = listOf(LimbSide(PI.toFloat(), side = 1f), LimbSide(0f, side = -1f))

/** Rotates a joint chain: angle is measured from vertical, `up` = true extends upward (-y). */
private fun jointEnd(start: Offset, angleFromVertical: Float, length: Float, up: Boolean): Offset {
    val dx = sin(angleFromVertical) * length
    val dy = cos(angleFromVertical) * length * if (up) -1f else 1f
    return Offset(start.x + dx, start.y + dy)
}

/** Shared limb math: returns (upperSegmentAngle, lowerSegmentAngle) from a phase + amplitudes. */
private fun limbAngles(phase: Float, swingAmp: Float, bendBase: Float, bendAmp: Float): Pair<Float, Float> {
    val forwardness = sin(phase)
    val upperAngle = swingAmp * forwardness
    val bend = bendBase + bendAmp * max(0f, forwardness)
    val lowerAngle = upperAngle - bend
    return upperAngle to lowerAngle
}

private fun lerpAngle(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun DrawScope.drawGlowSegment(from: Offset, to: Offset, color: Color, coreWidth: Float) {
    drawLine(color.copy(alpha = 0.10f), from, to, strokeWidth = coreWidth * 3.4f, cap = StrokeCap.Round)
    drawLine(color.copy(alpha = 0.22f), from, to, strokeWidth = coreWidth * 2.0f, cap = StrokeCap.Round)
    drawLine(color.copy(alpha = 0.95f), from, to, strokeWidth = coreWidth, cap = StrokeCap.Round)
}

private fun DrawScope.drawGlowCircle(center: Offset, radius: Float, color: Color) {
    drawCircle(color.copy(alpha = 0.12f), radius * 1.9f, center)
    drawCircle(color.copy(alpha = 0.28f), radius * 1.35f, center)
    drawCircle(color.copy(alpha = 0.95f), radius, center)
}

/**
 * Draws the runner at [groundPoint] (her feet-on-the-ground baseline), [height] pixels tall,
 * facing the camera. [runCyclePhase] drives the leg/arm swing; [lean] adds a small horizontal
 * tilt for lane-change momentum.
 */
fun DrawScope.drawRunnerFigure(
    groundPoint: Offset,
    height: Float,
    runCyclePhase: Float,
    vertical: VerticalAction,
    verticalT: Float,
    lean: Float,
    color: Color,
    strokeWidth: Float = height * 0.045f,
) {
    val thighLen = height * 0.24f
    val shinLen = height * 0.22f
    val torsoLen = height * 0.34f
    val upperArmLen = height * 0.18f
    val forearmLen = height * 0.16f
    val headRadius = height * 0.085f
    val hipHalfWidth = height * 0.07f
    val shoulderHalfWidth = height * 0.11f

    val standingLegLen = thighLen + shinLen

    // Jump/slide envelopes: 0 at the start/end of the action, 1 at its midpoint.
    val jumpArc = if (vertical == VerticalAction.JUMP) sin(PI.toFloat() * verticalT.coerceIn(0f, 1f)) else 0f
    val slideArc = if (vertical == VerticalAction.SLIDE) sin(PI.toFloat() * verticalT.coerceIn(0f, 1f)) else 0f

    val bob = BOB_FRACTION * height * abs(sin(runCyclePhase)) * (1f - jumpArc) * (1f - slideArc)
    val jumpLift = height * 0.55f * jumpArc
    val slideDrop = height * 0.28f * slideArc

    val hip = Offset(
        groundPoint.x,
        groundPoint.y - standingLegLen - bob - jumpLift + slideDrop,
    )

    val torsoLean = TORSO_BASE_LEAN + lean - 0.25f * slideArc + 0.1f * jumpArc
    val shoulder = jointEnd(hip, torsoLean, torsoLen, up = true)
    val head = jointEnd(shoulder, torsoLean, headRadius * 2.1f, up = true)

    // --- Legs: opposite phase from each other; the standard alternating gait. ---
    for (limb in LEG_SIDES) {
        val phase = runCyclePhase + limb.phaseOffset
        val (rawThighAngle, rawShinAngle) = limbAngles(phase, HIP_SWING_AMP, KNEE_BASE, KNEE_AMP)

        val thighAngle = lerpAngle(rawThighAngle, SLIDE_THIGH_ANGLE * limb.side, slideArc)
        val kneeTuck = JUMP_TUCK_KNEE_AMP * jumpArc
        val shinAngle = if (slideArc > 0f) {
            lerpAngle(rawShinAngle - kneeTuck, thighAngle - SLIDE_KNEE_BEND, slideArc)
        } else {
            rawShinAngle - kneeTuck
        }

        val hipJoint = Offset(hip.x + hipHalfWidth * limb.side, hip.y)
        val knee = jointEnd(hipJoint, thighAngle + lean, thighLen, up = false)
        val foot = jointEnd(knee, shinAngle + lean, shinLen, up = false)

        drawGlowSegment(hipJoint, knee, color, strokeWidth)
        drawGlowSegment(knee, foot, color, strokeWidth * 0.85f)
    }

    // --- Arms: swing opposite to the same-side leg for a natural counter-rotation. ---
    for (limb in ARM_SIDES) {
        val phase = runCyclePhase + limb.phaseOffset
        val forwardness = sin(phase)
        val shoulderAngle = ARM_BASE_ANGLE + ARM_SWING_AMP * forwardness + JUMP_ARM_RAISE * jumpArc
        val elbowBend = ELBOW_BASE + ELBOW_AMP * max(0f, -forwardness)

        val shoulderJoint = Offset(shoulder.x + shoulderHalfWidth * limb.side, shoulder.y)
        val elbow = jointEnd(shoulderJoint, shoulderAngle + lean, upperArmLen, up = false)
        val hand = jointEnd(elbow, shoulderAngle - elbowBend + lean, forearmLen, up = false)

        drawGlowSegment(shoulderJoint, elbow, color, strokeWidth * 0.8f)
        drawGlowSegment(elbow, hand, color, strokeWidth * 0.7f)
    }

    // --- Torso + head last, so limbs read as attached to a coherent body. ---
    drawGlowSegment(hip, shoulder, color, strokeWidth * 1.3f)
    drawGlowCircle(head, headRadius, color)
}
