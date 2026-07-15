package com.samnick.neverspiral

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the onset detector carried over unchanged from Bass Drop Shockwave:
 * [SpectrumEngine.bands] is hard-clamped to [0, 1], so a *ratio* threshold against the rolling
 * baseline (the original, broken implementation predating both modes) becomes mathematically
 * unreachable once the baseline climbs high enough -- see [radialBurstShouldTrigger]'s doc
 * comment. These tests pin the current additive-delta behavior so that regression can't silently
 * come back.
 */
class RadialSpectrumBurstScreenTest {

    @Test
    fun `still triggers when the baseline is loud, not near-silent`() {
        // The old ratio threshold (1.6x) would require bassEnergy >= 1.28 here, impossible since
        // bands are clamped to 1f -- the exact failure mode this test guards against. The
        // additive delta only needs a 0.15 jump above baseline, well within the clamped range.
        val baseline = 0.8f
        val bassEnergy = 1f
        assertTrue(radialBurstShouldTrigger(bassEnergy, baseline, timeSinceTriggerSeconds = 10f, scale = 1f))
    }

    @Test
    fun `does not trigger on a small fluctuation above baseline`() {
        val baseline = 0.5f
        val bassEnergy = 0.55f // +0.05, below the 0.15 default delta
        assertFalse(radialBurstShouldTrigger(bassEnergy, baseline, timeSinceTriggerSeconds = 10f, scale = 1f))
    }

    @Test
    fun `does not trigger below the absolute energy floor even with a big relative jump`() {
        // A high scale shrinks the delta threshold to 0.015, which this jump clears on its own --
        // isolating the separate MIN_ABSOLUTE_ENERGY floor (0.06) as the only thing blocking it.
        val baseline = 0f
        val bassEnergy = 0.05f
        assertFalse(radialBurstShouldTrigger(bassEnergy, baseline, timeSinceTriggerSeconds = 10f, scale = 10f))
    }

    @Test
    fun `does not retrigger before the debounce window elapses`() {
        val baseline = 0.3f
        val bassEnergy = 0.9f // easily clears the delta threshold on its own
        assertFalse(radialBurstShouldTrigger(bassEnergy, baseline, timeSinceTriggerSeconds = 0.05f, scale = 1f))
    }

    @Test
    fun `higher scale makes triggering easier`() {
        val baseline = 0.5f
        val bassEnergy = 0.6f // +0.10 -- below the default 0.15 delta but not by much

        assertFalse(radialBurstShouldTrigger(bassEnergy, baseline, timeSinceTriggerSeconds = 10f, scale = 1f))
        assertTrue(radialBurstShouldTrigger(bassEnergy, baseline, timeSinceTriggerSeconds = 10f, scale = 2f))
    }
}
