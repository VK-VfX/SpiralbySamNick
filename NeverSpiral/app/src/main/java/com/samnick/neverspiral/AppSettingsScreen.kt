package com.samnick.neverspiral

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class PlayerApp(val label: String, val packageName: String)

private val PLAYER_APPS = listOf(
    PlayerApp("Spotify", "com.spotify.music"),
    PlayerApp("YouTube Music", "com.google.android.apps.youtube.music"),
    PlayerApp("Tidal", "com.aspiro.tidal"),
)

/** Not private: [MainScreen] reads this directly to actually apply the flag to the window. */
internal const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"

/** Not private: [MainScreen] reads this directly to decide whether to hide system bars. */
internal const val KEY_IMMERSIVE_MODE = "immersive_mode"

private sealed interface UpdateCheckState {
    object Idle : UpdateCheckState
    object Checking : UpdateCheckState
    object UpToDateOrUnknown : UpdateCheckState
    data class UpToDate(val release: UpdateChecker.LatestRelease) : UpdateCheckState
    data class Available(val release: UpdateChecker.LatestRelease) : UpdateCheckState
}

/** Classifies a just-fetched [release] against the installed app -- null covers "no release
 * reachable at all", distinct from a release that's reachable but not actually newer. */
private fun classifyRelease(context: Context, release: UpdateChecker.LatestRelease?): UpdateCheckState = when {
    release == null -> UpdateCheckState.UpToDateOrUnknown
    UpdateChecker.isNewerThanInstalled(context, release) -> UpdateCheckState.Available(release)
    else -> UpdateCheckState.UpToDate(release)
}

/**
 * A full-screen, app-wide settings surface -- distinct from each visualizer mode's own gear-icon
 * panel, which only tunes that mode's look. Sections run Display, Haptics, Appearance, Modes,
 * Players, Updates, Diagnostics, About: settings that change how the app behaves or looks come
 * first, launcher shortcuts to other apps (not really a setting at all) come after, and update/
 * diagnostic/about administrivia comes last.
 */
@Composable
fun AppSettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Only the persisted value and the toggle's own UI live here -- actually applying it to the
    // window is MainScreen's job (see its own keepScreenOn state, refreshed via onDismiss below),
    // the same split already used for Immersive Mode. Applying it here too would only take effect
    // while this screen itself happened to be on screen, not for the rest of the session.
    var keepScreenOn by remember { mutableStateOf(SettingsStore.getBoolean(context, KEY_KEEP_SCREEN_ON, false)) }
    var immersiveMode by remember { mutableStateOf(SettingsStore.getBoolean(context, KEY_IMMERSIVE_MODE, true)) }
    var burstHaptics by remember { mutableStateOf(SettingsStore.getBoolean(context, KEY_BURST_HAPTICS, true)) }
    var autoCheckUpdates by remember { mutableStateOf(SettingsStore.getBoolean(context, KEY_AUTO_CHECK_UPDATES, false)) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    LaunchedEffect(Unit) {
        if (autoCheckUpdates) {
            updateState = UpdateCheckState.Checking
            updateState = classifyRelease(context, UpdateChecker.checkLatest())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VisualizerTheme.BACKGROUND)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SETTINGS",
                    color = VisualizerTheme.TEXT_PRIMARY,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(VisualizerTheme.PANEL_RAISED)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = VisualizerTheme.ACCENT, fontSize = 16.sp)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSectionTitle("Display")
                SettingsToggleRow(
                    label = "Keep Screen On",
                    description = "Prevents the display from sleeping while the app is open. Android " +
                        "doesn't let apps change the system screen-timeout duration directly, so this " +
                        "is the available always-on option.",
                    checked = keepScreenOn,
                ) {
                    keepScreenOn = it
                    SettingsStore.putBoolean(context, KEY_KEEP_SCREEN_ON, it)
                }
                SettingsToggleRow(
                    label = "Immersive Mode",
                    description = "Hides the status and navigation bars while the visualizer is " +
                        "running, for a true edge-to-edge full-screen view. Swipe from an edge to " +
                        "bring them back temporarily.",
                    checked = immersiveMode,
                ) {
                    immersiveMode = it
                    SettingsStore.putBoolean(context, KEY_IMMERSIVE_MODE, it)
                }

                // A separate section rather than folding this into Display -- it's not a display
                // behavior, and a dedicated "Haptics" section is also where any future vibration
                // setting belongs, instead of every unrelated toggle accumulating under Display.
                Spacer(modifier = Modifier.height(20.dp))
                SettingsSectionTitle("Haptics")
                SettingsToggleRow(
                    label = "Bass Drop Vibration",
                    description = "A short pulse each time Radial Spectrum Burst detects a bass drop.",
                    checked = burstHaptics,
                ) {
                    burstHaptics = it
                    SettingsStore.putBoolean(context, KEY_BURST_HAPTICS, it)
                }

                Spacer(modifier = Modifier.height(20.dp))
                SettingsSectionTitle("Appearance")
                AppearanceSection(context)

                Spacer(modifier = Modifier.height(20.dp))
                SettingsSectionTitle("Modes")
                Text(
                    text = "Hide modes you don't use, or reorder them -- the mode strip below the " +
                        "visualizer and swipe cycling both follow this order. At least one mode " +
                        "has to stay visible.",
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                ModeCustomizationSection(context)

                // Players moved below the settings that actually change app behavior/look --
                // these are just launcher shortcuts to other apps, not a Sam's Music Viz setting,
                // so they read better as a lower-priority convenience section than the first
                // thing in the list.
                Spacer(modifier = Modifier.height(20.dp))
                SettingsSectionTitle("Players")
                Text(
                    text = "Sam's Music Viz listens to whatever's playing system-wide, so it already " +
                        "works with any of these -- no account or setup needed. These just jump " +
                        "straight to the app.",
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                for (player in PLAYER_APPS) {
                    PlayerRow(player, context)
                }

                Spacer(modifier = Modifier.height(20.dp))
                SettingsSectionTitle("Updates")
                SettingsToggleRow(
                    label = "Check Automatically",
                    description = "Silently checks the GitHub repo's releases for a newer build each " +
                        "time Settings opens.",
                    checked = autoCheckUpdates,
                ) {
                    autoCheckUpdates = it
                    SettingsStore.putBoolean(context, KEY_AUTO_CHECK_UPDATES, it)
                }
                Spacer(modifier = Modifier.height(8.dp))
                UpdateSection(
                    state = updateState,
                    onCheckNow = {
                        scope.launch {
                            updateState = UpdateCheckState.Checking
                            updateState = classifyRelease(context, UpdateChecker.checkLatest())
                        }
                    },
                    onInstall = { release -> UpdateChecker.openReleasePage(context, release) },
                )

                Spacer(modifier = Modifier.height(20.dp))
                SettingsSectionTitle("Diagnostics")
                DiagnosticsSection(context)

                Spacer(modifier = Modifier.height(20.dp))
                SettingsSectionTitle("About")
                Text(
                    text = "Sam's Music Viz",
                    color = VisualizerTheme.TEXT_PRIMARY,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Version $versionName",
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text = "vibe coded with love by Samuel Nicholas Salvador/Veera Krishnan.",
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerRow(player: PlayerApp, context: Context) {
    val installed = remember(player.packageName) {
        context.packageManager.getLaunchIntentForPackage(player.packageName) != null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(VisualizerTheme.PANEL_RAISED)
            .clickable {
                if (installed) {
                    context.packageManager.getLaunchIntentForPackage(player.packageName)?.let {
                        context.startActivity(it)
                    }
                } else {
                    openPlayStoreListing(context, player.packageName)
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = player.label,
            color = VisualizerTheme.TEXT_PRIMARY,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = if (installed) "OPEN" else "GET",
            color = if (installed) VisualizerTheme.ACCENT else VisualizerTheme.TEXT_SECONDARY,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Lets the user hide modes and reorder the rest via up/down arrows -- a persisted list is
 * simpler and less error-prone on a touchscreen than drag-to-reorder, and there are only nine
 * modes to page through. Reads/writes through [ModePreferences] on every change so [MainScreen]
 * picks up the new order/visibility the next time this screen is dismissed.
 */
@Composable
private fun ModeCustomizationSection(context: Context) {
    var order by remember { mutableStateOf(ModePreferences.loadOrder(context)) }
    var hidden by remember { mutableStateOf(ModePreferences.loadHidden(context)) }

    for ((index, m) in order.withIndex()) {
        ModeRow(
            mode = m,
            enabled = m !in hidden,
            canMoveUp = index > 0,
            canMoveDown = index < order.lastIndex,
            onToggle = { checked ->
                val visibleCount = order.size - hidden.size
                if (!checked && visibleCount <= 1) return@ModeRow
                hidden = if (checked) hidden - m else hidden + m
                ModePreferences.saveHidden(context, hidden)
            },
            onMoveUp = {
                if (index > 0) {
                    order = order.toMutableList().apply {
                        val moved = removeAt(index)
                        add(index - 1, moved)
                    }
                    ModePreferences.saveOrder(context, order)
                }
            },
            onMoveDown = {
                if (index < order.lastIndex) {
                    order = order.toMutableList().apply {
                        val moved = removeAt(index)
                        add(index + 1, moved)
                    }
                    ModePreferences.saveOrder(context, order)
                }
            },
        )
    }
}

@Composable
private fun ModeRow(
    mode: VisualMode,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(VisualizerTheme.PANEL_RAISED)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mode.label,
            color = if (enabled) VisualizerTheme.TEXT_PRIMARY else VisualizerTheme.TEXT_SECONDARY,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        ReorderArrow("▲", canMoveUp, onMoveUp)
        Spacer(modifier = Modifier.width(4.dp))
        ReorderArrow("▼", canMoveDown, onMoveDown)
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VisualizerTheme.PANEL,
                checkedTrackColor = VisualizerTheme.ACCENT,
                uncheckedThumbColor = VisualizerTheme.TEXT_SECONDARY,
                uncheckedTrackColor = VisualizerTheme.PANEL_RAISED,
            ),
        )
    }
}

@Composable
private fun ReorderArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(VisualizerTheme.PANEL)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) VisualizerTheme.ACCENT else VisualizerTheme.HAIRLINE,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * A custom accent color picker, expressed as hue/saturation/brightness sliders rather than RGB so
 * three simple sliders cover the whole color range. Writes straight into [VisualizerTheme.ACCENT]
 * on every change for a live preview, since that's the same value the rest of the app reads.
 */
@Composable
private fun AppearanceSection(context: Context) {
    var useCustom by remember { mutableStateOf(AppearanceSettings.isCustomEnabled(context)) }
    var hue by remember { mutableFloatStateOf(AppearanceSettings.loadHue(context)) }
    var saturation by remember { mutableFloatStateOf(AppearanceSettings.loadSaturation(context)) }
    var brightness by remember { mutableFloatStateOf(AppearanceSettings.loadValue(context)) }

    Text(
        text = "Pick your own accent color -- used for highlights, needles, mode chips, and the " +
            "Cool/Frequency color schemes across every mode -- instead of the default teal-cyan.",
        color = VisualizerTheme.TEXT_SECONDARY,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    SettingsToggleRow(
        label = "Custom Accent Color",
        description = "Overrides the default accent everywhere it's used.",
        checked = useCustom,
    ) { checked ->
        useCustom = checked
        if (checked) {
            AppearanceSettings.saveCustomAccent(context, hue, saturation, brightness)
        } else {
            AppearanceSettings.resetToDefault(context)
        }
    }
    if (useCustom) {
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AppearanceSettings.colorFromHsv(hue, saturation, brightness)),
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingSliderRow("Hue", hue, 0f..360f) {
            hue = it
            AppearanceSettings.saveCustomAccent(context, hue, saturation, brightness)
        }
        SettingSliderRow("Saturation", saturation, 0.15f..1f) {
            saturation = it
            AppearanceSettings.saveCustomAccent(context, hue, saturation, brightness)
        }
        SettingSliderRow("Brightness", brightness, 0.45f..1f) {
            brightness = it
            AppearanceSettings.saveCustomAccent(context, hue, saturation, brightness)
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    var useCustomBackground by remember { mutableStateOf(AppearanceSettings.isBackgroundCustomEnabled(context)) }
    var bgHue by remember { mutableFloatStateOf(AppearanceSettings.loadBackgroundHue(context)) }
    var bgSaturation by remember { mutableFloatStateOf(AppearanceSettings.loadBackgroundSaturation(context)) }
    var bgBrightness by remember { mutableFloatStateOf(AppearanceSettings.loadBackgroundValue(context)) }

    Text(
        text = "Pick your own canvas background -- the backdrop every mode draws over, instead " +
            "of the default black. Some modes (Kaleidoscope Bloom especially) read nicer on a " +
            "deep, non-pure-black tone.",
        color = VisualizerTheme.TEXT_SECONDARY,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    SettingsToggleRow(
        label = "Custom Background Color",
        description = "Overrides the default black canvas backdrop in every mode.",
        checked = useCustomBackground,
    ) { checked ->
        useCustomBackground = checked
        if (checked) {
            AppearanceSettings.saveCustomBackground(context, bgHue, bgSaturation, bgBrightness)
        } else {
            AppearanceSettings.resetBackgroundToDefault(context)
        }
    }
    if (useCustomBackground) {
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AppearanceSettings.colorFromHsv(bgHue, bgSaturation, bgBrightness)),
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingSliderRow("Hue", bgHue, 0f..360f) {
            bgHue = it
            AppearanceSettings.saveCustomBackground(context, bgHue, bgSaturation, bgBrightness)
        }
        SettingSliderRow("Saturation", bgSaturation, 0f..1f) {
            bgSaturation = it
            AppearanceSettings.saveCustomBackground(context, bgHue, bgSaturation, bgBrightness)
        }
        SettingSliderRow("Brightness", bgBrightness, 0f..0.4f) {
            bgBrightness = it
            AppearanceSettings.saveCustomBackground(context, bgHue, bgSaturation, bgBrightness)
        }
    }
}

/**
 * Shows the most recent crash's stack trace, if any -- there's no crash-reporting backend, so
 * during solo on-device testing this local file (see [CrashLog]) is the only way to see what
 * actually broke after the app dies and relaunches.
 */
@Composable
private fun DiagnosticsSection(context: Context) {
    var crashLog by remember { mutableStateOf(CrashLog.read(context)) }
    if (crashLog == null) {
        Text(
            text = "No crashes recorded since the app was installed.",
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    } else {
        Text(
            text = "Most recent crash:",
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(VisualizerTheme.PANEL_RAISED)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                text = crashLog ?: "",
                color = VisualizerTheme.CRITICAL,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsActionButton("Clear") {
            CrashLog.clear(context)
            crashLog = null
        }
    }
}

private fun openPlayStoreListing(context: Context, packageName: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")),
        )
    } catch (e: ActivityNotFoundException) {
        // No browser available; nothing more we can do.
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        color = VisualizerTheme.ACCENT,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = VisualizerTheme.TEXT_PRIMARY, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(description, color = VisualizerTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VisualizerTheme.PANEL,
                checkedTrackColor = VisualizerTheme.ACCENT,
                uncheckedThumbColor = VisualizerTheme.TEXT_SECONDARY,
                uncheckedTrackColor = VisualizerTheme.PANEL_RAISED,
            ),
        )
    }
}

@Composable
private fun UpdateSection(
    state: UpdateCheckState,
    onCheckNow: () -> Unit,
    onInstall: (UpdateChecker.LatestRelease) -> Unit,
) {
    when (state) {
        is UpdateCheckState.Idle -> {
            SettingsActionButton("Check Now", onClick = onCheckNow)
        }
        is UpdateCheckState.Checking -> {
            Text("Checking…", color = VisualizerTheme.TEXT_SECONDARY, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        is UpdateCheckState.UpToDateOrUnknown -> {
            Text(
                text = "No release found (repo may be private, or unreachable).",
                color = VisualizerTheme.TEXT_SECONDARY,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsActionButton("Check Now", onClick = onCheckNow)
        }
        is UpdateCheckState.UpToDate -> {
            Text(
                text = "You're on the latest version (${state.release.name}).",
                color = VisualizerTheme.TEXT_SECONDARY,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsActionButton("Recheck", onClick = onCheckNow)
        }
        is UpdateCheckState.Available -> {
            Text(
                text = "Latest on GitHub: ${state.release.name}",
                color = VisualizerTheme.ACCENT,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row {
                SettingsActionButton("Get Update", onClick = { onInstall(state.release) })
                Spacer(modifier = Modifier.width(8.dp))
                SettingsActionButton("Recheck", onClick = onCheckNow)
            }
        }
    }
}

@Composable
private fun SettingsActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VisualizerTheme.PANEL_RAISED)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = VisualizerTheme.ACCENT,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
