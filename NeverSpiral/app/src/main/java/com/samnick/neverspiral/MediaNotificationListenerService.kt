package com.samnick.neverspiral

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService

/**
 * A notification-listener shell whose only real purpose is the system permission it unlocks: once
 * the user grants Notification Access, [MediaSessionManager.getActiveSessions] can enumerate
 * every app's currently published media session system-wide -- the same "listen to whatever's
 * playing, no per-app integration" philosophy [AudioCaptureService] already applies to the audio
 * stream itself, but here for transport control and metadata instead of PCM. This service never
 * inspects an actual notification -- [NowPlayingController] only needs the session list, so
 * onNotificationPosted/onNotificationRemoved are left as their no-op defaults.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        NowPlayingController.onSessionsChanged(applicationContext, controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, MediaNotificationListenerService::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsListener, component)
        NowPlayingController.onSessionsChanged(applicationContext, manager.getActiveSessions(component))
        NowPlayingController.setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val manager = getSystemService(MediaSessionManager::class.java)
        manager.removeOnActiveSessionsChangedListener(sessionsListener)
        NowPlayingController.setListenerConnected(false)
        NowPlayingController.onSessionsChanged(applicationContext, emptyList())
    }
}
