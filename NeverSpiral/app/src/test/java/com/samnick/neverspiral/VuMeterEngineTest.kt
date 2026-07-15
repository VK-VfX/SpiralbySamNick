package com.samnick.neverspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VuMeterEngineTest {

    @Test
    fun `starts resting at the scale minimum`() {
        val engine = VuMeterEngine()
        assertEquals(VuMeterEngine.SCALE_MIN_DB_VU, engine.dbVu, 0.001f)
        assertEquals(0f, engine.peakLedBrightness(), 0.001f)
    }

    @Test
    fun `settles at the scale maximum for a sustained full-scale signal`() {
        val engine = VuMeterEngine()
        // 40 steps of 50ms = 2s, comfortably past 5x the ~65ms ballistic time constant.
        repeat(40) { engine.step(0.05f, rawRms = 1f) }
        assertEquals(VuMeterEngine.SCALE_MAX_DB_VU, engine.dbVu, 0.05f)
    }

    @Test
    fun `stays at the scale minimum for silence`() {
        val engine = VuMeterEngine()
        repeat(10) { engine.step(0.05f, rawRms = 0f) }
        assertEquals(VuMeterEngine.SCALE_MIN_DB_VU, engine.dbVu, 0.001f)
    }

    @Test
    fun `ballistics respond gradually, not instantly, to a step change`() {
        val engine = VuMeterEngine()
        engine.step(0.05f, rawRms = 1f)
        val afterOneStep = engine.dbVu
        // The smoothing happens over the full internal dBFS range (silence starts far below the
        // visible -20..+3 scale), so a single 50ms tick barely clears the resting floor -- it
        // should not yet be at the ceiling, but 300ms (a handful more ticks) should carry it
        // much closer, matching the ~300ms-to-99%-of-a-step ANSI ballistic response.
        assertTrue("expected some movement off the floor, got $afterOneStep", afterOneStep > VuMeterEngine.SCALE_MIN_DB_VU)
        assertTrue("expected still short of the ceiling, got $afterOneStep", afterOneStep < VuMeterEngine.SCALE_MAX_DB_VU - 1f)

        repeat(5) { engine.step(0.05f, rawRms = 1f) }
        assertTrue("expected further movement after more ticks", engine.dbVu > afterOneStep)
    }

    @Test
    fun `peak LED flashes to full brightness and decays, not an instant cutoff`() {
        val engine = VuMeterEngine()
        repeat(40) { engine.step(0.05f, rawRms = 1f) }
        assertEquals(1f, engine.peakLedBrightness(), 0.001f)

        engine.step(0.01f, rawRms = 0f)
        assertTrue("LED should still be mostly lit right after the hit ends", engine.peakLedBrightness() > 0.5f)

        repeat(60) { engine.step(0.05f, rawRms = 0f) }
        assertTrue("LED should have decayed back down", engine.peakLedBrightness() < 0.01f)
    }

    @Test
    fun `peak LED holds at full brightness before decay begins`() {
        val engine = VuMeterEngine()
        repeat(40) { engine.step(0.05f, rawRms = 1f) }
        assertEquals(1f, engine.peakLedBrightness(), 0.001f)

        // 6 silent steps = 0.3s, safely inside the 0.4s hold window -- should not have decayed
        // at all yet, unlike a plain exponential decay which would start fading immediately.
        repeat(6) { engine.step(0.05f, rawRms = 0f) }
        assertEquals(1f, engine.peakLedBrightness(), 0.001f)

        // 8 more silent steps = 0.7s total elapsed, safely past the hold window plus some decay.
        repeat(8) { engine.step(0.05f, rawRms = 0f) }
        assertTrue("expected the LED to be decaying by now, got ${engine.peakLedBrightness()}", engine.peakLedBrightness() < 0.9f)
    }

    @Test
    fun `reset drops the needle and LED back to rest`() {
        val engine = VuMeterEngine()
        repeat(40) { engine.step(0.05f, rawRms = 1f) }
        engine.reset()
        assertEquals(VuMeterEngine.SCALE_MIN_DB_VU, engine.dbVu, 0.001f)
        assertEquals(0f, engine.peakLedBrightness(), 0.001f)
    }

    @Test
    fun `calibration offset shifts the reading`() {
        val hot = VuMeterEngine()
        val cool = VuMeterEngine()
        repeat(40) {
            hot.step(0.05f, rawRms = 0.1f, calibrationOffsetDb = 24f)
            cool.step(0.05f, rawRms = 0.1f, calibrationOffsetDb = 12f)
        }
        assertTrue("higher calibration offset should read hotter", hot.dbVu > cool.dbVu)
    }
}
