package com.samnick.neverspiral

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.mutableStateOf

/** A currently-playing track's title/artist, read from the system's active media sessions. */
data class NowPlayingInfo(val title: String, val artist: String)

/** Holds the latest [NowPlayingInfo] as Compose state so [MainScreen] redraws automatically when
 * it changes, without needing to bind to [NowPlayingListenerService] itself. Null whenever
 * nothing is actively playing, or the user hasn't granted notification-listener access. */
object NowPlaying {
    val current = mutableStateOf<NowPlayingInfo?>(null)
}

/**
 * Reads the system's active media sessions to surface now-playing track/artist metadata, using
 * the same [NotificationListenerService] special permission every lock-screen media-control
 * widget relies on -- there's no narrower API for a third-party app to read another app's
 * now-playing metadata. Publishes into [NowPlaying] so the rest of the app never touches
 * [MediaSessionManager] directly. Prefers whichever session is actively [PlaybackState.STATE_PLAYING];
 * clears to null when nothing is playing or no sessions remain.
 */
class NowPlayingListenerService : NotificationListenerService() {
    private var controllers: List<MediaController> = emptyList()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { attachTo(it ?: emptyList()) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, NowPlayingListenerService::class.java)
        try {
            attachTo(manager.getActiveSessions(component))
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
        } catch (e: SecurityException) {
            // Listener access isn't actually active yet (can happen right after being granted,
            // before the system finishes rebinding) -- nothing to read until it settles.
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        detachAll()
        NowPlaying.current.value = null
    }

    override fun onDestroy() {
        super.onDestroy()
        detachAll()
    }

    private fun attachTo(newControllers: List<MediaController>) {
        detachAll()
        controllers = newControllers
        for (controller in controllers) {
            controller.registerCallback(controllerCallback)
        }
        refresh()
    }

    private fun detachAll() {
        for (controller in controllers) {
            controller.unregisterCallback(controllerCallback)
        }
        controllers = emptyList()
    }

    private fun refresh() {
        val playingController = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val metadata = playingController?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

        NowPlaying.current.value = if (playingController != null && !title.isNullOrBlank()) {
            NowPlayingInfo(title, artist ?: "")
        } else {
            null
        }
    }
}
