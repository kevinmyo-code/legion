package com.kevin.midnightai.media

/**
 * Pure decisions for the App Remote link (reconnect pacing, whether a play should spend time on a
 * connect attempt). Android-free, so every branch runs in a plain JVM test.
 *
 * History: this file used to hold the Web API (Spotify Connect) playback fallback - device
 * picking, transport over HTTP, the UI-less wake, the Spotify-app launch policy. All of it was
 * removed 2026-10-15 (Kevin: "remove all capability of the voice ai to control the spotify app on
 * the head unit itself. and just fully run app remote"). App Remote is the only playback and
 * transport path; the Web API stays for reads only (search, playlists, queue contents).
 */
object SpotifyConnectLogic {

    /**
     * Waits before each silent reconnect attempt after an App Remote drop: Spotify may still be
     * restarting at 5s, so later tries are spaced out. Bounded (three) so a Spotify that is
     * really gone is not retried forever; MainActivity.onResume remains the backstop.
     */
    val RECONNECT_SCHEDULE_MS: List<Long> = listOf(5_000L, 20_000L, 60_000L)

    /** How long a recent App Remote failure skips a fresh App Remote attempt before a play. */
    const val RECENT_FAILURE_MS = 60_000L

    /**
     * Whether a play or transport command should spend time on an App Remote connect attempt.
     * Retrying an attempt that failed seconds ago costs the driver several seconds of silence
     * for an answer already known, so inside the [RECENT_FAILURE_MS] window it fails fast with the
     * recorded reason instead. An attempt still in flight is joined (it may be seconds from
     * connecting). [msSinceLastFailure] null = no failure on record.
     *
     * Before 2026-10-15 a `callerAlreadyTried` flag also existed, to stop a second wait stacking
     * ahead of the Web API leg. That leg is gone, so the flag had nothing left to protect.
     */
    fun shouldTryAppRemote(
        isConnected: Boolean,
        attemptInFlight: Boolean,
        msSinceLastFailure: Long?,
    ): Boolean =
        isConnected || attemptInFlight || msSinceLastFailure == null || msSinceLastFailure >= RECENT_FAILURE_MS
}
