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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive

private enum class VisualMode(val label: String) {
    VU_METER("VU Meter"),
    SPECTRUM("Spectrum"),
    OSCILLOSCOPE("Oscilloscope"),
    ;

    fun next(): VisualMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Hosts all three visualizer modes plus the single shared "Visualize music" capture toggle.
 * Tapping anywhere on the visualization cycles to the next mode with a smooth crossfade, instead
 * of a fixed tab bar -- which left little room on screen and, pinned to the very top, collided
 * with the status bar. All three engines are stepped every frame regardless of which mode is
 * showing, so switching feels instant rather than starting from a frozen reading.
 */
@Composable
fun MainScreen() {
    val vuMeter = remember { VuMeterEngine() }
    val spectrum = remember { SpectrumEngine(SpectrumAnalyzer.BAND_COUNT) }
    val oscilloscope = remember { OscilloscopeEngine() }
    val oscilloscopeSettings = remember { OscilloscopeSettings() }
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

    LaunchedEffect(vuMeter, spectrum, oscilloscope) {
        var lastFrameNanos = 0L
        var lastWaveform: FloatArray? = null
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                val snapshot = AudioAnalyzer.snapshots.value
                vuMeter.step(dt, snapshot.raw)
                spectrum.step(dt, snapshot.bands)
                // Audio buffers arrive slower than the display refreshes, so most frames see the
                // same snapshot as last time -- only fold a waveform chunk in once, the first
                // frame it shows up, or it would get double-counted into the scrolling history.
                if (snapshot.waveform !== lastWaveform) {
                    oscilloscope.ingest(snapshot.waveform)
                    lastWaveform = snapshot.waveform
                }
                oscilloscope.step(dt)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0E))
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
                }
            }

            Text(
                text = mode.label,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
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
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable { showOscilloscopeSettings = !showOscilloscopeSettings },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚙", color = Color.White, fontSize = 18.sp)
                }

                if (showOscilloscopeSettings) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
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
            Button(
                onClick = {
                    if (visualizerOn) {
                        AudioCaptureService.stop(context)
                        vuMeter.reset()
                        spectrum.reset()
                        oscilloscope.reset()
                        visualizerOn = false
                    } else {
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp),
            ) {
                Text(if (visualizerOn) "Stop visualizer" else "Visualize music")
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
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.width(96.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "%.2f".format(value),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.width(40.dp),
        )
    }
}
