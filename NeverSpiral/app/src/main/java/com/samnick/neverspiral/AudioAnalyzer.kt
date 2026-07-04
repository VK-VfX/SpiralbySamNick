package com.samnick.neverspiral

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Latest instantaneous RMS amplitude (linear, roughly 0..1) from captured audio. */
data class AudioSnapshot(
    val raw: Float = 0f,
)

/**
 * Process-wide sink for raw audio level. [AudioCaptureService] publishes a frame here every
 * time it reads a buffer of captured playback. The VU meter reads [snapshots] directly to drive
 * its needle ballistics, independent of whichever component started the capture -- all dB
 * conversion and ballistic smoothing happens downstream in [VuMeterEngine], not here.
 */
object AudioAnalyzer {
    private val _snapshots = MutableStateFlow(AudioSnapshot())
    val snapshots: StateFlow<AudioSnapshot> = _snapshots

    fun publish(raw: Float) {
        _snapshots.value = AudioSnapshot(raw = raw)
    }

    fun reset() {
        _snapshots.value = AudioSnapshot()
    }
}
