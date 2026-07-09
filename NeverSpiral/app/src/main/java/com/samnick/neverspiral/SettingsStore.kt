package com.samnick.neverspiral

import android.content.Context

/**
 * Thin wrapper over a single SharedPreferences file so every visualizer's tunable settings
 * survive an app restart instead of resetting to defaults every launch.
 */
object SettingsStore {
    private const val PREFS_NAME = "visualizer_settings"

    fun getFloat(context: Context, key: String, default: Float): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(key, default)

    fun putFloat(context: Context, key: String, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(key, value).apply()
    }

    fun getInt(context: Context, key: String, default: Int): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(key, default)

    fun putInt(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
    }
}
