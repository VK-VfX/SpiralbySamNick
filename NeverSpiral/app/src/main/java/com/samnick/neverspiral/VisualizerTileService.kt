package com.samnick.neverspiral

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A Quick Settings tile reflecting whether capture is currently running (via
 * [AudioCaptureService.isRunning]) and opening the app on tap. It can't start capture directly --
 * [android.media.projection.MediaProjection] requires a foreground Activity to show the system
 * "start recording or casting" consent dialog, and that consent isn't persisted across app
 * restarts, so a headless tile tap could never skip it. This is a quick-access shortcut, not a
 * remote toggle.
 */
class VisualizerTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = AudioCaptureService.isRunning.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // Tile.subtitle was added in API 29 -- setting it on older platforms crashes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Visualizing" else "Tap to open"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_bars)
        tile.updateTile()
    }
}
