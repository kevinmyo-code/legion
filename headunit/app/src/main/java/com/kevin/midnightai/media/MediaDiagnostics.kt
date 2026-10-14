package com.kevin.midnightai.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import com.kevin.midnightai.service.MediaNotificationListener

/**
 * Opens the system's notification-access settings, where the driver grants
 * (or checks) this app's [android.service.notification.NotificationListenerService]
 * permission - the one grant [NowPlayingController.hasAccess]/[MediaDiagnostics]
 * both gate on. Originally lived only in the now-deleted DriveHome screen;
 * moved here (rather than lost with it) so both the Cruise cassette's
 * NEEDS_NOTIFICATION_ACCESS empty state and MediaDiagnosticsScreen's own
 * "OPEN NOTIFICATION ACCESS SETTINGS" button can call the same one line.
 */
fun openNotificationAccessSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** One active MediaSession's diagnostic read, for the media diagnostics screen. */
data class SessionDiagnostic(
    val packageName: String,
    val playbackStateLabel: String,
    val metadataState: MetadataFillState,
    /**
     * What the session itself DECLARES it supports via
     * [PlaybackState.getActions] - e.g. "PLAY, PAUSE, SKIP_NEXT". This is the
     * only honest, synchronous answer to "does it respond to transport
     * controls": actually issuing play/pause here to "test" it would change
     * real playback just for opening a diagnostics screen, so this reports
     * what the session advertises rather than performing a live probe.
     */
    val declaredActionsLabel: String,
)

/** The full snapshot the diagnostics screen renders, gathered fresh on each read. */
data class MediaDiagnosticsSnapshot(
    val hasNotificationAccess: Boolean,
    val sessions: List<SessionDiagnostic>,
)

/**
 * Reads the raw MediaSession state directly from [MediaSessionManager],
 * independent of [NowPlayingController]'s own picked-one-session StateFlow.
 * This is deliberately a SEPARATE read path: [NowPlayingController] collapses
 * "no active session" and "session with null metadata" to the same `null`
 * state (see its kdoc), which is exactly the ambiguity this screen exists to
 * resolve, so it cannot be built out of that StateFlow alone.
 */
object MediaDiagnostics {

    fun hasAnyActiveSession(context: Context): Boolean = snapshot(context).sessions.isNotEmpty()

    fun snapshot(context: Context): MediaDiagnosticsSnapshot {
        val hasAccess = NowPlayingController.hasAccess(context)
        if (!hasAccess) return MediaDiagnosticsSnapshot(hasNotificationAccess = false, sessions = emptyList())

        val app = context.applicationContext
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(app, MediaNotificationListener::class.java)
        val sessions = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            // The grant can be revoked between the hasAccess() check above and this
            // call (system settings, another app disabling us) - fall through to
            // "access granted" being stale rather than crashing the diagnostics
            // screen itself.
            emptyList()
        }

        val diagnostics = sessions.map { controller ->
            val metadata = controller.metadata
            SessionDiagnostic(
                packageName = controller.packageName ?: "(unknown package)",
                playbackStateLabel = playbackStateLabel(controller.playbackState?.state),
                metadataState = classifyMetadataFillState(
                    title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    metadataIsNull = metadata == null,
                ),
                declaredActionsLabel = declaredActionsLabel(controller.playbackState?.actions ?: 0L),
            )
        }
        return MediaDiagnosticsSnapshot(hasNotificationAccess = true, sessions = diagnostics)
    }

    private fun playbackStateLabel(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_CONNECTING -> "connecting"
        null, PlaybackState.STATE_NONE -> "none reported"
        else -> "state code $state"
    }

    private fun declaredActionsLabel(actions: Long): String {
        if (actions == 0L) return "none declared"
        val names = buildList {
            if (actions and PlaybackState.ACTION_PLAY != 0L) add("PLAY")
            if (actions and PlaybackState.ACTION_PAUSE != 0L) add("PAUSE")
            if (actions and PlaybackState.ACTION_PLAY_PAUSE != 0L) add("PLAY_PAUSE")
            if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add("SKIP_NEXT")
            if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add("SKIP_PREVIOUS")
        }
        return if (names.isEmpty()) "other (0x${actions.toString(16)})" else names.joinToString(", ")
    }
}
