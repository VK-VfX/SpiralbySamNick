package com.samnick.neverspiral

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the visualizers need from one captured buffer: instantaneous mono RMS amplitude (VU
 * meter, loudness meter, peak/RMS meter), the instantaneous linear sample peak across both
 * channels (peak/RMS meter), log-spaced frequency-band levels (spectrum, graphic EQ, tonal
 * balance), the raw mono PCM chunk just captured normalized to -1..1 (oscilloscope, loudness
 * meter), and the raw left/right PCM chunks (goniometer). Each is a fresh chunk on every publish,
 * not a fixed-size display window -- consumers fold it into whatever history they keep.
 */
data class AudioSnapshot(
    val raw: Float = 0f,
    val peak: Float = 0f,
    val bands: FloatArray = FloatArray(SpectrumAnalyzer.BAND_COUNT),
    val waveform: FloatArray = FloatArray(0),
    val left: FloatArray = FloatArray(0),
    val right: FloatArray = FloatArray(0),
)

/**
 * Process-wide sink for raw audio levels. [AudioCaptureService] publishes a frame here every
 * time it reads a buffer of captured playback. Every visualizer view reads [snapshots] directly
 * to drive its own smoothing, independent of whichever component started the capture -- all dB
 * conversion, FFT, and ballistic smoothing happen downstream, not here.
 */
object AudioAnalyzer {
    private val _snapshots = MutableStateFlow(AudioSnapshot())
    val snapshots: StateFlow<AudioSnapshot> = _snapshots

    /** [bands] is optional: pass null to keep the previous value (it only updates once a full FFT window is available). */
    fun publish(
        raw: Float,
        peak: Float,
        waveform: FloatArray,
        left: FloatArray,
        right: FloatArray,
        bands: FloatArray? = null,
    ) {
        val current = _snapshots.value
        _snapshots.value = AudioSnapshot(
            raw = raw,
            peak = peak,
            bands = bands ?: current.bands,
            waveform = waveform,
            left = left,
            right = right,
        )
    }

    fun reset() {
        _snapshots.value = AudioSnapshot()
    }
}
