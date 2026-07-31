package com.kevin.legion.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.kevin.legion.service.MediaNotificationListener

/**
 * Controls whatever media is playing through Android's MediaSession framework -
 * transport only (play/pause/next/previous). Music playback itself now lives on
 * the driver's PHONE, streamed to the head unit over Bluetooth (A2DP); the head
 * unit surfaces the phone's AVRCP metadata as a media session, so these transport
 * commands are relayed back to the phone. No Spotify app, no app launching, no
 * search-to-play (AVRCP can't carry a search) - the driver picks the track on
 * their phone, Zero just controls transport hands-free.
 *
 * Reading/controlling the session needs the one-time notification-access grant
 * (the same gate [NowPlayingController] uses); without it, getActiveSessions
 * throws SecurityException and the commands no-op.
 *
 * NOTE: the media framework expects to run on a thread with a Looper, so callers
 * (e.g. [com.kevin.legion.service.LiveToolbox]) must invoke these on the main thread -
 * a bare worker thread throws "Can't create handler inside thread that has not
 * called Looper.prepare()". Everything is defensively try/caught so a transport
 * failure no-ops instead of crashing.
 */
object MusicController {
    private const val TAG = "MusicController"

    /**
     * The package that shows up in [activeSessions] once the head unit's own
     * Spotify app (BYO App Remote, acct B) is connected and playing locally -
     * as opposed to the vendor Bluetooth bridge that surfaces the driver's
     * PHONE's AVRCP session (acct A), which is NOT this package. There is no
     * documented/stable package name for that BT bridge session across
     * vendors, so [pausePhone]/[resumePhone] use "not Spotify" as the phone
     * heuristic rather than an allowlist of BT bridge packages. This is the
     * fragile leg of source switching: it holds for the one vendor unit this
     * app targets in practice, but a different head-unit's BT stack could in
     * theory publish a session under [SPOTIFY_PACKAGE] too (unlikely - that
     * would mean the BT bridge impersonates Spotify's own package) or fail to
     * publish AVRCP metadata as a MediaSession at all (falls through to
     * no-op false, never a crash).
     */
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    fun play(context: Context): Boolean = withController(context) { it.transportControls.play() }

    fun pause(context: Context): Boolean = withController(context) { it.transportControls.pause() }

    fun next(context: Context): Boolean = withController(context) { it.transportControls.skipToNext() }

    fun previous(context: Context): Boolean = withController(context) { it.transportControls.skipToPrevious() }

    /**
     * Pauses the driver's PHONE stream specifically (not Spotify on the head
     * unit) - used when switching TO head-unit Spotify, so acct A goes quiet
     * before acct B starts. No-ops (returns false) if the phone isn't
     * currently playing; there's nothing to pause.
     */
    fun pausePhone(context: Context): Boolean =
        withPhoneController(context, requirePlaying = true) { it.transportControls.pause() }

    /**
     * Resumes the driver's PHONE stream - used when switching back from head
     * unit Spotify. Best-effort: sends play() to whatever non-Spotify session
     * is available (playing one preferred, else the first found), since the
     * phone side has no explicit "resume this" state to restore.
     */
    fun resumePhone(context: Context): Boolean =
        withPhoneController(context, requirePlaying = false) { it.transportControls.play() }

    private inline fun withController(context: Context, action: (MediaController) -> Unit): Boolean {
        val c = activeController(context) ?: return false
        return try {
            action(c)
            true
        } catch (e: Exception) {
            Log.w(TAG, "transport command failed: ${e.message}")
            false
        }
    }

    /**
     * Same shape as [withController] but scoped to non-Spotify (phone)
     * sessions only. When [requirePlaying] is true (pause direction), only
     * acts if a non-Spotify session is actively STATE_PLAYING - pausing
     * something that isn't playing would be a no-op anyway, and returning
     * false lets callers skip the "paused your phone" confirmation
     * correctly. When false (resume direction), prefers a playing non-Spotify
     * session but falls back to the first one found.
     */
    private inline fun withPhoneController(
        context: Context,
        requirePlaying: Boolean,
        action: (MediaController) -> Unit,
    ): Boolean {
        val phoneSessions = activeSessions(context).filter { it.packageName != SPOTIFY_PACKAGE }
        val target = if (requirePlaying) {
            phoneSessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return false
        } else {
            phoneSessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: phoneSessions.firstOrNull() ?: return false
        }
        return try {
            action(target)
            true
        } catch (e: Exception) {
            Log.w(TAG, "phone transport command failed: ${e.message}")
            false
        }
    }

    /** The currently-playing media session, else any active one, or null if none. */
    private fun activeController(context: Context): MediaController? {
        val all = activeSessions(context)
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull()
    }

    private fun activeSessions(context: Context): List<MediaController> {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(app, MediaNotificationListener::class.java)
        return try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access for media sessions: ${e.message}")
            emptyList()
        }
    }
}
