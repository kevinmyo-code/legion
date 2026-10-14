package com.kevin.midnightai.media

/**
 * The ONE list of Web API scopes the app asks Spotify for, and the pure check of a stored grant
 * against it. Pure and Android-free so it runs in plain JVM tests.
 *
 * **Why everything is asked for in one edit.** A refresh token only ever mints access tokens
 * scoped to what it was issued with, so every scope added later forces the driver through the
 * browser consent again - and discovering that mid-drive is the worst place to learn it. So the
 * voice music tools (queue read, library reads, recently-played, top lists, playback state) and
 * the Cruise playlist row share this one set, and [anyMissing] is the single "reconnect" test
 * for ALL of them: an old token trips it once, one approval fixes everything.
 *
 * Each scope's reason (so a future trim knows what it would break):
 *  - `user-read-private`: the search floor. Legion measured `403 Insufficient client scope` on a
 *    user token with NO scopes, and playlists alone may not be enough on every account.
 *  - `user-library-read`: `browse_my_music` saved_albums.
 *  - `user-read-recently-played`: `browse_my_music` recently_played.
 *  - `user-top-read`: `browse_my_music` top_artists / top_tracks.
 *  - `user-read-playback-state`, `user-read-currently-playing`: `GET /me/player/queue` needs BOTH.
 *  - `user-modify-playback-state`, `user-library-modify`, `app-remote-control`: every WRITE the
 *    voice tools make (queue, shuffle, repeat, seek, like, follow) goes through App Remote, which
 *    runs its own consent; they are requested here too because legion ships them in the same
 *    set and a stale grant missing them is the failure mode we are buying out of. Not verified
 *    on this device that App Remote's own grant is independent of them.
 *  - `playlist-read-private`, `playlist-read-collaborative`: the Cruise playlist row and
 *    play-playlist-by-name against the driver's own library.
 *
 * Deliberately NOT asked for: `playlist-modify-*` (no add-to-playlist tool is ported). Adding that
 * tool later costs one more re-approval; that is the price of not asking for write access nothing
 * uses.
 */
object SpotifyScopes {
    /** The space-delimited string sent as the authorize request's `scope`. */
    const val ALL = "user-read-private user-library-read user-read-recently-played user-top-read " +
        "user-modify-playback-state user-read-playback-state user-read-currently-playing " +
        "user-library-modify playlist-read-private playlist-read-collaborative app-remote-control"

    /** Every scope in [ALL], in order: what the authorize request asks for. */
    val REQUESTED: List<String> = ALL.split(' ').filter { it.isNotBlank() }

    /**
     * Scopes that are App Remote grants, not Web API ones. Spotify's token response may not echo
     * them back, so [anyMissing] must not hold them against the stored grant - otherwise the
     * "Reconnect Spotify" prompt sticks forever, every consent producing the same token again.
     */
    private val NOT_ECHOED_IN_TOKEN = setOf("app-remote-control")

    /** What a stored Web API grant must contain: [REQUESTED] minus [NOT_ECHOED_IN_TOKEN]. */
    val REQUIRED: List<String> = REQUESTED.filter { it !in NOT_ECHOED_IN_TOKEN }

    private fun parse(granted: String): Set<String> =
        granted.split(' ', ',').filter { it.isNotBlank() }.toSet()

    /** Required (token-echoed) scopes absent from [granted], in [REQUIRED] order. A blank [granted] misses all of them. */
    fun missing(granted: String): List<String> {
        val have = parse(granted)
        return REQUIRED.filter { it !in have }
    }

    /**
     * True when the grant lacks ANY required scope. A blank string is a token stored before this
     * app recorded scopes (and before it asked for any beyond playlists), so it certainly lacks
     * some - treating blank as "unknown, just try" would hide the reconnect from exactly the
     * drivers who need it.
     */
    fun anyMissing(granted: String): Boolean = missing(granted).isNotEmpty()
}
