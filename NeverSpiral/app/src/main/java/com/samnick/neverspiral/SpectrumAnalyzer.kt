package com.samnick.neverspiral

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a raw PCM buffer into a small set of log-spaced frequency-band levels for the spectrum
 * view, using a plain iterative radix-2 FFT computed on the same audio already captured for the
 * VU meter -- no extra permission or capture path needed.
 */
object SpectrumAnalyzer {
    const val FFT_SIZE = 1024
    const val BAND_COUNT = 28

    private const val SAMPLE_RATE = 44100f
    const val MIN_FREQ_HZ = 40f
    const val MAX_FREQ_HZ = 16000f
    const val FLOOR_DB = -60f

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }

    /** Where along the log-spaced band axis [hz] falls (0 = [MIN_FREQ_HZ], 1 = [MAX_FREQ_HZ]) --
     * shared by every mode that draws frequency labels along a band axis (currently just
     * Spectrum), so the mapping can't drift out of sync between them. */
    fun xFractionForFrequency(hz: Float): Float {
        val logMin = ln(MIN_FREQ_HZ)
        val logMax = ln(MAX_FREQ_HZ)
        return ((ln(hz) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
    }

    /** [buffer] must hold at least [FFT_SIZE] samples starting at index 0. */
    fun computeBands(buffer: ShortArray): FloatArray {
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            real[i] = (buffer[i].toFloat() / Short.MAX_VALUE) * window[i]
        }
        fft(real, imag)

        val magnitudes = FloatArray(FFT_SIZE / 2)
        for (k in magnitudes.indices) {
            magnitudes[k] = sqrt(real[k] * real[k] + imag[k] * imag[k])
        }

        val binHz = SAMPLE_RATE / FFT_SIZE
        val logMin = ln(MIN_FREQ_HZ)
        val logMax = ln(MAX_FREQ_HZ)
        val bands = FloatArray(BAND_COUNT)
        for (b in 0 until BAND_COUNT) {
            val f0 = exp(logMin + (logMax - logMin) * b / BAND_COUNT)
            val f1 = exp(logMin + (logMax - logMin) * (b + 1) / BAND_COUNT)
            val bin0 = (f0 / binHz).toInt().coerceIn(1, magnitudes.size - 1)
            val bin1 = (f1 / binHz).toInt().coerceIn(bin0, magnitudes.size - 1)
            var sum = 0f
            for (k in bin0..bin1) sum += magnitudes[k]
            // Raw FFT magnitude scales with FFT_SIZE (a full-scale single-bin tone peaks near
            // FFT_SIZE/2), so without this normalization it reads many times louder than the
            // original -1..1 signal -- every band would land above the 0dB ceiling below and get
            // clamped to 1.0 regardless of what's actually playing.
            val avg = (sum / (bin1 - bin0 + 1)) / (FFT_SIZE / 2f)
            val db = if (avg > 0f) 20f * log10(avg.toDouble()).toFloat() else FLOOR_DB
            bands[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
        return bands
    }

    /** Iterative in-place radix-2 Cooley-Tukey FFT; [real].size must be a power of two. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        // Iterative butterfly stages.
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until len / 2) {
                    val evenR = real[i + k]
                    val evenI = imag[i + k]
                    val oddR = real[i + k + len / 2]
                    val oddI = imag[i + k + len / 2]
                    val twiddleR = oddR * curWr - oddI * curWi
                    val twiddleI = oddR * curWi + oddI * curWr
                    real[i + k] = evenR + twiddleR
                    imag[i + k] = evenI + twiddleI
                    real[i + k + len / 2] = evenR - twiddleR
                    imag[i + k + len / 2] = evenI - twiddleI
                    val nextWr = curWr * wr - curWi * wi
                    val nextWi = curWr * wi + curWi * wr
                    curWr = nextWr
                    curWi = nextWi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
