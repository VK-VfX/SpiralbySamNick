package com.samnick.neverspiral

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One frame of analyzed audio: how loud it is right now, whether a beat just landed, and how "bright" (treble-heavy) it sounds. */
data class AudioSnapshot(
    val loudness: Float = 0f,
    val beatId: Long = 0L,
    val brightness: Float = 0f,
)

/**
 * Process-wide sink for audio analysis. [AudioCaptureService] publishes frames here as it reads
 * and analyzes captured playback; the Compose UI collects [snapshots] to drive the spiral,
 * independently of whichever component started the capture.
 */
object AudioAnalyzer {
    private val _snapshots = MutableStateFlow(AudioSnapshot())
    val snapshots: StateFlow<AudioSnapshot> = _snapshots

    private var beatCounter = 0L
    private var runningAverage = 0f
    private var lastBeatNanos = 0L
    private val beatRefractoryNanos = 150_000_000L

    /** [rms] is 0..1 root-mean-square volume for the buffer; [brightness] is a rough 0..1 treble measure. */
    fun publish(rms: Float, brightness: Float) {
        // Auto-leveling against a slow-moving average so both quiet and loud tracks read as
        // roughly the same visual intensity, instead of loudness being tied to absolute volume.
        runningAverage += (rms - runningAverage) * 0.02f
        val floor = 0.003f
        val loudness = (rms / (runningAverage.coerceAtLeast(floor) * 3f)).coerceIn(0f, 1f)

        val now = System.nanoTime()
        val isBeat = rms > runningAverage * 1.6f && rms > floor * 2f && (now - lastBeatNanos) > beatRefractoryNanos
        if (isBeat) {
            beatCounter++
            lastBeatNanos = now
        }

        _snapshots.value = AudioSnapshot(loudness = loudness, beatId = beatCounter, brightness = brightness.coerceIn(0f, 1f))
    }

    fun reset() {
        _snapshots.value = AudioSnapshot()
        runningAverage = 0f
    }
}
