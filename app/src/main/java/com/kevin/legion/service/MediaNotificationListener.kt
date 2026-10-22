package com.kevin.legion.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kevin.legion.MidnightApplication

/**
 * Two jobs, one grant.
 *
 * **1. Now playing.** Exists so [android.media.session.MediaSessionManager] will grant us access to
 * active media sessions (e.g. Spotify's now-playing state) once the user enables notification access
 * in system settings. That now-playing data is the live "data stream" Zero reads to know what's
 * playing (see [com.kevin.legion.media.NowPlayingController]).
 *
 * **2. Dumb mode's hold (ADR 0062, added 2026-10-22).** While dumb phone mode is on, each posted
 * notification goes to `DumbModeGraph.onNotificationPosted`, which lets a chosen person's through and
 * cancels and counts the rest. It reads WHO a notification is from to decide, and keeps nothing: the
 * digest holds a package, a count and a time (CLAUDE.md section 7, third-party content is read-through).
 * Off the mode, [onNotificationPosted] returns at once and nothing is read.
 *
 * This class used to claim "no notifications are parsed here". That was true until dumb mode and is
 * not any more; it is corrected rather than deleted so the history is not lost.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as? MidnightApplication ?: return
        app.dumb.onNotificationPosted(sbn) { key -> cancelNotification(key) }
    }
}
