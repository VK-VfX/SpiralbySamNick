package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** User-tunable Goniometer trail, exposed through a gear-icon settings panel. */
class GoniometerSettings(initialTrailPersistence: Float = DEFAULT_TRAIL_PERSISTENCE) {
    var trailPersistence by mutableFloatStateOf(initialTrailPersistence)

    companion object {
        const val DEFAULT_TRAIL_PERSISTENCE = 0.78f
        const val TRAIL_PERSISTENCE_MIN = 0.4f
        const val TRAIL_PERSISTENCE_MAX = 0.96f
    }
}
