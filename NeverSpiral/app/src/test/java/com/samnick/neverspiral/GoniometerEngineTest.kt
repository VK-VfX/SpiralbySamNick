package com.samnick.neverspiral

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoniometerEngineTest {

    /** A long single buffer so the leaky-integrator correlation nearly fully converges in one [GoniometerEngine.ingest] call. */
    private fun tone(frequencyHz: Double, sampleCount: Int = 44100 * 2, sampleRate: Double = 44100.0): FloatArray =
        FloatArray(sampleCount) { i -> sin(2.0 * PI * frequencyHz * i / sampleRate).toFloat() * 0.5f }

    @Test
    fun `identical left and right read as fully in phase`() {
        val engine = GoniometerEngine()
        val left = tone(440.0)
        engine.ingest(left, left.copyOf())
        assertTrue("expected correlation near +1, got ${engine.correlation}", engine.correlation > 0.99f)
    }

    @Test
    fun `inverted right reads as fully out of phase`() {
        val engine = GoniometerEngine()
        val left = tone(440.0)
        val right = FloatArray(left.size) { -left[it] }
        engine.ingest(left, right)
        assertTrue("expected correlation near -1, got ${engine.correlation}", engine.correlation < -0.99f)
    }

    @Test
    fun `unrelated content reads as roughly uncorrelated`() {
        val engine = GoniometerEngine()
        engine.ingest(tone(440.0), tone(977.0))
        assertTrue("expected correlation near 0, got ${engine.correlation}", kotlin.math.abs(engine.correlation) < 0.15f)
    }

    @Test
    fun `generation increments once per ingest, even for an empty buffer`() {
        val engine = GoniometerEngine()
        assertEquals(0, engine.generation)
        engine.ingest(FloatArray(0), FloatArray(0))
        assertEquals(1, engine.generation)
        engine.ingest(floatArrayOf(0.1f), floatArrayOf(0.1f))
        assertEquals(2, engine.generation)
    }

    @Test
    fun `reset clears the buffers and correlation`() {
        val engine = GoniometerEngine()
        val left = tone(440.0)
        engine.ingest(left, left.copyOf())
        engine.reset()
        assertEquals(0f, engine.correlation, 0.001f)
        assertEquals(0, engine.latestLeft.size)
        assertEquals(0, engine.latestRight.size)
    }
}
