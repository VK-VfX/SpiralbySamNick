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
    fun `LED flashes on as soon as the needle reaches peak, not gradually`() {
        val engine = VuMeterEngine()
        var reachedPeak = false
        repeat(20) {
            engine.step(0.05f, rawRms = 1f)
            if (engine.dbVu >= VuMeterEngine.SCALE_MAX_DB_VU - 0.15f) reachedPeak = true
        }
        assertTrue("expected the needle to actually reach peak within 20 steps", reachedPeak)
        assertTrue(
            "expected the LED to already be lit once the needle reaches peak, got ${engine.peakLedBrightness()}",
            engine.peakLedBrightness() > 0.9f,
        )
    }

    @Test
    fun `peak LED blinks on and off while continuously at peak, rather than staying solid`() {
        val engine = VuMeterEngine()
        var sawOn = false
        var sawOff = false
        // 60 steps of 50ms = 3s: comfortably covers several blink cycles once the needle settles
        // at peak (which happens within the first few steps).
        repeat(60) {
            engine.step(0.05f, rawRms = 1f)
            val brightness = engine.peakLedBrightness()
            if (brightness > 0.9f) sawOn = true
            if (brightness < 0.1f) sawOff = true
        }
        assertTrue("expected the LED to be fully on at some point", sawOn)
        assertTrue(
            "expected the LED to be fully off at some point while still at peak -- a real blink, not a solid light",
            sawOff,
        )
    }

    @Test
    fun `successive blink-off transitions repeat every LED_BLINK_PERIOD_SECONDS`() {
        val engine = VuMeterEngine()
        val dt = 0.02f
        var elapsed = 0f
        var wasOn = false
        val offTimestamps = mutableListOf<Float>()
        repeat(250) { // 5s, several full blink cycles
            engine.step(dt, rawRms = 1f)
            elapsed += dt
            val on = engine.peakLedBrightness() > 0.5f
            if (wasOn && !on) offTimestamps.add(elapsed)
            wasOn = on
        }
        assertTrue("expected at least 2 blink-off transitions within 5s, got ${offTimestamps.size}", offTimestamps.size >= 2)
        for (i in 1 until offTimestamps.size) {
            val gap = offTimestamps[i] - offTimestamps[i - 1]
            assertEquals(
                "expected successive blinks to repeat every ${VuMeterEngine.LED_BLINK_PERIOD_SECONDS}s, got a ${gap}s gap",
                VuMeterEngine.LED_BLINK_PERIOD_SECONDS, gap, 0.05f,
            )
        }
    }

    @Test
    fun `LED decays smoothly once it truly leaves peak, rather than snapping off`() {
        val engine = VuMeterEngine()
        // 0.4s of driving at full scale -- past the crossing point and still within the first
        // blink's on phase, so this is a real assertion, not a coincidence of timing.
        repeat(20) { engine.step(0.02f, rawRms = 1f) }
        assertTrue("expected the LED to be lit while still driving to peak", engine.peakLedBrightness() > 0.5f)

        // 4s of silence: comfortably past the brief peak-grace hysteresis plus the full decay tail.
        repeat(80) { engine.step(0.05f, rawRms = 0f) }
        assertTrue("expected the LED to have decayed back down", engine.peakLedBrightness() < 0.01f)
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
