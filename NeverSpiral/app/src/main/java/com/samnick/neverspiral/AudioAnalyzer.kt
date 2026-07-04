package com.samnick.neverspiral

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Latest instantaneous RMS amplitude (for the VU meter), log-spaced frequency-band levels (for
 * the spectrum view), and a decimated time-domain waveform window (for the oscilloscope) from
 * captured audio.
 */
data class AudioSnapshot(
    val raw: Float = 0f,
    val bands: FloatArray = FloatArray(SpectrumAnalyzer.BAND_COUNT),
    val waveform: FloatArray = FloatArray(OscilloscopeEngine.POINT_COUNT),
)

/**
 * Process-wide sink for raw audio levels. [AudioCaptureService] publishes a frame here every
 * time it reads a buffer of captured playback. The VU meter, spectrum, and oscilloscope views
 * all read [snapshots] directly to drive their own smoothing, independent of whichever component
 * started the capture -- all dB conversion, FFT, and ballistic smoothing happen downstream, not
 * here.
 */
object AudioAnalyzer {
    private val _snapshots = MutableStateFlow(AudioSnapshot())
    val snapshots: StateFlow<AudioSnapshot> = _snapshots

    /** [bands] and [waveform] are optional: pass null to keep the previous value. */
    fun publish(raw: Float, bands: FloatArray? = null, waveform: FloatArray? = null) {
        val current = _snapshots.value
        _snapshots.value = AudioSnapshot(
            raw = raw,
            bands = bands ?: current.bands,
            waveform = waveform ?: current.waveform,
        )
    }

    fun reset() {
        _snapshots.value = AudioSnapshot()
    }
}
