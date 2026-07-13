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
    fun `ballistics respond, not jump, to a step change`() {
        val engine = VuMeterEngine()
        engine.step(0.05f, rawRms = 1f)
        // A real VU meter takes ~300ms to reach 99% of a step -- a single 50ms tick should have
        // moved noticeably but should not already be at the ceiling.
        assertTrue("expected partial movement, got ${engine.dbVu}", engine.dbVu > VuMeterEngine.SCALE_MIN_DB_VU + 5f)
        assertTrue("expected partial movement, got ${engine.dbVu}", engine.dbVu < VuMeterEngine.SCALE_MAX_DB_VU - 1f)
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
