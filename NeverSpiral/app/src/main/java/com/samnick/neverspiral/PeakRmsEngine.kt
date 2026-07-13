package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.log10

/**
 * A hardware-style dual peak/RMS meter, unlike the VU meter's symmetric analog ballistics: peak
 * has a near-instant attack and a slower release (so it actually catches transients instead of
 * averaging them away), RMS averages over the same ~300ms window the VU meter uses, and a
 * peak-hold cap latches at the highest peak seen and slowly falls -- the classic three-element
 * combo on serious studio meters. The gap between peak and RMS, the crest factor, is a genuinely
 * useful number for judging how compressed/limited a master is: a wide crest factor means a
 * dynamic recording, a narrow one means it's been squashed toward a wall of loudness.
 */
class PeakRmsEngine {
    var peakDb by mutableFloatStateOf(FLOOR_DB)
        private set
    var rmsDb by mutableFloatStateOf(FLOOR_DB)
        private set
    var peakHoldDb by mutableFloatStateOf(FLOOR_DB)
        private set
    var crestFactorDb by mutableFloatStateOf(0f)
        private set

    private var peakHoldTimer = 0f

    fun step(dtSeconds: Float, rawRms: Float, rawPeak: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)

        val rmsTargetDb = amplitudeToDb(rawRms)
        val rmsAlpha = 1f - exp(-dt / RMS_TAU_SECONDS)
        rmsDb += (rmsTargetDb - rmsDb) * rmsAlpha

        val peakTargetDb = amplitudeToDb(rawPeak)
        val peakAlpha = if (peakTargetDb > peakDb) {
            1f - exp(-dt / PEAK_ATTACK_TAU_SECONDS)
        } else {
            1f - exp(-dt / PEAK_RELEASE_TAU_SECONDS)
        }
        peakDb += (peakTargetDb - peakDb) * peakAlpha

        if (peakDb >= peakHoldDb) {
            peakHoldDb = peakDb
            peakHoldTimer = 0f
        } else {
            peakHoldTimer += dt
            if (peakHoldTimer > PEAK_HOLD_SECONDS) {
                peakHoldDb = (peakHoldDb - dt * PEAK_HOLD_FALL_DB_PER_SECOND).coerceAtLeast(FLOOR_DB)
            }
        }

        crestFactorDb = (peakDb - rmsDb).coerceAtLeast(0f)
    }

    fun reset() {
        peakDb = FLOOR_DB
        rmsDb = FLOOR_DB
        peakHoldDb = FLOOR_DB
        crestFactorDb = 0f
        peakHoldTimer = 0f
    }

    companion object {
        const val FLOOR_DB = -60f
        const val CEILING_DB = 0f

        private const val AMPLITUDE_FLOOR = 0.001f
        private const val RMS_TAU_SECONDS = 0.3f
        private const val PEAK_ATTACK_TAU_SECONDS = 0.001f
        private const val PEAK_RELEASE_TAU_SECONDS = 0.6f
        private const val PEAK_HOLD_SECONDS = 1.5f
        private const val PEAK_HOLD_FALL_DB_PER_SECOND = 12f

        private fun amplitudeToDb(amplitude: Float): Float {
            val clamped = amplitude.coerceAtLeast(AMPLITUDE_FLOOR)
            return (20f * log10(clamped)).coerceIn(FLOOR_DB, CEILING_DB)
        }
    }
}
