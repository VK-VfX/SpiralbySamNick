package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A simple two-pole IIR biquad (Direct Form I), configured via the standard RBJ Audio EQ Cookbook
 * formulas -- the same well-known coefficient derivations used throughout pro-audio DSP.
 */
private class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun setHighPass(sampleRateHz: Float, cutoffHz: Float, q: Float) {
        val w0 = 2f * PI.toFloat() * cutoffHz / sampleRateHz
        val alpha = sin(w0) / (2f * q)
        val cosw0 = cos(w0)
        val a0 = 1f + alpha
        b0 = ((1f + cosw0) / 2f) / a0
        b1 = (-(1f + cosw0)) / a0
        b2 = ((1f + cosw0) / 2f) / a0
        a1 = (-2f * cosw0) / a0
        a2 = (1f - alpha) / a0
    }

    /** A high-shelf boost, standing in for BS.1770's "head effect" pre-filter. */
    fun setHighShelf(sampleRateHz: Float, cornerHz: Float, gainDb: Float, q: Float) {
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w0 = 2f * PI.toFloat() * cornerHz / sampleRateHz
        val alpha = sin(w0) / (2f * q)
        val cosw0 = cos(w0)
        val sqrtA = sqrt(a)

        val a0 = (a + 1f) - (a - 1f) * cosw0 + 2f * sqrtA * alpha
        b0 = (a * ((a + 1f) + (a - 1f) * cosw0 + 2f * sqrtA * alpha)) / a0
        b1 = (-2f * a * ((a - 1f) + (a + 1f) * cosw0)) / a0
        b2 = (a * ((a + 1f) + (a - 1f) * cosw0 - 2f * sqrtA * alpha)) / a0
        a1 = (2f * ((a - 1f) - (a + 1f) * cosw0)) / a0
        a2 = (((a + 1f) - (a - 1f) * cosw0 - 2f * sqrtA * alpha)) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}

/**
 * A loudness meter along the lines of what mastering engineers and streaming platforms (Spotify,
 * YouTube) actually normalize to, rather than a VU meter's vintage analog ballistics: momentary,
 * short-term, and (gated) integrated loudness, plus loudness range (LRA).
 *
 * This is a practical, real-time approximation of ITU-R BS.1770 / EBU R128, not a certified
 * meter: the K-weighting pre-filter is implemented as a proper high-pass + high-shelf biquad pair
 * (correct RBJ cookbook DSP, tuned to the same intent as BS.1770's filters -- attenuate deep bass
 * that doesn't correlate with perceived loudness, boost the presence range that does), and the
 * gating uses a single-pass absolute-threshold approximation rather than BS.1770's two-pass
 * absolute+relative block gating, since this runs continuously on a live stream rather than
 * analyzing a fixed file.
 */
class LoudnessEngine {
    var momentaryLufs by mutableFloatStateOf(SILENCE_LUFS)
        private set
    var shortTermLufs by mutableFloatStateOf(SILENCE_LUFS)
        private set
    var integratedLufs by mutableFloatStateOf(SILENCE_LUFS)
        private set
    var loudnessRange by mutableFloatStateOf(0f)
        private set
    var elapsed by mutableFloatStateOf(0f)
        private set

    /** Ring buffer of recent short-term readings (one per [HISTORY_PUSH_INTERVAL_SECONDS]), for both [loudnessRange] and a scrolling trend line on screen. */
    val history = FloatArray(HISTORY_SIZE) { SILENCE_LUFS }

    private val highPass = Biquad().apply { setHighPass(SAMPLE_RATE, 60f, 0.5f) }
    private val highShelf = Biquad().apply { setHighShelf(SAMPLE_RATE, 1500f, 4f, 0.707f) }

    private var momentaryMeanSquare = 0f
    private var shortTermMeanSquare = 0f
    private var integratedSumMeanSquare = 0.0
    private var integratedBlockCount = 0L
    private var historyWriteIndex = 0
    private var historyFilled = 0
    private var timeSinceHistoryPush = 0f

    /** Folds newly captured raw mono PCM (linear, -1..1) through K-weighting into the running power estimates. */
    fun ingest(samples: FloatArray) {
        val dt = 1f / SAMPLE_RATE
        val momentaryAlpha = 1f - exp(-dt / MOMENTARY_TAU_SECONDS)
        val shortTermAlpha = 1f - exp(-dt / SHORT_TERM_TAU_SECONDS)
        for (s in samples) {
            val weighted = highShelf.process(highPass.process(s))
            val sq = weighted * weighted
            momentaryMeanSquare += (sq - momentaryMeanSquare) * momentaryAlpha
            shortTermMeanSquare += (sq - shortTermMeanSquare) * shortTermAlpha
        }
    }

    /** Advances derived readouts and periodic bookkeeping; call once per frame. */
    fun step(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        elapsed += dt

        momentaryLufs = lufsFromMeanSquare(momentaryMeanSquare)
        shortTermLufs = lufsFromMeanSquare(shortTermMeanSquare)

        if (momentaryLufs > ABSOLUTE_GATE_LUFS) {
            integratedSumMeanSquare += momentaryMeanSquare.toDouble()
            integratedBlockCount++
        }
        integratedLufs = if (integratedBlockCount > 0) {
            lufsFromMeanSquare((integratedSumMeanSquare / integratedBlockCount).toFloat())
        } else {
            SILENCE_LUFS
        }

        timeSinceHistoryPush += dt
        if (timeSinceHistoryPush >= HISTORY_PUSH_INTERVAL_SECONDS) {
            timeSinceHistoryPush = 0f
            history[historyWriteIndex % HISTORY_SIZE] = shortTermLufs
            historyWriteIndex++
            historyFilled = (historyFilled + 1).coerceAtMost(HISTORY_SIZE)
            loudnessRange = computeLoudnessRange()
        }
    }

    fun reset() {
        highPass.reset()
        highShelf.reset()
        momentaryMeanSquare = 0f
        shortTermMeanSquare = 0f
        integratedSumMeanSquare = 0.0
        integratedBlockCount = 0L
        historyWriteIndex = 0
        historyFilled = 0
        timeSinceHistoryPush = 0f
        history.fill(SILENCE_LUFS)
        momentaryLufs = SILENCE_LUFS
        shortTermLufs = SILENCE_LUFS
        integratedLufs = SILENCE_LUFS
        loudnessRange = 0f
    }

    private fun computeLoudnessRange(): Float {
        if (historyFilled < 2) return 0f
        val gated = history.copyOfRange(0, historyFilled).filter { it > ABSOLUTE_GATE_LUFS }.sorted()
        if (gated.size < 2) return 0f
        val lowIndex = (gated.size * 0.10f).toInt().coerceIn(0, gated.size - 1)
        val highIndex = (gated.size * 0.95f).toInt().coerceIn(0, gated.size - 1)
        return (gated[highIndex] - gated[lowIndex]).coerceAtLeast(0f)
    }

    companion object {
        const val SILENCE_LUFS = -60f
        const val DISPLAY_FLOOR_LUFS = -40f
        const val DISPLAY_CEILING_LUFS = 0f

        private const val SAMPLE_RATE = 44100f
        private const val MOMENTARY_TAU_SECONDS = 0.4f
        private const val SHORT_TERM_TAU_SECONDS = 3f
        private const val ABSOLUTE_GATE_LUFS = -70f
        private const val MEAN_SQUARE_FLOOR = 1e-7f
        private const val HISTORY_PUSH_INTERVAL_SECONDS = 1f
        const val HISTORY_SIZE = 90

        private fun lufsFromMeanSquare(meanSquare: Float): Float {
            val clamped = meanSquare.coerceAtLeast(MEAN_SQUARE_FLOOR)
            return (-0.691f + 10f * log10(clamped)).coerceAtLeast(SILENCE_LUFS)
        }
    }
}
