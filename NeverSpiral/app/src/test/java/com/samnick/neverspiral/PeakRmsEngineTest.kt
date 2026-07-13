package com.samnick.neverspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakRmsEngineTest {

    @Test
    fun `starts at the floor`() {
        val engine = PeakRmsEngine()
        assertEquals(PeakRmsEngine.FLOOR_DB, engine.peakDb, 0.001f)
        assertEquals(PeakRmsEngine.FLOOR_DB, engine.rmsDb, 0.001f)
        assertEquals(0f, engine.crestFactorDb, 0.001f)
    }

    @Test
    fun `peak attacks far faster than RMS for the same hit`() {
        val engine = PeakRmsEngine()
        engine.step(0.02f, rawRms = 1f, rawPeak = 1f)
        // Peak's attack time constant (1ms) is ~300x faster than RMS's (300ms), so one 20ms tick
        // should already read near the ceiling for peak while RMS has barely moved off the floor.
        assertTrue("peak should have nearly reached the ceiling, got ${engine.peakDb}", engine.peakDb > -1f)
        assertTrue("RMS should still be far from the ceiling, got ${engine.rmsDb}", engine.rmsDb < PeakRmsEngine.FLOOR_DB + 10f)
        assertTrue(engine.peakDb > engine.rmsDb)
    }

    @Test
    fun `crest factor reflects the gap between peak and RMS`() {
        val engine = PeakRmsEngine()
        // A single sharp transient (peak=1) riding on a quiet, settled bed (rms=0.1) -- let peak
        // and RMS both fully settle so the comparison isn't confounded by attack/release timing.
        repeat(60) { engine.step(0.05f, rawRms = 0.1f, rawPeak = 1f) }
        assertTrue(engine.crestFactorDb > 15f)
        assertEquals(engine.peakDb - engine.rmsDb, engine.crestFactorDb, 0.01f)
    }

    @Test
    fun `peak hold latches through a brief dip then falls after the hold time`() {
        val engine = PeakRmsEngine()
        engine.step(0.02f, rawRms = 1f, rawPeak = 1f)
        val heldPeak = engine.peakHoldDb
        assertTrue(heldPeak > -1f)

        // 1.0s of silence -- less than the 1.5s hold window, so the cap should still be latched.
        repeat(10) { engine.step(0.1f, rawRms = 0f, rawPeak = 0f) }
        assertEquals(heldPeak, engine.peakHoldDb, 0.001f)

        // Past the 1.5s hold window now -- the cap should have started falling.
        repeat(10) { engine.step(0.1f, rawRms = 0f, rawPeak = 0f) }
        assertTrue("hold cap should have started falling, got ${engine.peakHoldDb}", engine.peakHoldDb < heldPeak)
    }

    @Test
    fun `reset drops everything back to the floor`() {
        val engine = PeakRmsEngine()
        repeat(30) { engine.step(0.05f, rawRms = 1f, rawPeak = 1f) }
        engine.reset()
        assertEquals(PeakRmsEngine.FLOOR_DB, engine.peakDb, 0.001f)
        assertEquals(PeakRmsEngine.FLOOR_DB, engine.rmsDb, 0.001f)
        assertEquals(PeakRmsEngine.FLOOR_DB, engine.peakHoldDb, 0.001f)
        assertEquals(0f, engine.crestFactorDb, 0.001f)
    }
}
