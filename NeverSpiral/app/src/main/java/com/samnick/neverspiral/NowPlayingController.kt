package com.samnick.neverspiral

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Snapshot of whichever [MediaController] session [NowPlayingController] currently considers
 * "now playing". [positionMs] and [positionAnchorRealtimeMs] describe the position as of the last
 * state change, not a continuously-updated value -- consumers (see NowPlayingBar) extrapolate the
 * live position themselves from elapsed wall-clock time since the anchor, the same delta-time
 * approach every visualizer engine already uses instead of polling a live position directly.
 */
data class NowPlayingSnapshot(
    val title: String,
    val artist: String,
    val appLabel: String,
    val packageName: String,
    val albumArt: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val positionAnchorRealtimeMs: Long,
    val playbackSpeed: Float,
)

/**
 * Process-wide publisher for the "best" active media session, mirroring [AudioAnalyzer]'s
 * publish-once/observe-everywhere shape. [MediaNotificationListenerService] is the sole writer;
 * every consumer (NowPlayingBar, AppSettingsScreen's status row) just reads [nowPlaying] /
 * [listenerConnected] reactively. Picking "best" prefers whichever session is actually
 * [PlaybackState.STATE_PLAYING] over one merely paused in the background, so switching from
 * Spotify to YouTube Music (say) follows playback rather than sticking with whichever app
 * happened to publish a session first.
 */
object NowPlayingController {
    private val _nowPlaying = MutableStateFlow<NowPlayingSnapshot?>(null)
    val nowPlaying: StateFlow<NowPlayingSnapshot?> = _nowPlaying

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected

    private var activeController: MediaController? = null
    private var lastContext: Context? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshActive()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshActive()
        override fun onSessionDestroyed() {
            activeController = null
            _nowPlaying.value = null
        }
    }

    fun onSessionsChanged(context: Context, controllers: List<MediaController>) {
        lastContext = context
        val best = pickBest(controllers)
        if (best?.sessionToken != activeController?.sessionToken) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = best
            activeController?.registerCallback(controllerCallback)
        }
        refreshActive()
    }

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun playPause() {
        val controller = activeController ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun skipNext() {
        activeController?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        activeController?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        activeController?.transportControls?.seekTo(positionMs)
    }

    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Nudges the system to (re)bind [MediaNotificationListenerService] immediately rather than
     * waiting for whatever delay it would otherwise take to notice a fresh permission grant. */
    fun requestRebind(context: Context) {
        NotificationListenerService.requestRebind(ComponentName(context, MediaNotificationListenerService::class.java))
    }

    private fun pickBest(controllers: List<MediaController>): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()

    private fun refreshActive() {
        val controller = activeController
        val context = lastContext
        if (controller == null || context == null) {
            _nowPlaying.value = null
            return
        }
        val metadata = controller.metadata
        val state = controller.playbackState
        val appLabel = try {
            val pm = context.packageManager
            pm.getApplicationInfo(controller.packageName, 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            controller.packageName
        }
        _nowPlaying.value = NowPlayingSnapshot(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Track",
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            appLabel = appLabel,
            packageName = controller.packageName,
            albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            positionAnchorRealtimeMs = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }
}
