package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Integrated-loudness normalization targets real platforms actually master to. */
enum class LoudnessTarget(val label: String, val targetLufs: Float) {
    STREAMING("Streaming", -14f),
    APPLE_MUSIC("Apple", -16f),
    EBU_R128("EBU R128", -23f),
}

/** User-selected loudness target, exposed through a gear-icon settings panel. */
class LoudnessSettings(initialTarget: LoudnessTarget = LoudnessTarget.STREAMING) {
    var target by mutableStateOf(initialTarget)
}
