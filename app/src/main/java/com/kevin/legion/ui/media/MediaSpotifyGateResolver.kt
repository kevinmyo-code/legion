package com.kevin.legion.ui.media

import com.kevin.legion.ui.spotify.SpotifyConnectResolver

/**
 * Pure gate for [MediaScreen]'s search-and-play / library-browse / queue sections - NOT for the
 * now-playing panel or transport, which read/control whatever media session is actually active
 * ([com.kevin.legion.media.NowPlayingController]) and need no Spotify connection at all: a
 * driver streaming from their phone over Bluetooth can already see and pause it here with zero
 * Spotify setup. Only "turn a typed name into something playable" needs the Web API grant.
 *
 * Reuses [SpotifyConnectResolver.Stage] wholesale rather than inventing a second
 * connected/not-connected vocabulary - it is the SAME three-stage state
 * [com.kevin.legion.ui.SpotifyScreen] already derives from the identical booleans, so "Spotify
 * isn't connected" reads in identical words everywhere in the app. Command-center ticket 04's
 * rule: that state must always NAME THE FIX (open Setup > Spotify), never render as a quietly
 * empty panel - CLAUDE.md's worded-state posture (the reconciliation gate's "say so in words"
 * clause) carried to a UI gate rather than a data one.
 *
 * Kept Android-free on purpose, same shape as [SpotifyConnectResolver] itself: a plain JVM unit
 * test, no Robolectric.
 */
object MediaSpotifyGateResolver {

    /** Whether search-and-play, library browse, and the queue read can run at all. */
    fun searchReady(stage: SpotifyConnectResolver.Stage): Boolean = stage == SpotifyConnectResolver.Stage.READY

    /**
     * The honest line for every non-[SpotifyConnectResolver.Stage.READY] stage. Callers must not
     * invoke this for [SpotifyConnectResolver.Stage.READY] - there is nothing to say, the section
     * should simply render - so it returns an empty string there rather than throwing: a caller
     * that forgets the [searchReady] check first fails visibly with a blank line, not a crash.
     */
    fun notReadyMessage(stage: SpotifyConnectResolver.Stage): String = when (stage) {
        SpotifyConnectResolver.Stage.READY -> ""
        SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID ->
            "Spotify isn't connected. Open Setup > Spotify to add your client ID and connect it."
        SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION ->
            "Spotify isn't finished connecting. Open Setup > Spotify and tap AUTHORIZE."
        SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION ->
            "Spotify needs re-approving. Open Setup > Spotify and tap AUTHORIZE again."
    }
}
