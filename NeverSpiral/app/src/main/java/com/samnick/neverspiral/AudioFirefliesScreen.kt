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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val MAX_PARTICLES = 200

/** Base spawn attempts per second at full band level -- scaled down per band by that band's own
 * level, and converted through delta time like every rate in this app, so spawn density stays
 * consistent across refresh rates rather than spiking on faster displays. */
private const val SPAWN_RATE_PER_SECOND = 40f

/** Exponent on band level before it drives spawn probability: <1 lifts moderate levels so most of
 * the spectrum visibly spawns particles rather than only the loudest transients, matching the
 * "gamma < 1 lifts quiet content" convention every other reactive mode uses. */
private const val SPAWN_LEVEL_GAMMA = 0.8f

private const val LIFE_MIN_SECONDS = 0.9f
private const val LIFE_MAX_SECONDS = 2.2f

private const val SPEED_MIN_FRACTION = 0.16f
private const val SPEED_MAX_FRACTION = 0.62f

/** Exponential drag on velocity, expressed as a time constant. Deliberately long relative to
 * particle life (see [LIFE_MIN_SECONDS]/[LIFE_MAX_SECONDS]): a short tau here decays a particle's
 * launch speed to near-zero well before it dies, which is what made earlier tuning look like
 * particles barely left the center -- a spark should still be visibly moving for most of its life,
 * not just its first few frames. */
private const val DRAG_TAU_SECONDS = 1.6f

/** Gentle constant upward drift applied to every particle's vertical velocity, as a fraction of
 * the canvas's shorter dimension per second-squared -- the "firefly" rather than "spark" feel. */
private const val BUOYANCY_FRACTION = 0.10f

private const val PARTICLE_RADIUS_FRACTION = 0.012f
private const val GLOW_RADIUS_FRACTION = 0.024f
private const val GLOW_ALPHA = 175

/**
 * A particle-burst mode -- deliberately the opposite shape language from every bar/ring mode in
 * the app: instead of a fixed row or circle of positions each reading one frequency band, a pool
 * of up to [MAX_PARTICLES] fireflies is spawned and killed continuously, each one a one-shot
 * physics object (position, velocity, remaining life) rather than a value tied to a stable slot.
 *
 * Spawning is probabilistic and dt-scaled rather than edge-triggered: each frame, each of
 * [SpectrumEngine]'s bands gets a spawn chance of `level^[SPAWN_LEVEL_GAMMA] * scale *
 * [SPAWN_RATE_PER_SECOND] * dt`, so expected spawns per second stay correct regardless of the
 * display's refresh rate, exactly like every other rate in this app. A spawned particle launches
 * in a random direction (not tied to its band's angle -- an intentionally chaotic scatter, unlike
 * every angle-mapped mode elsewhere) at a speed scaled by that band's own level, colored via
 * [frequencyZoneColor] keyed to which band spawned it, so bass bursts read warm and treble bursts
 * read cool without needing per-particle data beyond its own color and physics state.
 *
 * Each live particle is a small, real physics object: exponential drag on velocity (a launch that
 * eases off, not a constant coast) plus a gentle constant upward buoyancy, both converted through
 * real per-frame delta time. Particles are pre-allocated once into a fixed-size pool and reused
 * (dead slots overwritten on the next spawn) rather than allocated per spawn, avoiding per-frame
 * garbage the way every array-based engine in this app already does. Glow is the same
 * draw-solid-then-blur-once technique used everywhere else: every live particle drawn solid into
 * one offscreen bitmap, blurred a single time, composited under the crisp layer.
 */
@Composable
fun AudioFirefliesScreen(engine: SpectrumEngine, settings: BarSpectrumSettings) {
    val particles = remember { Array(MAX_PARTICLES) { Firefly() } }
    val particlesHolder = remember { arrayOfNulls<Bitmap>(1) }
    val glowHolder = remember { arrayOfNulls<Bitmap>(1) }
    val lastElapsedHolder = remember { floatArrayOf(0f) }
    val random = remember { Random(System.nanoTime()) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        engine.elapsed

        val dt = (engine.elapsed - lastElapsedHolder[0]).coerceIn(0f, 0.1f)
        lastElapsedHolder[0] = engine.elapsed

        val widthPx = size.width.toInt().coerceAtLeast(1)
        val heightPx = size.height.toInt().coerceAtLeast(1)
        var particlesBitmap = particlesHolder[0]
        if (particlesBitmap == null || particlesBitmap.width != widthPx || particlesBitmap.height != heightPx) {
            particlesBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            particlesHolder[0] = particlesBitmap
        }
        var glow = glowHolder[0]
        if (glow == null || glow.width != widthPx || glow.height != heightPx) {
            glow = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            glowHolder[0] = glow
        }

        val minDim = size.minDimension
        val speedScale = (settings.height / BarSpectrumSettings.HEIGHT_MAX)
        val dragAlpha = 1f - exp(-dt / DRAG_TAU_SECONDS)
        val radius = minDim * PARTICLE_RADIUS_FRACTION * settings.strokeWeight
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        val bandCount = engine.bands.size
        for (b in 0 until bandCount) {
            val level = engine.bands[b].coerceIn(0f, 1f)
            val spawnChance = level.pow(SPAWN_LEVEL_GAMMA) * settings.scale * SPAWN_RATE_PER_SECOND * dt
            if (spawnChance <= 0f || random.nextFloat() >= spawnChance) continue

            val slot = particles.firstOrNull { !it.alive } ?: continue
            val angle = random.nextFloat() * (2f * Math.PI.toFloat())
            val speed = minDim * (SPEED_MIN_FRACTION + (SPEED_MAX_FRACTION - SPEED_MIN_FRACTION) * level) * speedScale
            slot.alive = true
            slot.x = centerX
            slot.y = centerY
            slot.vx = cos(angle) * speed
            slot.vy = sin(angle) * speed
            slot.life = LIFE_MIN_SECONDS + (LIFE_MAX_SECONDS - LIFE_MIN_SECONDS) * random.nextFloat()
            slot.maxLife = slot.life
            slot.color = frequencyZoneColor(b.toFloat() / (bandCount - 1).coerceAtLeast(1)).toArgb()
        }

        val particlesCanvas = AndroidCanvas(particlesBitmap)
        particlesCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val particlePaint = AndroidPaint().apply { isAntiAlias = true }

        for (p in particles) {
            if (!p.alive) continue
            p.vy -= minDim * BUOYANCY_FRACTION * dt
            p.vx -= p.vx * dragAlpha
            p.vy -= p.vy * dragAlpha
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
            if (p.life <= 0f) {
                p.alive = false
                continue
            }
            val lifeFraction = (p.life / p.maxLife).coerceIn(0f, 1f)
            particlePaint.color = p.color
            particlePaint.alpha = (lifeFraction * 255).toInt()
            particlesCanvas.drawCircle(p.x, p.y, radius * (0.5f + 0.5f * lifeFraction), particlePaint)
        }

        val glowCanvas = AndroidCanvas(glow)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val glowPaint = AndroidPaint().apply {
            isAntiAlias = true
            alpha = GLOW_ALPHA
            maskFilter = BlurMaskFilter(minDim * GLOW_RADIUS_FRACTION, BlurMaskFilter.Blur.NORMAL)
        }
        glowCanvas.drawBitmap(particlesBitmap, 0f, 0f, glowPaint)

        drawRect(color = VisualizerTheme.CANVAS_BACKGROUND)
        drawImage(glow.asImageBitmap())
        drawImage(particlesBitmap.asImageBitmap())
    }
}

/** One reusable slot in the particle pool -- plain mutable fields, not a data class, so the pool
 * can be allocated once and mutated in place with no per-spawn or per-frame allocation. */
private class Firefly {
    var alive = false
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var life = 0f
    var maxLife = 0f
    var color = 0
}
