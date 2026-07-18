package com.samnick.neverspiral

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the FFT magnitude normalization bug: raw FFT magnitude scales with
 * [SpectrumAnalyzer.FFT_SIZE], so without dividing it back out before the dB conversion, every
 * band read pinned near the ceiling regardless of what was actually playing -- a moderately loud
 * tone looked identical to a full-scale one. These tests fail if that normalization regresses.
 */
class SpectrumAnalyzerTest {

    private fun sineBuffer(frequencyHz: Float, amplitudeFraction: Float, sampleCount: Int, sampleRate: Float = 44100f): ShortArray =
        ShortArray(sampleCount) { i ->
            val t = i / sampleRate
            (sin(2.0 * PI * frequencyHz * t) * amplitudeFraction * Short.MAX_VALUE).toInt().toShort()
        }

    @Test
    fun `silence reads every band at zero`() {
        val bands = SpectrumAnalyzer.computeBands(ShortArray(SpectrumAnalyzer.FFT_SIZE))
        assertTrue(bands.all { it == 0f })
    }

    @Test
    fun `a quiet tone reads measurably lower than a loud one`() {
        val quiet = SpectrumAnalyzer.computeBands(sineBuffer(1000f, 0.05f, SpectrumAnalyzer.FFT_SIZE))
        val loud = SpectrumAnalyzer.computeBands(sineBuffer(1000f, 0.9f, SpectrumAnalyzer.FFT_SIZE))
        assertTrue("quiet=${quiet.max()} loud=${loud.max()}", quiet.max() < loud.max())
    }

    @Test
    fun `a moderate tone does not saturate every band at the ceiling`() {
        // -26 dBFS is a perfectly normal, unremarkable level -- it must not read as maxed out.
        val bands = SpectrumAnalyzer.computeBands(sineBuffer(1000f, 0.05f, SpectrumAnalyzer.FFT_SIZE))
        assertTrue("expected headroom below the ceiling, got ${bands.max()}", bands.max() < 0.9f)
    }

    @Test
    fun `the loudest band roughly lines up with the tone's frequency`() {
        val bands = SpectrumAnalyzer.computeBands(sineBuffer(1000f, 0.8f, SpectrumAnalyzer.FFT_SIZE))
        val peakIndex = bands.indices.maxByOrNull { bands[it] }!!

        val logMin = ln(SpectrumAnalyzer.MIN_FREQ_HZ)
        val logMax = ln(SpectrumAnalyzer.MAX_FREQ_HZ)
        val bandStartHz = exp(logMin + (logMax - logMin) * peakIndex / SpectrumAnalyzer.BAND_COUNT)
        val bandEndHz = exp(logMin + (logMax - logMin) * (peakIndex + 1) / SpectrumAnalyzer.BAND_COUNT)

        // Loose bracket (half an octave either side) to allow for spectral leakage from the
        // Hann window and the log-band quantization, without the test being a tautology.
        assertTrue(
            "peak band [$bandStartHz, $bandEndHz) doesn't bracket 1000Hz",
            1000f in (bandStartHz * 0.5f)..(bandEndHz * 2f),
        )
    }

    @Test
    fun `xFractionForFrequency spans 0 to 1 across the band range and clamps outside it`() {
        assertEquals(0f, SpectrumAnalyzer.xFractionForFrequency(SpectrumAnalyzer.MIN_FREQ_HZ), 0.001f)
        assertEquals(1f, SpectrumAnalyzer.xFractionForFrequency(SpectrumAnalyzer.MAX_FREQ_HZ), 0.001f)
        // Below MIN_FREQ_HZ/above MAX_FREQ_HZ must clamp, not extrapolate past the visible axis.
        assertEquals(0f, SpectrumAnalyzer.xFractionForFrequency(1f), 0.001f)
        assertEquals(1f, SpectrumAnalyzer.xFractionForFrequency(100_000f), 0.001f)

        val low = SpectrumAnalyzer.xFractionForFrequency(1000f)
        val high = SpectrumAnalyzer.xFractionForFrequency(4000f)
        assertTrue("expected 0 < low < high < 1, got low=$low high=$high", low in 0f..1f && high in 0f..1f && low < high)
    }
}
