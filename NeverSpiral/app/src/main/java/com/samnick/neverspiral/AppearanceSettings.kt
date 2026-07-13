package com.samnick.neverspiral

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

private const val KEY_USE_CUSTOM_ACCENT = "use_custom_accent"
private const val KEY_ACCENT_HUE = "custom_accent_hue"
private const val KEY_ACCENT_SATURATION = "custom_accent_saturation"
private const val KEY_ACCENT_VALUE = "custom_accent_value"

/**
 * Persists and applies a custom accent color, expressed as hue/saturation/brightness rather than
 * RGB so a simple three-slider picker can cover the full range without needing a real color wheel.
 * Reads/writes through [SettingsStore] and pushes the result straight into [VisualizerTheme.ACCENT],
 * which every mode already reads -- so one custom color cascades across the whole app.
 */
object AppearanceSettings {
    val DEFAULT_ACCENT = Color(0xFF5AC8E0)

    /** The default accent's own HSV components, computed once so "Reset" reproduces it exactly
     * rather than relying on separately-hand-picked constants that could drift out of sync. */
    private val defaultHsv: FloatArray by lazy {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(DEFAULT_ACCENT.toArgb(), hsv)
        hsv
    }

    fun isCustomEnabled(context: Context): Boolean = SettingsStore.getBoolean(context, KEY_USE_CUSTOM_ACCENT, false)

    fun loadHue(context: Context): Float = SettingsStore.getFloat(context, KEY_ACCENT_HUE, defaultHsv[0])
    fun loadSaturation(context: Context): Float = SettingsStore.getFloat(context, KEY_ACCENT_SATURATION, defaultHsv[1])
    fun loadValue(context: Context): Float = SettingsStore.getFloat(context, KEY_ACCENT_VALUE, defaultHsv[2])

    fun colorFromHsv(hue: Float, saturation: Float, value: Float): Color =
        Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))

    /** Applies whatever's persisted (custom or default) to [VisualizerTheme.ACCENT] -- call once
     * at app startup so the custom color is live before the first frame, not just after visiting
     * Settings. */
    fun applyStoredAccent(context: Context) {
        VisualizerTheme.ACCENT = if (isCustomEnabled(context)) {
            colorFromHsv(loadHue(context), loadSaturation(context), loadValue(context))
        } else {
            DEFAULT_ACCENT
        }
    }

    fun saveCustomAccent(context: Context, hue: Float, saturation: Float, value: Float) {
        SettingsStore.putBoolean(context, KEY_USE_CUSTOM_ACCENT, true)
        SettingsStore.putFloat(context, KEY_ACCENT_HUE, hue)
        SettingsStore.putFloat(context, KEY_ACCENT_SATURATION, saturation)
        SettingsStore.putFloat(context, KEY_ACCENT_VALUE, value)
        VisualizerTheme.ACCENT = colorFromHsv(hue, saturation, value)
    }

    fun resetToDefault(context: Context) {
        SettingsStore.putBoolean(context, KEY_USE_CUSTOM_ACCENT, false)
        VisualizerTheme.ACCENT = DEFAULT_ACCENT
    }
}
