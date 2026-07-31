package com.kevin.legion.service

import android.service.notification.NotificationListenerService

/**
 * Exists so [android.media.session.MediaSessionManager] will grant us access to
 * active media sessions (e.g. Spotify's now-playing state) once the user enables
 * notification access for this app in system settings. That now-playing data is
 * the live "data stream" Zero reads to know what's playing (see
 * [com.kevin.legion.media.NowPlayingController]); no notifications are parsed here.
 */
class MediaNotificationListener : NotificationListenerService()
