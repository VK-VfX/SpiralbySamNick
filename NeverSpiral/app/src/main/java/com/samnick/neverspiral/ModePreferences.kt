package com.samnick.neverspiral

import android.content.Context

/**
 * All available visualizer modes, in their fixed declaration order -- the default order and the
 * canonical set that a user's customized order/visibility in [ModePreferences] is derived from.
 */
enum class VisualMode(val label: String) {
    VU_METER("VU Meter"),
    SPECTRUM("Spectrum"),
    GONIOMETER("Goniometer"),
    LOUDNESS("Loudness"),
    GRAPHIC_EQ("Graphic EQ"),
    PEAK_RMS("Peak / RMS"),
    TONAL_BALANCE("Tonal Balance"),
    RAINBOW_SPECTRUM("Rainbow Spectrum"),
    NEON_CYAN_PULSE("Neon Cyan Pulse"),
}

private const val KEY_MODE_ORDER = "mode_order"
private const val KEY_MODE_HIDDEN = "mode_hidden"

/**
 * Reads/writes the user's customized mode order and hidden set, so the mode picker, tap/swipe
 * cycling, and the app settings "Modes" section all agree on which modes are shown and in what
 * order. Falls back to every mode in its declaration order when nothing's been customized yet,
 * and any mode missing from a previously-saved order (e.g. a new mode added in an app update)
 * gets appended at the end so it still shows up rather than silently disappearing.
 */
object ModePreferences {
    fun loadOrder(context: Context): List<VisualMode> {
        val stored = SettingsStore.getString(context, KEY_MODE_ORDER, null)
        val storedNames = stored?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val ordered = storedNames.mapNotNull { name -> VisualMode.entries.find { it.name == name } }
        val missing = VisualMode.entries.filter { it !in ordered }
        return ordered + missing
    }

    fun saveOrder(context: Context, order: List<VisualMode>) {
        SettingsStore.putString(context, KEY_MODE_ORDER, order.joinToString(",") { it.name })
    }

    fun loadHidden(context: Context): Set<VisualMode> {
        val stored = SettingsStore.getString(context, KEY_MODE_HIDDEN, "") ?: ""
        return stored.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { name -> VisualMode.entries.find { it.name == name } }
            .toSet()
    }

    fun saveHidden(context: Context, hidden: Set<VisualMode>) {
        SettingsStore.putString(context, KEY_MODE_HIDDEN, hidden.joinToString(",") { it.name })
    }

    /** The saved order with hidden modes filtered out -- never empty; if every mode were somehow
     * hidden this falls back to showing all of them rather than leaving nothing to display. */
    fun loadVisible(context: Context): List<VisualMode> {
        val order = loadOrder(context)
        val hidden = loadHidden(context)
        val visible = order.filter { it !in hidden }
        return visible.ifEmpty { order }
    }
}

/** The mode that follows [this] within [modes], wrapping around; [this] itself if [modes] is empty. */
fun VisualMode.nextIn(modes: List<VisualMode>): VisualMode {
    if (modes.isEmpty()) return this
    val index = modes.indexOf(this)
    return if (index == -1) modes.first() else modes[(index + 1) % modes.size]
}

/** The mode that precedes [this] within [modes], wrapping around; [this] itself if [modes] is empty. */
fun VisualMode.previousIn(modes: List<VisualMode>): VisualMode {
    if (modes.isEmpty()) return this
    val index = modes.indexOf(this)
    return if (index == -1) modes.first() else modes[(index - 1 + modes.size) % modes.size]
}
