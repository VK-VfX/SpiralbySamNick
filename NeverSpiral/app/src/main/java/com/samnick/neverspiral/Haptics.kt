package com.samnick.neverspiral

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/** Thin wrapper so tap/pulse feedback degrades gracefully across API levels. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Short buzz on tap; strength grows with [energy] so excitement feels physical. */
    fun tapTick(energy: Float) {
        val amplitude = (60 + energy * 195).roundToInt().coerceIn(1, 255)
        val durationMs = (12 + energy * 18).toLong()
        fire(durationMs, amplitude)
    }

    /** A single stronger pulse for long-press "breathing" start. */
    fun pulseThud() {
        fire(35, 200)
    }

    private fun fire(durationMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }
}
