package com.samnick.neverspiral

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Latest instantaneous RMS amplitude (linear, roughly 0..1) per channel from captured audio. */
data class AudioSnapshot(
    val rawLeft: Float = 0f,
    val rawRight: Float = 0f,
)

/**
 * Process-wide sink for raw audio levels. [AudioCaptureService] publishes a frame here every
 * time it reads a buffer of captured playback. The VU meter reads [snapshots] directly to drive
 * its needle ballistics, independent of whichever component started the capture -- all dB
 * conversion and ballistic smoothing happens downstream in [VuMeterEngine], not here.
 */
object AudioAnalyzer {
    private val _snapshots = MutableStateFlow(AudioSnapshot())
    val snapshots: StateFlow<AudioSnapshot> = _snapshots

    fun publish(rawLeft: Float, rawRight: Float) {
        _snapshots.value = AudioSnapshot(rawLeft = rawLeft, rawRight = rawRight)
    }

    fun reset() {
        _snapshots.value = AudioSnapshot()
    }
}
