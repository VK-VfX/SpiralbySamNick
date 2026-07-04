package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** One "life" of the spiral: it is born tiny, grows past the edge of the screen, and fades. */
data class Generation(val birth: Float, val lifetime: Float, val hueOffset: Float, val armCount: Int)

/** A quick expanding ring dropped at a tap's screen position. */
data class TapRipple(val x: Float, val y: Float, val birth: Float, val hue: Float)

/**
 * Drives the "never spiral": overlapping [Generation]s are born, grow far larger than the
 * screen, and crossfade into the next one -- so growth never has to reset with a visible pop,
 * and the loop truly never ends.
 *
 * Touch input feeds a decaying [energy] value (0..1) that is the app's "personality knob":
 * it speeds up rotation, shortens each generation's growth cycle, sweeps color faster, splits
 * the spiral into more arms, and pushes color saturation -- fast tapping reads as excitement.
 * Go untouched for a while and [restfulness] climbs instead, easing rotation and color further
 * below their normal resting point so calm has its own floor, not just a flat idle speed.
 */
class SpiralEngine {
    var phase by mutableFloatStateOf(0f)
        private set
    var hueBase by mutableFloatStateOf(260f)
        private set
    var energy by mutableFloatStateOf(0f)
        private set
    var pulse by mutableFloatStateOf(0f)
        private set
    var elapsed by mutableFloatStateOf(0f)
        private set
    var restfulness by mutableFloatStateOf(0f)
        private set

    val generations = mutableStateListOf<Generation>()
    val ripples = mutableStateListOf<TapRipple>()

    private var energyTarget = 0f
    private var dragVelocity = 0f
    private var lastSpawn = -1000f
    private var idleTime = 0f
    private val tapTimestamps = ArrayDeque<Long>()

    private val baseAngularSpeed = 0.35f // radians/sec at rest
    private val baseHueSpeed = 12f // degrees/sec at rest
    private val tapWindowNanos = 1_500_000_000L
    private val rippleLifetime = 0.5f

    init {
        generations.add(Generation(birth = 0f, lifetime = lifetimeFor(0f), hueOffset = hueBase, armCount = armCountFor(0f)))
    }

    private fun lifetimeFor(energy: Float): Float = 9f - energy * 6f // calm: ~9s cycle, hyper: ~3s
    private fun armCountFor(energy: Float): Int = 1 + (energy * 2.5f).toInt().coerceIn(0, 2) // 1..3 arms

    /** Advance all animated state by [dtSeconds]. Call once per frame. */
    fun step(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.05f) // guard against huge jumps after a paused frame
        elapsed += dt

        energy += (energyTarget - energy) * min(1f, dt * 2.5f)
        pulse *= exp(-dt * 3f)

        idleTime = if (energyTarget < 0.05f) idleTime + dt else 0f
        val restfulnessTarget = min(1f, idleTime / 8f) // 8s untouched to fully settle
        restfulness += (restfulnessTarget - restfulness) * min(1f, dt * 0.5f)

        val angularSpeed = baseAngularSpeed * (1f + energy * 5f) * (1f - restfulness * 0.6f) + dragVelocity
        phase = (phase + angularSpeed * dt).mod(2f * PI.toFloat())

        // friction brings a manual flick to rest like a spun wheel
        dragVelocity *= exp(-dt * 2.2f)

        hueBase = (hueBase + baseHueSpeed * (1f + energy * 6f) * (1f - restfulness * 0.5f) * dt).mod(360f)

        val lifetime = lifetimeFor(energy)
        val spawnPeriod = lifetime * 0.5f
        if (elapsed - lastSpawn >= spawnPeriod) {
            lastSpawn = elapsed
            generations.add(Generation(birth = elapsed, lifetime = lifetime, hueOffset = hueBase, armCount = armCountFor(energy)))
        }
        generations.removeAll { gen -> (elapsed - gen.birth) > gen.lifetime * 1.15f }
        ripples.removeAll { r -> (elapsed - r.birth) > rippleLifetime }

        pruneOldTaps(nowNanos = System.nanoTime())
        energyTarget = min(1f, tapTimestamps.size / 6f)
    }

    /** A finger just tapped down at ([x], [y]) in the canvas's local coordinates. */
    fun onTap(pointerCount: Int, x: Float, y: Float) {
        val now = System.nanoTime()
        tapTimestamps.addLast(now)
        pruneOldTaps(now)
        energyTarget = min(1f, tapTimestamps.size / 6f)
        // Multi-finger taps read as a bigger jolt of excitement.
        energy = min(1f, energy + 0.12f * pointerCount)
        ripples.add(TapRipple(x = x, y = y, birth = elapsed, hue = hueBase))
    }

    /** A finger held still long enough to count as a long press: the spiral "breathes". */
    fun onLongPress() {
        pulse = 1f
    }

    /** Horizontal drag distance in pixels for this frame; spins the spiral like a flick. */
    fun onDrag(dxPixels: Float) {
        dragVelocity += dxPixels * 0.02f
        dragVelocity = dragVelocity.coerceIn(-6f, 6f)
    }

    /** A soft breathing multiplier driven by [pulse], meant to modulate radius. */
    fun breathe(timeSeconds: Float): Float = 1f + pulse * 0.18f * sin(timeSeconds * 10f)

    private fun pruneOldTaps(nowNanos: Long) {
        while (tapTimestamps.isNotEmpty() && nowNanos - tapTimestamps.first() > tapWindowNanos) {
            tapTimestamps.removeFirst()
        }
    }
}
