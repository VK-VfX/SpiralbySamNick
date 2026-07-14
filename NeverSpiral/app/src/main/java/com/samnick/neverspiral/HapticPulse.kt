package com.samnick.neverspiral

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A single short, sharp vibration for event-driven visual hits (currently just Bass Drop
 * Shockwave's onset trigger) -- deliberately not a per-frame or continuous buzz, which would
 * just drain the battery and feel like noise rather than punctuating a distinct "hit."
 */
object HapticPulse {
    private const val DURATION_MS = 25L

    /** Out of 255 -- firm enough to feel through a pocket, short of a jarring full-strength buzz. */
    private const val AMPLITUDE = 180

    fun fire(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(DURATION_MS, AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(DURATION_MS)
        }
    }
}
