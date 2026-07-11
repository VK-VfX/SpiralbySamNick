package com.samnick.neverspiral

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive

private enum class VisualMode(val label: String) {
    VU_METER("VU Meter"),
    SPECTRUM("Spectrum"),
    WAVEFORM("Waveform"),
    GONIOMETER("Goniometer"),
    LOUDNESS("Loudness"),
    GRAPHIC_EQ("Graphic EQ"),
    PEAK_RMS("Peak / RMS"),
    TONAL_BALANCE("Tonal Balance"),
    ;

    fun next(): VisualMode = entries[(ordinal + 1) % entries.size]
    fun previous(): VisualMode = entries[(ordinal - 1 + entries.size) % entries.size]
}

/** Modes with their own gear-icon settings panel. */
private val MODES_WITH_SETTINGS = setOf(
    VisualMode.VU_METER,
    VisualMode.SPECTRUM,
    VisualMode.WAVEFORM,
    VisualMode.GONIOMETER,
    VisualMode.LOUDNESS,
)

private const val SWIPE_THRESHOLD_PX = 90f

/**
 * Hosts all eight visualizer modes plus the single shared "Visualize music" capture toggle. Tap
 * to cycle forward, swipe left/right to cycle either direction, or long-press to jump straight to
 * a mode via a picker grid -- tap-only stopped scaling once there were 8 modes to page through. A
 * hamburger icon in the top-right opens the app-wide [AppSettingsScreen] (player shortcuts,
 * keep-screen-on, OTA updates, about) -- distinct from each mode's own gear-icon tuning panel.
 * Every engine is stepped every frame regardless of which mode is showing (except Loudness and
 * Goniometer's per-sample work, which only runs while their mode is actually visible -- the
 * heaviest per-sample processing in the app, worth skipping when nothing is reading it), so
 * switching among the other six modes still feels instant rather than starting from a frozen
 * reading.
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current

    val vuMeter = remember { VuMeterEngine() }
    val vuMeterSettings = remember {
        VuMeterSettings(SettingsStore.getFloat(context, KEY_VU_CALIBRATION, VuMeterSettings.DEFAULT_CALIBRATION_OFFSET_DB))
    }
    val spectrum = remember { SpectrumEngine(SpectrumAnalyzer.BAND_COUNT) }
    val spectrumSettings = remember {
        val ordinal = SettingsStore.getInt(context, KEY_SPECTRUM_COLOR_SCHEME, SpectrumColorScheme.COOL.ordinal)
        SpectrumSettings(SpectrumColorScheme.entries.getOrElse(ordinal) { SpectrumColorScheme.COOL })
    }
    val waveform = remember { WaveformEngine() }
    val waveformSettings = remember {
        WaveformSettings(
            initialScale = SettingsStore.getFloat(context, KEY_WAVEFORM_SCALE, 1f),
            initialStrokeWeight = SettingsStore.getFloat(context, KEY_WAVEFORM_STROKE_WEIGHT, 1.6f),
            initialIntensity = SettingsStore.getFloat(context, KEY_WAVEFORM_INTENSITY, 1f),
            initialAfterglow = SettingsStore.getFloat(context, KEY_WAVEFORM_AFTERGLOW, 0.35f),
        )
    }
    val goniometer = remember { GoniometerEngine() }
    val goniometerSettings = remember {
        GoniometerSettings(SettingsStore.getFloat(context, KEY_GONIOMETER_TRAIL, GoniometerSettings.DEFAULT_TRAIL_PERSISTENCE))
    }
    val loudness = remember { LoudnessEngine() }
    val loudnessSettings = remember {
        val ordinal = SettingsStore.getInt(context, KEY_LOUDNESS_TARGET, LoudnessTarget.STREAMING.ordinal)
        LoudnessSettings(LoudnessTarget.entries.getOrElse(ordinal) { LoudnessTarget.STREAMING })
    }
    val peakRms = remember { PeakRmsEngine() }
    val tonalBalance = remember { TonalBalanceEngine(SpectrumAnalyzer.BAND_COUNT) }

    var visualizerOn by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VisualMode.VU_METER) }
    var showSettings by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }

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

    LaunchedEffect(vuMeter, spectrum, waveform, goniometer, loudness, peakRms, tonalBalance) {
        var lastFrameNanos = 0L
        var lastWaveform: FloatArray? = null
        var lastLeft: FloatArray? = null
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                val snapshot = AudioAnalyzer.snapshots.value
                vuMeter.step(dt, snapshot.raw, vuMeterSettings.calibrationOffsetDb)
                spectrum.step(dt, snapshot.bands)
                peakRms.step(dt, snapshot.raw, snapshot.peak)
                tonalBalance.step(dt, snapshot.bands)
                // Audio buffers arrive slower than the display refreshes, so most frames see the
                // same snapshot as last time -- only fold a chunk in once, the first frame it
                // shows up, or it would get double-counted into whichever scrolling history reads
                // it (waveform trace, goniometer dot cloud).
                if (snapshot.waveform !== lastWaveform) {
                    waveform.ingest(snapshot.waveform)
                    lastWaveform = snapshot.waveform
                    // Loudness's K-weighting runs two IIR filters over every sample in the buffer
                    // -- the heaviest per-sample work in the app -- so only pay for it while the
                    // Loudness screen is actually visible to read it.
                    if (mode == VisualMode.LOUDNESS) {
                        loudness.ingest(snapshot.waveform)
                    }
                }
                if (snapshot.left !== lastLeft) {
                    lastLeft = snapshot.left
                    if (mode == VisualMode.GONIOMETER) {
                        goniometer.ingest(snapshot.left, snapshot.right)
                    }
                }
                waveform.step(dt)
                if (mode == VisualMode.GONIOMETER) goniometer.step(dt)
                if (mode == VisualMode.LOUDNESS) loudness.step(dt)
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
                        detectTapGestures(
                            onTap = { mode = mode.next() },
                            onLongPress = { showModePicker = true },
                        )
                    }
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
                                    mode = mode.next()
                                } else if (totalDragX >= SWIPE_THRESHOLD_PX) {
                                    mode = mode.previous()
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
                        VisualMode.WAVEFORM -> WaveformScreen(waveform, waveformSettings)
                        VisualMode.GONIOMETER -> GoniometerScreen(goniometer, goniometerSettings)
                        VisualMode.LOUDNESS -> LoudnessScreen(loudness, loudnessSettings)
                        VisualMode.GRAPHIC_EQ -> GraphicEqScreen(spectrum)
                        VisualMode.PEAK_RMS -> PeakRmsScreen(peakRms)
                        VisualMode.TONAL_BALANCE -> TonalBalanceScreen(tonalBalance)
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

                ModeIndicatorDots(
                    currentMode = mode,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
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
                                waveformSettings = waveformSettings,
                                goniometerSettings = goniometerSettings,
                                loudnessSettings = loudnessSettings,
                            )
                        }
                    }
                }

                if (showModePicker) {
                    ModePickerOverlay(
                        currentMode = mode,
                        onSelect = {
                            mode = it
                            showModePicker = false
                        },
                        onDismiss = { showModePicker = false },
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutlinedButton(
                    onClick = {
                        if (visualizerOn) {
                            AudioCaptureService.stop(context)
                            vuMeter.reset()
                            spectrum.reset()
                            waveform.reset()
                            goniometer.reset()
                            loudness.reset()
                            peakRms.reset()
                            tonalBalance.reset()
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
            AppSettingsScreen(onDismiss = { showAppSettings = false })
        }
    }
}

@Composable
private fun SettingsPanelContent(
    mode: VisualMode,
    context: Context,
    vuMeterSettings: VuMeterSettings,
    spectrumSettings: SpectrumSettings,
    waveformSettings: WaveformSettings,
    goniometerSettings: GoniometerSettings,
    loudnessSettings: LoudnessSettings,
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
        VisualMode.WAVEFORM -> {
            SettingSliderRow(
                "Scale",
                waveformSettings.scale,
                WaveformSettings.SCALE_MIN..WaveformSettings.SCALE_MAX,
            ) {
                waveformSettings.scale = it
                SettingsStore.putFloat(context, KEY_WAVEFORM_SCALE, it)
            }
            SettingSliderRow(
                "Stroke Weight",
                waveformSettings.strokeWeight,
                WaveformSettings.STROKE_WEIGHT_MIN..WaveformSettings.STROKE_WEIGHT_MAX,
            ) {
                waveformSettings.strokeWeight = it
                SettingsStore.putFloat(context, KEY_WAVEFORM_STROKE_WEIGHT, it)
            }
            SettingSliderRow(
                "Intensity",
                waveformSettings.intensity,
                WaveformSettings.INTENSITY_MIN..1f,
            ) {
                waveformSettings.intensity = it
                SettingsStore.putFloat(context, KEY_WAVEFORM_INTENSITY, it)
            }
            SettingSliderRow(
                "Afterglow",
                waveformSettings.afterglow,
                0f..WaveformSettings.AFTERGLOW_MAX,
            ) {
                waveformSettings.afterglow = it
                SettingsStore.putFloat(context, KEY_WAVEFORM_AFTERGLOW, it)
            }
        }
        VisualMode.GONIOMETER -> {
            SettingSliderRow(
                "Trail",
                goniometerSettings.trailPersistence,
                GoniometerSettings.TRAIL_PERSISTENCE_MIN..GoniometerSettings.TRAIL_PERSISTENCE_MAX,
            ) {
                goniometerSettings.trailPersistence = it
                SettingsStore.putFloat(context, KEY_GONIOMETER_TRAIL, it)
            }
        }
        VisualMode.LOUDNESS -> {
            SettingChoiceRow(
                "Target",
                LoudnessTarget.entries.map { it to it.label },
                loudnessSettings.target,
            ) {
                loudnessSettings.target = it
                SettingsStore.putInt(context, KEY_LOUDNESS_TARGET, it.ordinal)
            }
        }
        else -> Unit
    }
}

@Composable
private fun ModeIndicatorDots(currentMode: VisualMode, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in VisualMode.entries) {
            val isCurrent = m == currentMode
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) VisualizerTheme.ACCENT else VisualizerTheme.HAIRLINE),
            )
        }
    }
}

/** A full-screen picker grid, opened via long-press, for jumping straight to any of the 8 modes. */
@Composable
private fun ModePickerOverlay(currentMode: VisualMode, onSelect: (VisualMode) -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VisualizerTheme.BACKGROUND.copy(alpha = 0.94f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(VisualizerTheme.PANEL)
                .border(1.dp, VisualizerTheme.HAIRLINE, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            for (rowModes in VisualMode.entries.chunked(2)) {
                Row {
                    for (m in rowModes) {
                        val isCurrent = m == currentMode
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .width(140.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCurrent) VisualizerTheme.ACCENT else VisualizerTheme.PANEL_RAISED)
                                .clickable { onSelect(m) }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = m.label.uppercase(),
                                color = if (isCurrent) VisualizerTheme.PANEL else VisualizerTheme.TEXT_PRIMARY,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val KEY_VU_CALIBRATION = "vu_calibration_offset_db"
private const val KEY_SPECTRUM_COLOR_SCHEME = "spectrum_color_scheme"
private const val KEY_WAVEFORM_SCALE = "waveform_scale"
private const val KEY_WAVEFORM_STROKE_WEIGHT = "waveform_stroke_weight"
private const val KEY_WAVEFORM_INTENSITY = "waveform_intensity"
private const val KEY_WAVEFORM_AFTERGLOW = "waveform_afterglow"
private const val KEY_GONIOMETER_TRAIL = "goniometer_trail_persistence"
private const val KEY_LOUDNESS_TARGET = "loudness_target"
