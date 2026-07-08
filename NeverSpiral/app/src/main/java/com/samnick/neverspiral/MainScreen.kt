package com.samnick.neverspiral

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
    OSCILLOSCOPE("Oscilloscope"),
    GONIOMETER("Goniometer"),
    LOUDNESS("Loudness"),
    GRAPHIC_EQ("Graphic EQ"),
    PEAK_RMS("Peak / RMS"),
    TONAL_BALANCE("Tonal Balance"),
    ;

    fun next(): VisualMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Hosts all eight visualizer modes plus the single shared "Visualize music" capture toggle.
 * Tapping anywhere on the visualization cycles to the next mode with a smooth crossfade, instead
 * of a fixed tab bar -- which left little room on screen and, pinned to the very top, collided
 * with the status bar. Every engine is stepped every frame regardless of which mode is showing,
 * so switching feels instant rather than starting from a frozen reading.
 */
@Composable
fun MainScreen() {
    val vuMeter = remember { VuMeterEngine() }
    val spectrum = remember { SpectrumEngine(SpectrumAnalyzer.BAND_COUNT) }
    val oscilloscope = remember { OscilloscopeEngine() }
    val oscilloscopeSettings = remember { OscilloscopeSettings() }
    val goniometer = remember { GoniometerEngine() }
    val loudness = remember { LoudnessEngine() }
    val peakRms = remember { PeakRmsEngine() }
    val tonalBalance = remember { TonalBalanceEngine(SpectrumAnalyzer.BAND_COUNT) }
    val context = LocalContext.current
    var visualizerOn by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VisualMode.VU_METER) }
    var showOscilloscopeSettings by remember { mutableStateOf(false) }

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

    LaunchedEffect(vuMeter, spectrum, oscilloscope, goniometer, loudness, peakRms, tonalBalance) {
        var lastFrameNanos = 0L
        var lastWaveform: FloatArray? = null
        var lastLeft: FloatArray? = null
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                val snapshot = AudioAnalyzer.snapshots.value
                vuMeter.step(dt, snapshot.raw)
                spectrum.step(dt, snapshot.bands)
                peakRms.step(dt, snapshot.raw, snapshot.peak)
                tonalBalance.step(dt, snapshot.bands)
                // Audio buffers arrive slower than the display refreshes, so most frames see the
                // same snapshot as last time -- only fold a chunk in once, the first frame it
                // shows up, or it would get double-counted into whichever scrolling history reads
                // it (oscilloscope trace, goniometer dot cloud).
                if (snapshot.waveform !== lastWaveform) {
                    oscilloscope.ingest(snapshot.waveform)
                    loudness.ingest(snapshot.waveform)
                    lastWaveform = snapshot.waveform
                }
                if (snapshot.left !== lastLeft) {
                    goniometer.ingest(snapshot.left, snapshot.right)
                    lastLeft = snapshot.left
                }
                oscilloscope.step(dt)
                goniometer.step(dt)
                loudness.step(dt)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VisualizerTheme.BACKGROUND)
            .safeDrawingPadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { mode = mode.next() })
                },
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "visualizer-mode",
            ) { current ->
                when (current) {
                    VisualMode.VU_METER -> VuMeterScreen(vuMeter)
                    VisualMode.SPECTRUM -> SpectrumScreen(spectrum)
                    VisualMode.OSCILLOSCOPE -> OscilloscopeScreen(oscilloscope, oscilloscopeSettings)
                    VisualMode.GONIOMETER -> GoniometerScreen(goniometer)
                    VisualMode.LOUDNESS -> LoudnessScreen(loudness)
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

            if (mode == VisualMode.OSCILLOSCOPE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(VisualizerTheme.PANEL_RAISED)
                        .border(1.5.dp, VisualizerTheme.HAIRLINE, CircleShape)
                        .clickable { showOscilloscopeSettings = !showOscilloscopeSettings },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚙", color = VisualizerTheme.ACCENT, fontSize = 18.sp)
                }

                if (showOscilloscopeSettings) {
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
                        OscilloscopeSliderRow(
                            "Scale",
                            oscilloscopeSettings.scale,
                            OscilloscopeSettings.SCALE_MIN..OscilloscopeSettings.SCALE_MAX,
                        ) { oscilloscopeSettings.scale = it }
                        OscilloscopeSliderRow(
                            "Stroke Weight",
                            oscilloscopeSettings.strokeWeight,
                            OscilloscopeSettings.STROKE_WEIGHT_MIN..OscilloscopeSettings.STROKE_WEIGHT_MAX,
                        ) { oscilloscopeSettings.strokeWeight = it }
                        OscilloscopeSliderRow(
                            "Intensity",
                            oscilloscopeSettings.intensity,
                            OscilloscopeSettings.INTENSITY_MIN..1f,
                        ) { oscilloscopeSettings.intensity = it }
                        OscilloscopeSliderRow(
                            "Afterglow",
                            oscilloscopeSettings.afterglow,
                            0f..OscilloscopeSettings.AFTERGLOW_MAX,
                        ) { oscilloscopeSettings.afterglow = it }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            OutlinedButton(
                onClick = {
                    if (visualizerOn) {
                        AudioCaptureService.stop(context)
                        vuMeter.reset()
                        spectrum.reset()
                        oscilloscope.reset()
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
}

@Composable
private fun OscilloscopeSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(96.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = VisualizerTheme.ACCENT,
                activeTrackColor = VisualizerTheme.ACCENT,
                inactiveTrackColor = VisualizerTheme.HAIRLINE,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "%.2f".format(value),
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp),
        )
    }
}
