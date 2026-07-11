package com.samnick.neverspiral

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighRefreshRate()
        setContent {
            MainScreen()
        }
    }

    /**
     * Android ties refresh rate to the window, not to individual views, so this is a best-effort
     * hint for the whole app rather than something scoped to just the waveform view: on a 90Hz or
     * 120Hz display the system will pick its highest supported mode at or below this value; on a
     * 60Hz-only device it's silently ignored.
     */
    private fun requestHighRefreshRate() {
        val params = window.attributes
        params.preferredRefreshRate = 120f
        window.attributes = params
    }
}
