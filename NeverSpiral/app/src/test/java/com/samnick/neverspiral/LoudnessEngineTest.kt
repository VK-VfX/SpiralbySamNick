package com.samnick.neverspiral

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessEngineTest {

    private fun tone(amplitude: Float, sampleCount: Int = 44100, sampleRate: Double = 44100.0): FloatArray =
        FloatArray(sampleCount) { i -> sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat() * amplitude }

    @Test
    fun `starts silent`() {
        val engine = LoudnessEngine()
        assertEquals(LoudnessEngine.SILENCE_LUFS, engine.momentaryLufs, 0.001f)
        assertEquals(LoudnessEngine.SILENCE_LUFS, engine.shortTermLufs, 0.001f)
        assertEquals(LoudnessEngine.SILENCE_LUFS, engine.integratedLufs, 0.001f)
        assertEquals(0f, engine.loudnessRange, 0.001f)
    }

    @Test
    fun `a real signal reads well above silence`() {
        val engine = LoudnessEngine()
        engine.ingest(tone(0.8f))
        engine.step(0.05f)
        assertTrue("expected a real reading, got ${engine.momentaryLufs}", engine.momentaryLufs > LoudnessEngine.SILENCE_LUFS + 10f)
        assertTrue("LUFS shouldn't exceed 0", engine.momentaryLufs <= 0f)
    }

    @Test
    fun `louder input reads louder`() {
        val loud = LoudnessEngine()
        val quiet = LoudnessEngine()
        loud.ingest(tone(0.9f))
        quiet.ingest(tone(0.05f))
        loud.step(0.05f)
        quiet.step(0.05f)
        assertTrue(loud.momentaryLufs > quiet.momentaryLufs)
    }

    @Test
    fun `integrated loudness stays silent until a step runs`() {
        val engine = LoudnessEngine()
        engine.ingest(tone(0.8f))
        // ingest alone only updates the running mean-square estimates; integrated loudness is
        // only folded in by step().
        assertEquals(LoudnessEngine.SILENCE_LUFS, engine.integratedLufs, 0.001f)
    }

    @Test
    fun `reset drops everything back to silence`() {
        val engine = LoudnessEngine()
        engine.ingest(tone(0.8f))
        repeat(5) { engine.step(0.5f) }
        engine.reset()
        assertEquals(LoudnessEngine.SILENCE_LUFS, engine.momentaryLufs, 0.001f)
        assertEquals(LoudnessEngine.SILENCE_LUFS, engine.shortTermLufs, 0.001f)
        assertEquals(LoudnessEngine.SILENCE_LUFS, engine.integratedLufs, 0.001f)
        assertEquals(0f, engine.loudnessRange, 0.001f)
        assertTrue(engine.history.all { it == LoudnessEngine.SILENCE_LUFS })
    }
}
