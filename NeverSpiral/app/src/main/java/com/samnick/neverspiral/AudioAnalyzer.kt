package com.samnick.neverspiral

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Latest instantaneous RMS amplitude (for the VU meter) and log-spaced frequency-band levels
 * (for the spectrum view) from captured audio.
 */
data class AudioSnapshot(
    val raw: Float = 0f,
    val bands: FloatArray = FloatArray(SpectrumAnalyzer.BAND_COUNT),
)

/**
 * Process-wide sink for raw audio levels. [AudioCaptureService] publishes a frame here every
 * time it reads a buffer of captured playback. The VU meter and spectrum view both read
 * [snapshots] directly to drive their own smoothing, independent of whichever component started
 * the capture -- all dB conversion, FFT, and ballistic smoothing happen downstream, not here.
 */
object AudioAnalyzer {
    private val _snapshots = MutableStateFlow(AudioSnapshot())
    val snapshots: StateFlow<AudioSnapshot> = _snapshots

    /** [bands] is optional: pass null when a buffer was too short to run the FFT this time. */
    fun publish(raw: Float, bands: FloatArray? = null) {
        val current = _snapshots.value
        _snapshots.value = AudioSnapshot(raw = raw, bands = bands ?: current.bands)
    }

    fun reset() {
        _snapshots.value = AudioSnapshot()
    }
}
