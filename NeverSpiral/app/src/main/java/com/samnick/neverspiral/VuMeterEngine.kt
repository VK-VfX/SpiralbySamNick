package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

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
 * A separate [peakHold] value drives a peak LED: it latches on the instant the needle hits the
 * top of the scale and fades out over [PEAK_HOLD_SECONDS], independent of the needle's own much
 * slower ballistic fall, so a single loud hit still reads clearly as a peak.
 */
class VuMeterEngine {
    var dbVu by mutableFloatStateOf(SCALE_MIN_DB_VU)
        private set

    private var smoothedDbFs = SILENCE_FLOOR_DBFS
    private var elapsed = 0f
    private var peakHold = 0f

    /** Advance the needle by [dtSeconds] toward the level implied by [rawRms] (linear, ~0..1). */
    fun step(dtSeconds: Float, rawRms: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        elapsed += dt

        val alpha = 1f - exp(-dt / BALLISTIC_TAU_SECONDS)
        val targetDbFs = amplitudeToDbFs(rawRms)
        smoothedDbFs += (targetDbFs - smoothedDbFs) * alpha

        dbVu = (smoothedDbFs + CALIBRATION_OFFSET_DB).coerceIn(SCALE_MIN_DB_VU, SCALE_MAX_DB_VU)

        peakHold = if (dbVu >= SCALE_MAX_DB_VU - PEAK_TOLERANCE_DB) {
            1f
        } else {
            (peakHold - dt / PEAK_HOLD_SECONDS).coerceAtLeast(0f)
        }
    }

    /** 0..1 brightness for the peak LED: smoothly pulses while a peak is held, otherwise off. */
    fun peakLedBrightness(): Float {
        if (peakHold <= 0f) return 0f
        val pulse = 0.5f + 0.5f * sin(2f * PI.toFloat() * BLINK_HZ * elapsed)
        return peakHold * pulse
    }

    /** Drop the needle back to rest, e.g. when capture stops. */
    fun reset() {
        smoothedDbFs = SILENCE_FLOOR_DBFS
        dbVu = SCALE_MIN_DB_VU
        peakHold = 0f
    }

    companion object {
        /** Standard VU reference: 0 dBVU corresponds to -18 dBFS. */
        const val CALIBRATION_OFFSET_DB = 18f
        const val SCALE_MIN_DB_VU = -20f
        const val SCALE_MAX_DB_VU = 3f

        private const val SILENCE_FLOOR_DBFS = -80f
        private const val AMPLITUDE_FLOOR = 0.00007f // ~ -83 dBFS; keeps log10 away from zero

        // A step reaching 99% of its final value in 300ms, for a single-pole exponential
        // response, implies tau = 300ms / ln(100).
        private val BALLISTIC_TAU_SECONDS = 0.3f / ln(100f)

        private const val PEAK_TOLERANCE_DB = 0.15f
        private const val PEAK_HOLD_SECONDS = 1.2f
        private const val BLINK_HZ = 5f

        private fun amplitudeToDbFs(rms: Float): Float {
            val clamped = rms.coerceAtLeast(AMPLITUDE_FLOOR)
            return (20f * log10(clamped)).coerceAtLeast(SILENCE_FLOOR_DBFS)
        }
    }
}
