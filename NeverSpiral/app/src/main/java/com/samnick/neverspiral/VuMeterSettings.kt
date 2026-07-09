package com.samnick.neverspiral

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** User-tunable VU meter calibration, exposed through a gear-icon settings panel. */
class VuMeterSettings(initialCalibrationOffsetDb: Float = DEFAULT_CALIBRATION_OFFSET_DB) {
    var calibrationOffsetDb by mutableFloatStateOf(initialCalibrationOffsetDb)

    companion object {
        /** Standard professional reference: 0 dBVU corresponds to -18 dBFS. */
        const val DEFAULT_CALIBRATION_OFFSET_DB = 18f
        const val CALIBRATION_MIN_DB = 12f
        const val CALIBRATION_MAX_DB = 24f
    }
}
