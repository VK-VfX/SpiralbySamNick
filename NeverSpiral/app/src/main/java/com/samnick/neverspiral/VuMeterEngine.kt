package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

/**
 * Emulates a real analog VU meter's ballistics for one channel.
 *
 * A true VU meter is not a peak meter: ANSI C16.5-1942 defines its response as reaching 99% of
 * a step change in 300ms, and -- unlike a peak program meter's fast-attack/slow-release -- that
 * same 300ms time constant applies symmetrically on the way up and the way down. That single-pole
 * response is what makes the needle read average program energy and ignore brief transients
 * (a snare hit, a consonant) rather than jumping to every instantaneous sample peak.
 *
 * The smoothing is applied directly in the dB domain (the standard, simple way to emulate this
 * digitally), then calibrated so 0 dBVU corresponds to -18 dBFS: the professional reference level
 * that leaves headroom above 0 for transients to peak into before the signal clips digitally.
 *
 * A single red peak LED, not a two-color ladder -- an earlier version added a second amber
 * "approaching" LED, but a real analog VU meter's peak indicator is exactly this: one lamp for
 * "you hit the top of the scale," full stop. [ledBrightness] *blinks* rather than staying lit
 * solid while the needle sits at the top of the scale -- a plain hold-then-decay reads as a
 * static "on" light the instant a loud passage settles at the ceiling, which looks like a stuck
 * indicator rather than an active warning. Instead, the moment the needle first reaches peak the
 * LED flashes on immediately, then repeats a fixed on/off cycle every [LED_BLINK_PERIOD_SECONDS]
 * for as long as the needle keeps reading at peak -- exactly the "still clipping" strobe behavior
 * real peak/clip indicators use, rather than one flash that then just sits there. Only once the
 * needle genuinely drops back below peak (past a brief [PEAK_GRACE_SECONDS] hysteresis window, so
 * a single sample right at the boundary doesn't restart the blink cycle) does the LED stop
 * blinking and fade out smoothly over [LED_DECAY_TAU_SECONDS].
 */
class VuMeterEngine {
    var dbVu by mutableFloatStateOf(SCALE_MIN_DB_VU)
        private set

    private var smoothedDbFs = SILENCE_FLOOR_DBFS
    private var ledBrightness = 0f
    private var isAtPeak = false
    private var peakGraceRemainingSeconds = 0f
    private var blinkPhaseSeconds = 0f

    /**
     * Advance the needle by [dtSeconds] toward the level implied by [rawRms] (linear, ~0..1).
     * [calibrationOffsetDb] is adjustable (default [VuMeterSettings.DEFAULT_CALIBRATION_OFFSET_DB],
     * the standard -18 dBFS reference) so headroom can be tuned for a hotter or cooler source.
     */
    fun step(dtSeconds: Float, rawRms: Float, calibrationOffsetDb: Float = VuMeterSettings.DEFAULT_CALIBRATION_OFFSET_DB) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)

        val alpha = 1f - exp(-dt / BALLISTIC_TAU_SECONDS)
        val targetDbFs = amplitudeToDbFs(rawRms)
        smoothedDbFs += (targetDbFs - smoothedDbFs) * alpha

        dbVu = (smoothedDbFs + calibrationOffsetDb).coerceIn(SCALE_MIN_DB_VU, SCALE_MAX_DB_VU)

        if (dbVu >= SCALE_MAX_DB_VU - PEAK_TOLERANCE_DB) {
            if (!isAtPeak) blinkPhaseSeconds = 0f // fresh arrival at peak: flash on immediately
            isAtPeak = true
            peakGraceRemainingSeconds = PEAK_GRACE_SECONDS
        } else if (peakGraceRemainingSeconds > 0f) {
            peakGraceRemainingSeconds -= dt
        } else {
            isAtPeak = false
        }

        if (isAtPeak) {
            blinkPhaseSeconds += dt
            if (blinkPhaseSeconds >= LED_BLINK_PERIOD_SECONDS) blinkPhaseSeconds -= LED_BLINK_PERIOD_SECONDS
            ledBrightness = if (blinkPhaseSeconds < LED_BLINK_PERIOD_SECONDS * LED_BLINK_ON_FRACTION) 1f else 0f
        } else {
            val decayAlpha = 1f - exp(-dt / LED_DECAY_TAU_SECONDS)
            ledBrightness -= ledBrightness * decayAlpha
        }
    }

    /** 0..1 brightness for the peak LED: blinks on/off while at peak, decays smoothly once it isn't. */
    fun peakLedBrightness(): Float = ledBrightness

    /** Drop the needle back to rest, e.g. when capture stops. */
    fun reset() {
        smoothedDbFs = SILENCE_FLOOR_DBFS
        dbVu = SCALE_MIN_DB_VU
        ledBrightness = 0f
        isAtPeak = false
        peakGraceRemainingSeconds = 0f
        blinkPhaseSeconds = 0f
    }

    companion object {
        const val SCALE_MIN_DB_VU = -20f
        const val SCALE_MAX_DB_VU = 3f

        /** How often the peak LED repeats its on/off cycle while continuously at peak -- public so
         * tests can assert period consistency without hardcoding a duplicate literal. */
        const val LED_BLINK_PERIOD_SECONDS = 1f

        private const val SILENCE_FLOOR_DBFS = -80f
        private const val AMPLITUDE_FLOOR = 0.00007f // ~ -83 dBFS; keeps log10 away from zero

        // A step reaching 99% of its final value in 300ms, for a single-pole exponential
        // response, implies tau = 300ms / ln(100).
        private val BALLISTIC_TAU_SECONDS = 0.3f / ln(100f)

        private const val PEAK_TOLERANCE_DB = 0.15f
        private const val LED_DECAY_TAU_SECONDS = 0.45f

        /** Fraction of each blink cycle the LED spends lit -- lit for the first half, dark for the
         * second, an even on/off strobe rather than a brief flash lost in a long dark gap. */
        private const val LED_BLINK_ON_FRACTION = 0.5f

        /** A brief hysteresis window so one sample dipping just under the peak threshold (which
         * happens constantly with real program material bouncing right at the edge) doesn't read as
         * "left peak" and restart the blink cycle from scratch -- only a genuine, sustained drop
         * stops the blinking. */
        private const val PEAK_GRACE_SECONDS = 0.05f

        private fun amplitudeToDbFs(rms: Float): Float {
            val clamped = rms.coerceAtLeast(AMPLITUDE_FLOOR)
            return (20f * log10(clamped)).coerceAtLeast(SILENCE_FLOOR_DBFS)
        }
    }
}
