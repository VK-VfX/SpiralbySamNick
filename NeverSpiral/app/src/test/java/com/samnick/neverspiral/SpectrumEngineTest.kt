package com.samnick.neverspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumEngineTest {

    @Test
    fun `starts at zero`() {
        val engine = SpectrumEngine(1)
        assertEquals(0f, engine.bands[0], 0.001f)
        assertEquals(0f, engine.peaks[0], 0.001f)
    }

    @Test
    fun `rises faster than it falls, for the same time step`() {
        val rising = SpectrumEngine(1)
        rising.step(0.05f, floatArrayOf(1f))
        val roseBy = rising.bands[0]

        val falling = SpectrumEngine(1)
        falling.step(0.05f, floatArrayOf(1f)) // get it up to the same starting point first
        falling.step(0.05f, floatArrayOf(0f))
        val fellBy = roseBy - falling.bands[0]

        // Rise time constant (30ms) is 5x faster than fall (150ms), so a 50ms tick rises a much
        // larger fraction of the remaining distance than an equal tick falls.
        assertTrue("rose=$roseBy fellBy=$fellBy", roseBy > fellBy)
    }

    @Test
    fun `peak hold latches to a new high and only falls slowly afterward`() {
        val engine = SpectrumEngine(1)
        engine.step(0.05f, floatArrayOf(1f))
        val peakAfterHit = engine.peaks[0]
        assertTrue(peakAfterHit > 0f)

        engine.step(0.05f, floatArrayOf(0f))
        // Peak-hold falls at 1/PEAK_FALL_SECONDS per second regardless of how fast the band
        // itself drops, so one more 50ms tick should only shave a small amount off the top.
        val fallenBy = peakAfterHit - engine.peaks[0]
        assertTrue("fell too little: $fallenBy", fallenBy > 0f)
        assertTrue("fell too much: $fallenBy", fallenBy < 0.1f)
    }

    @Test
    fun `reset clears bands and peaks`() {
        val engine = SpectrumEngine(3)
        engine.step(0.05f, floatArrayOf(1f, 1f, 1f))
        engine.reset()
        assertTrue(engine.bands.all { it == 0f })
        assertTrue(engine.peaks.all { it == 0f })
    }
}
