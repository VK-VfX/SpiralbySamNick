package com.samnick.neverspiral

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf

class MainActivity : ComponentActivity() {
    private val displayManager: DisplayManager by lazy { getSystemService(DisplayManager::class.java) }

    // Requesting the fastest mode once in onCreate isn't enough on its own: many phones do
    // adaptive/variable refresh rate and drop from, say, 120Hz to 90Hz or 60Hz mid-session to save
    // battery, and the system is free to override a one-time preference at any point. This listener
    // is what lets the app notice and re-request the fastest mode whenever the display actually
    // changes, instead of silently staying stuck at whatever rate the system settled on afterward.
    // It's purely about which physical rate the display is driven at -- every visualizer's own
    // animation/smoothing already reads its timestep from the real per-frame delta time (see
    // MainScreen's frame loop), not a fixed-fps assumption, so it already tracks whatever rate this
    // ends up requesting without needing any changes of its own.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == currentDisplay()?.displayId) requestNativeRefreshRate()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNativeRefreshRate()
        setContent {
            MainScreen()
        }
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, null)
        // The active mode can have changed while the Activity was stopped (e.g. the system
        // dropped to a lower rate to save battery, or the app moved to a different display).
        requestNativeRefreshRate()
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    // Granting Notification Access happens on a separate system settings screen, entirely outside
    // Compose's own recomposition triggers -- there's no other signal that fires when the user
    // comes back. Bumping this plain Compose state in onResume (the same cross-component
    // mutableStateOf pattern AudioCaptureService.isRunning already uses) gives MainScreen
    // something to key a re-check off of every time the app returns to the foreground.
    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    /**
     * Android ties refresh rate to the window, not to individual views, so this is a best-effort
     * hint for the whole app rather than something scoped to just one visualizer mode. Rather than
     * requesting a fixed value (which would cap a 144Hz phone at that number, or do nothing useful
     * on a 60Hz-only one), this reads the display's actual supported modes and asks for the one
     * with the highest refresh rate -- whatever that device's true native max is. Falls back to a
     * plain preferredRefreshRate hint if, for some reason, no supported modes are reported.
     */
    private fun requestNativeRefreshRate() {
        val nativeMode = currentDisplay()?.supportedModes?.maxByOrNull { it.refreshRate }
        val params = window.attributes
        if (nativeMode != null) {
            params.preferredDisplayModeId = nativeMode.modeId
        } else {
            params.preferredRefreshRate = 120f
        }
        window.attributes = params
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

    companion object {
        val resumeTick = mutableIntStateOf(0)
    }
}
