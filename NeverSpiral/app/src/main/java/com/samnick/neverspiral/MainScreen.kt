package com.samnick.neverspiral

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.isActive

/** Modes with their own gear-icon settings panel. */
private val MODES_WITH_SETTINGS = setOf(
    VisualMode.VU_METER,
    VisualMode.SPECTRUM,
    VisualMode.GRAPHIC_EQ,
    VisualMode.RAINBOW_SPECTRUM,
    VisualMode.NEON_CYAN_PULSE,
    VisualMode.AUDIO_FIREFLIES,
    VisualMode.RADAR_RIPPLES,
    VisualMode.WAVEFORM_RIBBON,
    VisualMode.FREQUENCY_TERRAIN,
)

private const val SWIPE_THRESHOLD_PX = 90f

/** Modes that render well wide -- everything else forces portrait, since a VU meter's arc or a
 * centered radial/particle composition either doesn't gain anything from landscape or (VU Meter)
 * actively looks worse stretched that wide. The two horizontally-scrolling modes (Waveform Ribbon,
 * Frequency Terrain) benefit the same way Spectrum's bars do -- more width is more visible history. */
private val MODES_ALLOWING_LANDSCAPE = setOf(
    VisualMode.SPECTRUM,
    VisualMode.RAINBOW_SPECTRUM,
    VisualMode.NEON_CYAN_PULSE,
    VisualMode.WAVEFORM_RIBBON,
    VisualMode.FREQUENCY_TERRAIN,
)

/**
 * Hosts up to nine visualizer modes (fewer if the user's hidden some via the "Modes" section in
 * [AppSettingsScreen], see [ModePreferences]) plus the single shared "Visualize music" capture
 * toggle. A persistent, horizontally-scrollable row of mode chips below the visualizer is the
 * primary way to switch -- tap the specific mode you want directly, rather than repeatedly tapping
 * the canvas to cycle through them one at a time. Swipe left/right on the canvas still works too,
 * for quick cycling without looking down at the row. A hamburger icon in the top-right opens the
 * app-wide [AppSettingsScreen] (player shortcuts, keep-screen-on, OTA updates, mode customization,
 * about) -- distinct from each mode's own gear-icon tuning panel. VU Meter and Spectrum are stepped
 * every frame regardless of which mode is showing, so switching between them and any other mode
 * still feels instant rather than starting from a frozen reading. Every mode past Graphic EQ
 * (Rainbow Spectrum, Neon Cyan Pulse, Audio Fireflies, Radar Ripples, Waveform Ribbon, Frequency
 * Terrain) is a pure rendering treatment of [SpectrumEngine]'s already fast-rise/slower-fall
 * smoothed bands, the same data [SpectrumScreen] and [GraphicEqScreen] draw, so none of them need a
 * dedicated engine of their own -- just their own [BarSpectrumSettings] for Scale, Stroke Weight,
 * and Height, same as every other mode's gear-icon panel.
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    remember(context) { AppearanceSettings.applyStoredAccent(context) }

    val vuMeter = remember { VuMeterEngine() }
    val vuMeterSettings = remember {
        VuMeterSettings(SettingsStore.getFloat(context, KEY_VU_CALIBRATION, VuMeterSettings.DEFAULT_CALIBRATION_OFFSET_DB))
    }
    val spectrum = remember { SpectrumEngine(SpectrumAnalyzer.BAND_COUNT) }
    val spectrumSettings = remember {
        val ordinal = SettingsStore.getInt(context, KEY_SPECTRUM_COLOR_SCHEME, SpectrumColorScheme.COOL.ordinal)
        SpectrumSettings(SpectrumColorScheme.entries.getOrElse(ordinal) { SpectrumColorScheme.COOL })
    }
    val graphicEqSettings = remember {
        val ordinal = SettingsStore.getInt(context, KEY_GRAPHIC_EQ_COLOR_SCHEME, GraphicEqColorScheme.CLASSIC.ordinal)
        GraphicEqSettings(GraphicEqColorScheme.entries.getOrElse(ordinal) { GraphicEqColorScheme.CLASSIC })
    }
    val rainbowSpectrumSettings = remember {
        BarSpectrumSettings(
            initialScale = SettingsStore.getFloat(context, KEY_RAINBOW_SCALE, 1f),
            initialStrokeWeight = SettingsStore.getFloat(context, KEY_RAINBOW_STROKE_WEIGHT, 1f),
            initialHeight = SettingsStore.getFloat(context, KEY_RAINBOW_HEIGHT, 0.46f),
        )
    }
    val neonCyanPulseSettings = remember {
        BarSpectrumSettings(
            initialScale = SettingsStore.getFloat(context, KEY_NEON_SCALE, 1f),
            initialStrokeWeight = SettingsStore.getFloat(context, KEY_NEON_STROKE_WEIGHT, 1f),
            initialHeight = SettingsStore.getFloat(context, KEY_NEON_HEIGHT, 0.46f),
        )
    }
    val audioFirefliesSettings = remember {
        BarSpectrumSettings(
            initialScale = SettingsStore.getFloat(context, KEY_FIREFLIES_SCALE, 1f),
            initialStrokeWeight = SettingsStore.getFloat(context, KEY_FIREFLIES_STROKE_WEIGHT, 1f),
            initialHeight = SettingsStore.getFloat(context, KEY_FIREFLIES_HEIGHT, 0.46f),
        )
    }
    val radarRipplesSettings = remember {
        BarSpectrumSettings(
            initialScale = SettingsStore.getFloat(context, KEY_RIPPLES_SCALE, 1f),
            initialStrokeWeight = SettingsStore.getFloat(context, KEY_RIPPLES_STROKE_WEIGHT, 1f),
            initialHeight = SettingsStore.getFloat(context, KEY_RIPPLES_HEIGHT, 0.46f),
        )
    }
    val waveformRibbonSettings = remember {
        BarSpectrumSettings(
            initialScale = SettingsStore.getFloat(context, KEY_RIBBON_SCALE, 1f),
            initialStrokeWeight = SettingsStore.getFloat(context, KEY_RIBBON_STROKE_WEIGHT, 1f),
            initialHeight = SettingsStore.getFloat(context, KEY_RIBBON_HEIGHT, 0.46f),
        )
    }
    val frequencyTerrainSettings = remember {
        BarSpectrumSettings(
            initialScale = SettingsStore.getFloat(context, KEY_TERRAIN_SCALE, 1f),
            initialStrokeWeight = SettingsStore.getFloat(context, KEY_TERRAIN_STROKE_WEIGHT, 1f),
            initialHeight = SettingsStore.getFloat(context, KEY_TERRAIN_HEIGHT, 0.46f),
        )
    }

    var visualizerOn by remember { mutableStateOf(false) }
    var visibleModes by remember { mutableStateOf(ModePreferences.loadVisible(context)) }
    var mode by remember { mutableStateOf(visibleModes.firstOrNull() ?: VisualMode.VU_METER) }
    var showSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var immersiveMode by remember { mutableStateOf(SettingsStore.getBoolean(context, KEY_IMMERSIVE_MODE, true)) }

    LaunchedEffect(visualizerOn, immersiveMode) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (immersiveMode && visualizerOn) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Only a few modes actually benefit from landscape (see MODES_ALLOWING_LANDSCAPE); everything
    // else is locked back to portrait the moment it's selected. requestedOrientation (not a
    // manifest-level lock) is what lets this vary per mode instead of for the whole app, and the
    // manifest's configChanges="orientation|screenSize" means this never recreates the Activity or
    // loses state, it just physically rotates the display if needed.
    LaunchedEffect(mode) {
        val activity = view.context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (mode in MODES_ALLOWING_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioCaptureService.start(context, result.resultCode, data)
            visualizerOn = true
        } else {
            visualizerOn = false
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    LaunchedEffect(vuMeter, spectrum) {
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                val snapshot = AudioAnalyzer.snapshots.value
                vuMeter.step(dt, snapshot.raw, vuMeterSettings.calibrationOffsetDb)
                spectrum.step(dt, snapshot.bands)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(VisualizerTheme.BACKGROUND)
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(VisualizerTheme.PANEL_RAISED)
                        .border(1.5.dp, VisualizerTheme.HAIRLINE, CircleShape)
                        .clickable { showAppSettings = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("☰", color = VisualizerTheme.ACCENT, fontSize = 15.sp)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var totalDragX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDragX = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                totalDragX += dragAmount
                                change.consume()
                            },
                            onDragEnd = {
                                if (totalDragX <= -SWIPE_THRESHOLD_PX) {
                                    mode = mode.nextIn(visibleModes)
                                } else if (totalDragX >= SWIPE_THRESHOLD_PX) {
                                    mode = mode.previousIn(visibleModes)
                                }
                            },
                        )
                    },
            ) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "visualizer-mode",
                ) { current ->
                    when (current) {
                        VisualMode.VU_METER -> VuMeterScreen(vuMeter)
                        VisualMode.SPECTRUM -> SpectrumScreen(spectrum, spectrumSettings)
                        VisualMode.GRAPHIC_EQ -> GraphicEqScreen(spectrum, graphicEqSettings)
                        VisualMode.RAINBOW_SPECTRUM -> RainbowSpectrumScreen(spectrum, rainbowSpectrumSettings)
                        VisualMode.NEON_CYAN_PULSE -> NeonCyanPulseScreen(spectrum, neonCyanPulseSettings)
                        VisualMode.AUDIO_FIREFLIES -> AudioFirefliesScreen(spectrum, audioFirefliesSettings)
                        VisualMode.RADAR_RIPPLES -> RadarRipplesScreen(spectrum, radarRipplesSettings)
                        VisualMode.WAVEFORM_RIBBON -> WaveformRibbonScreen(spectrum, waveformRibbonSettings)
                        VisualMode.FREQUENCY_TERRAIN -> FrequencyTerrainScreen(spectrum, frequencyTerrainSettings)
                    }
                }

                Text(
                    text = mode.label.uppercase(),
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                )

                if (mode in MODES_WITH_SETTINGS) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(VisualizerTheme.PANEL_RAISED)
                            .border(1.5.dp, VisualizerTheme.HAIRLINE, CircleShape)
                            .clickable { showSettings = !showSettings },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⚙", color = VisualizerTheme.ACCENT, fontSize = 18.sp)
                    }

                    if (showSettings) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(VisualizerTheme.PANEL.copy(alpha = 0.92f))
                                .border(1.dp, VisualizerTheme.HAIRLINE, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            SettingsPanelContent(
                                mode = mode,
                                context = context,
                                vuMeterSettings = vuMeterSettings,
                                spectrumSettings = spectrumSettings,
                                graphicEqSettings = graphicEqSettings,
                                rainbowSpectrumSettings = rainbowSpectrumSettings,
                                neonCyanPulseSettings = neonCyanPulseSettings,
                                audioFirefliesSettings = audioFirefliesSettings,
                                radarRipplesSettings = radarRipplesSettings,
                                waveformRibbonSettings = waveformRibbonSettings,
                                frequencyTerrainSettings = frequencyTerrainSettings,
                            )
                        }
                    }
                }
            }

            ModeSelectorRow(
                currentMode = mode,
                modes = visibleModes,
                onSelect = { mode = it },
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutlinedButton(
                    onClick = {
                        if (visualizerOn) {
                            AudioCaptureService.stop(context)
                            vuMeter.reset()
                            spectrum.reset()
                            visualizerOn = false
                        } else {
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (visualizerOn) VisualizerTheme.CRITICAL else VisualizerTheme.ACCENT,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (visualizerOn) VisualizerTheme.CRITICAL else VisualizerTheme.ACCENT,
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(16.dp),
                ) {
                    Text(
                        text = if (visualizerOn) "STOP" else "VISUALIZE MUSIC",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (showAppSettings) {
            AppSettingsScreen(
                onDismiss = {
                    showAppSettings = false
                    visibleModes = ModePreferences.loadVisible(context)
                    if (mode !in visibleModes) {
                        mode = visibleModes.firstOrNull() ?: mode
                    }
                    immersiveMode = SettingsStore.getBoolean(context, KEY_IMMERSIVE_MODE, true)
                },
            )
        }
    }
}

@Composable
private fun SettingsPanelContent(
    mode: VisualMode,
    context: Context,
    vuMeterSettings: VuMeterSettings,
    spectrumSettings: SpectrumSettings,
    graphicEqSettings: GraphicEqSettings,
    rainbowSpectrumSettings: BarSpectrumSettings,
    neonCyanPulseSettings: BarSpectrumSettings,
    audioFirefliesSettings: BarSpectrumSettings,
    radarRipplesSettings: BarSpectrumSettings,
    waveformRibbonSettings: BarSpectrumSettings,
    frequencyTerrainSettings: BarSpectrumSettings,
) {
    when (mode) {
        VisualMode.VU_METER -> {
            SettingSliderRow(
                "Calibration",
                vuMeterSettings.calibrationOffsetDb,
                VuMeterSettings.CALIBRATION_MIN_DB..VuMeterSettings.CALIBRATION_MAX_DB,
            ) {
                vuMeterSettings.calibrationOffsetDb = it
                SettingsStore.putFloat(context, KEY_VU_CALIBRATION, it)
            }
        }
        VisualMode.SPECTRUM -> {
            SettingChoiceRow(
                "Colors",
                SpectrumColorScheme.entries.map { it to it.label },
                spectrumSettings.colorScheme,
            ) {
                spectrumSettings.colorScheme = it
                SettingsStore.putInt(context, KEY_SPECTRUM_COLOR_SCHEME, it.ordinal)
            }
        }
        VisualMode.GRAPHIC_EQ -> {
            SettingChoiceRow(
                "Colors",
                GraphicEqColorScheme.entries.map { it to it.label },
                graphicEqSettings.colorScheme,
            ) {
                graphicEqSettings.colorScheme = it
                SettingsStore.putInt(context, KEY_GRAPHIC_EQ_COLOR_SCHEME, it.ordinal)
            }
        }
        VisualMode.RAINBOW_SPECTRUM -> BarSpectrumSettingsPanel(rainbowSpectrumSettings, context, KEY_RAINBOW_SCALE, KEY_RAINBOW_STROKE_WEIGHT, KEY_RAINBOW_HEIGHT)
        VisualMode.NEON_CYAN_PULSE -> BarSpectrumSettingsPanel(neonCyanPulseSettings, context, KEY_NEON_SCALE, KEY_NEON_STROKE_WEIGHT, KEY_NEON_HEIGHT)
        VisualMode.AUDIO_FIREFLIES -> BarSpectrumSettingsPanel(audioFirefliesSettings, context, KEY_FIREFLIES_SCALE, KEY_FIREFLIES_STROKE_WEIGHT, KEY_FIREFLIES_HEIGHT)
        VisualMode.RADAR_RIPPLES -> BarSpectrumSettingsPanel(radarRipplesSettings, context, KEY_RIPPLES_SCALE, KEY_RIPPLES_STROKE_WEIGHT, KEY_RIPPLES_HEIGHT)
        VisualMode.WAVEFORM_RIBBON -> BarSpectrumSettingsPanel(waveformRibbonSettings, context, KEY_RIBBON_SCALE, KEY_RIBBON_STROKE_WEIGHT, KEY_RIBBON_HEIGHT)
        VisualMode.FREQUENCY_TERRAIN -> BarSpectrumSettingsPanel(frequencyTerrainSettings, context, KEY_TERRAIN_SCALE, KEY_TERRAIN_STROKE_WEIGHT, KEY_TERRAIN_HEIGHT)
    }
}

/** Scale/Stroke Weight/Height sliders shared by every mode that reuses [BarSpectrumSettings]. */
@Composable
private fun BarSpectrumSettingsPanel(
    settings: BarSpectrumSettings,
    context: Context,
    keyScale: String,
    keyStrokeWeight: String,
    keyHeight: String,
) {
    SettingSliderRow(
        "Scale",
        settings.scale,
        BarSpectrumSettings.SCALE_MIN..BarSpectrumSettings.SCALE_MAX,
    ) {
        settings.scale = it
        SettingsStore.putFloat(context, keyScale, it)
    }
    SettingSliderRow(
        "Stroke Weight",
        settings.strokeWeight,
        BarSpectrumSettings.STROKE_WEIGHT_MIN..BarSpectrumSettings.STROKE_WEIGHT_MAX,
    ) {
        settings.strokeWeight = it
        SettingsStore.putFloat(context, keyStrokeWeight, it)
    }
    SettingSliderRow(
        "Height",
        settings.height,
        BarSpectrumSettings.HEIGHT_MIN..BarSpectrumSettings.HEIGHT_MAX,
    ) {
        settings.height = it
        SettingsStore.putFloat(context, keyHeight, it)
    }
}

/**
 * A persistent, horizontally-scrollable strip of mode chips below the visualizer -- the primary
 * way to switch modes, replacing a bare tap-anywhere-to-cycle gesture that got tedious to repeat
 * with a lot of modes to page through. Tapping a chip jumps straight to that mode; the strip
 * auto-scrolls to keep the current mode's chip in view when the mode changes via swipe.
 */
@Composable
private fun ModeSelectorRow(currentMode: VisualMode, modes: List<VisualMode>, onSelect: (VisualMode) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentMode, modes) {
        val index = modes.indexOf(currentMode)
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(modes) { m ->
            val isCurrent = m == currentMode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCurrent) VisualizerTheme.ACCENT else VisualizerTheme.PANEL_RAISED)
                    .border(1.dp, VisualizerTheme.HAIRLINE, RoundedCornerShape(8.dp))
                    .clickable { onSelect(m) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = m.label.uppercase(),
                    color = if (isCurrent) VisualizerTheme.PANEL else VisualizerTheme.TEXT_PRIMARY,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

private const val KEY_VU_CALIBRATION = "vu_calibration_offset_db"
private const val KEY_SPECTRUM_COLOR_SCHEME = "spectrum_color_scheme"
private const val KEY_GRAPHIC_EQ_COLOR_SCHEME = "graphic_eq_color_scheme"
private const val KEY_RAINBOW_SCALE = "rainbow_spectrum_scale"
private const val KEY_RAINBOW_STROKE_WEIGHT = "rainbow_spectrum_stroke_weight"
private const val KEY_RAINBOW_HEIGHT = "rainbow_spectrum_height"
private const val KEY_NEON_SCALE = "neon_cyan_pulse_scale"
private const val KEY_NEON_STROKE_WEIGHT = "neon_cyan_pulse_stroke_weight"
private const val KEY_NEON_HEIGHT = "neon_cyan_pulse_height"
private const val KEY_FIREFLIES_SCALE = "audio_fireflies_scale"
private const val KEY_FIREFLIES_STROKE_WEIGHT = "audio_fireflies_stroke_weight"
private const val KEY_FIREFLIES_HEIGHT = "audio_fireflies_height"
private const val KEY_RIPPLES_SCALE = "radar_ripples_scale"
private const val KEY_RIPPLES_STROKE_WEIGHT = "radar_ripples_stroke_weight"
private const val KEY_RIPPLES_HEIGHT = "radar_ripples_height"
private const val KEY_RIBBON_SCALE = "waveform_ribbon_scale"
private const val KEY_RIBBON_STROKE_WEIGHT = "waveform_ribbon_stroke_weight"
private const val KEY_RIBBON_HEIGHT = "waveform_ribbon_height"
private const val KEY_TERRAIN_SCALE = "frequency_terrain_scale"
private const val KEY_TERRAIN_STROKE_WEIGHT = "frequency_terrain_stroke_weight"
private const val KEY_TERRAIN_HEIGHT = "frequency_terrain_height"
