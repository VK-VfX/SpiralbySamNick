package com.samnick.neverspiral

import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNativeRefreshRate()
        setContent {
            MainScreen()
        }
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
}
