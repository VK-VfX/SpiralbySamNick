package com.samnick.neverspiral

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/**
 * Hosts both visualizer modes behind a tab switcher, plus the single shared "Visualize music"
 * capture toggle. Both engines are stepped every frame regardless of which tab is visible, so
 * switching tabs shows a live reading immediately rather than a stale, frozen one.
 */
@Composable
fun MainScreen() {
    val vuMeter = remember { VuMeterEngine() }
    val spectrum = remember { SpectrumEngine(SpectrumAnalyzer.BAND_COUNT) }
    val context = LocalContext.current
    var visualizerOn by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

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
                vuMeter.step(dt, snapshot.raw)
                spectrum.step(dt, snapshot.bands)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF0B0B0E),
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("VU Meter") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Spectrum") })
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selectedTab == 0) {
                VuMeterScreen(vuMeter)
            } else {
                SpectrumScreen(spectrum)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Button(
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
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp),
            ) {
                Text(if (visualizerOn) "Stop visualizer" else "Visualize music")
            }
        }
    }
}
