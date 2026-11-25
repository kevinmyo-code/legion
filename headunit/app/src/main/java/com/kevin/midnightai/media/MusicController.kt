package com.kevin.midnightai.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.kevin.midnightai.service.MediaNotificationListener

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
 * (e.g. [com.kevin.midnightai.service.LiveToolbox]) must invoke these on the main thread -
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

    // play/pause/next/previous below are the PHONE branch of [MusicRouter] and act ONLY on a
    // non-Spotify session (F12, 2026-10-15). They used to take "the playing session, else any", so
    // with head-unit Spotify holding a session a PHONE "play" could land on Spotify's session, and a
    // failure then blamed the phone. Head-unit Spotify transport goes through App Remote, never here.
    fun play(context: Context): Boolean =
        withPhoneController(context, requirePlaying = false) { it.transportControls.play() }

    /**
     * True when a non-Spotify (phone) session reports PLAYING. A Bluetooth bridge that reports
     * STATE_NONE while it plays reads as not playing here; callers that act on a "no" must tolerate it.
     */
    fun phoneSessionPlaying(context: Context): Boolean =
        activeSessions(context).any {
            it.packageName != SPOTIFY_PACKAGE && it.playbackState?.state == PlaybackState.STATE_PLAYING
        }

    /**
     * Package of the session that is playing right now: a PLAYING one, else the one the Cruise
     * card would show ([pickBestSession]; Bluetooth AVRCP often reports STATE_NONE while it
     * plays). Null when there are no sessions.
     */
    fun playingPackage(context: Context): String? {
        val all = activeSessions(context)
        all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it.packageName }
        val candidates = all.map {
            SessionCandidate(
                state = it.playbackState?.state ?: PlaybackState.STATE_NONE,
                hasTitle = it.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) != null,
                packageName = it.packageName ?: "",
            )
        }
        return pickBestSession(candidates)?.let { all[it].packageName }
    }

    /**
     * After a voice turn: sends play() to the session of [pkg] only if it is not already playing
     * again (an app that paused for the transient focus loss usually resumes itself on the GAIN).
     * Never touches any other app's session, never starts an app that has no session. Returns
     * what it did, for the breadcrumb.
     */
    fun resumeIfPaused(context: Context, pkg: String?): String {
        if (pkg == null) return "skipped: nothing was playing"
        val sessions = activeSessions(context).filter { it.packageName == pkg }
        if (sessions.isEmpty()) return "skipped: no session for $pkg"
        if (sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) return "already playing"
        return try {
            sessions.first().transportControls.play()
            "play sent"
        } catch (e: Exception) {
            Log.w(TAG, "post-turn resume failed: ${e.message}")
            "play failed"
        }
    }

    fun pause(context: Context): Boolean =
        withPhoneController(context, requirePlaying = false) { it.transportControls.pause() }

    fun next(context: Context): Boolean =
        withPhoneController(context, requirePlaying = false) { it.transportControls.skipToNext() }

    fun previous(context: Context): Boolean =
        withPhoneController(context, requirePlaying = false) { it.transportControls.skipToPrevious() }

    /**
     * One PHONE transport command with that session's own state read back
     * (`music-suggestions-1124` P4, mirrored from the phone app so Moose and Alfred answer the
     * same way). [dispatched] says the command left here; [before]/[after] are what the session
     * reported either side of it, for [TransportReadback.verdict] to judge.
     *
     * [withPhoneController] returns true the instant a command is DISPATCHED without throwing,
     * which is not the same as the player having done it. Head-unit Spotify does not need this:
     * [SpotifyController.transport] already awaits App Remote's own CallResult.
     */
    internal data class MoveReport(
        val dispatched: Boolean,
        val before: TransportReadback.Observation?,
        val after: TransportReadback.Observation?,
    )

    /**
     * Dispatches [move] to the phone session and reads THE SAME controller back until the state
     * settles or [TransportReadback.BUDGET_MS] runs out. The same controller deliberately: looking
     * the session up again could land on another app's and read its state as the answer.
     *
     * Must be called on a thread with a Looper (see this object's own note).
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // guard clauses; a transport throw must no-op
    internal suspend fun runMove(context: Context, move: TransportReadback.Move): MoveReport {
        // The same pick [withPhoneController] makes with requirePlaying false: a playing
        // non-Spotify session first, else the first one found. A different pick here would read
        // one session's state back after commanding another.
        val phone = activeSessions(context).filter { it.packageName != SPOTIFY_PACKAGE }
        val c = phone.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: phone.firstOrNull()
            ?: return MoveReport(dispatched = false, before = null, after = null)
        val before = observe(c)
        val sent = try {
            when (move) {
                TransportReadback.Move.PLAY -> c.transportControls.play()
                TransportReadback.Move.PAUSE -> c.transportControls.pause()
                TransportReadback.Move.NEXT -> c.transportControls.skipToNext()
                TransportReadback.Move.PREVIOUS -> c.transportControls.skipToPrevious()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "phone transport command failed: ${e.message}")
            false
        }
        if (!sent) return MoveReport(dispatched = false, before = before, after = null)
        var after = observe(c)
        var waited = 0L
        while (TransportReadback.verdict(move, before, after) != TransportReadback.Verdict.CONFIRMED &&
            waited < TransportReadback.BUDGET_MS
        ) {
            kotlinx.coroutines.delay(TransportReadback.STEP_MS)
            waited += TransportReadback.STEP_MS
            after = observe(c)
        }
        return MoveReport(dispatched = true, before = before, after = after)
    }

    /**
     * What this session reports now, or null when it publishes no playback state at all - a real
     * answer ("cannot tell"), never to be read as paused. A Bluetooth bridge reporting STATE_NONE
     * while it plays is exactly why a skip is judged on the item, not on this flag.
     */
    private fun observe(c: MediaController): TransportReadback.Observation? {
        val state = c.playbackState ?: return null
        val key = c.metadata?.let {
            it.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?: it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        }
        return TransportReadback.Observation(
            // Only an explicit pause or stop is "not playing". Buffering, connecting and every
            // other state are UNKNOWN: a play that is still buffering has not failed.
            playing = when (state.state) {
                PlaybackState.STATE_PLAYING -> true
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> false
                else -> null
            },
            trackKey = key?.takeIf { it.isNotBlank() },
            positionMs = state.position.takeIf { it >= 0 },
        )
    }

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
