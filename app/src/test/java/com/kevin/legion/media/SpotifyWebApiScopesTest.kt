package com.kevin.legion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for [SpotifyWebApi.SCOPES] - ticket 01 of the spotify-voice map
 * (`.scratch/spotify-voice/issues/01-scopes-and-one-reapproval.md`), whose whole point is that
 * every scope the map will ever need is taken in ONE re-approval rather than one per ticket. A
 * later ticket silently forgetting to ask for a scope it needs is exactly the defect this ticket
 * exists to make impossible - this test is the guard that a future edit to [SpotifyWebApi.SCOPES]
 * cannot silently drop one of the nine scopes the map's settled decision table names, and cannot
 * introduce a duplicate or a typo'd scope string that [SpotifyWebApi.isAuthorized]'s exact-string
 * equality check would then treat as permanently stale.
 */
class SpotifyWebApiScopesTest {

    /** Every scope named in the map's settled decision table for ticket 01, plus the four pre-existing ones. */
    private val expectedScopes = setOf(
        // Pre-existing, from the 2026-08-12 and 2026-08-18 widenings.
        "user-read-private",
        "user-library-read",
        "user-read-recently-played",
        "user-top-read",
        // Added 2026-08-19, ticket 01 - every playback WRITE (tickets 04, 06).
        "user-modify-playback-state",
        // Device/player reads (ticket 07).
        "user-read-playback-state",
        // The queue read needs this AND user-read-playback-state.
        "user-read-currently-playing",
        // Like/unlike and follow-as-save (ticket 05).
        "user-library-modify",
        // His own and friend-shared playlists.
        "playlist-read-private",
        "playlist-read-collaborative",
        // Add-to-playlist (ticket 08). Playlist CREATION is deliberately out of scope.
        "playlist-modify-private",
        "playlist-modify-public",
        // App Remote (ticket 02).
        "app-remote-control",
    )

    @Test
    fun `SCOPES holds exactly the nine scopes ticket 01 added, plus the four pre-existing ones - no more, no fewer`() {
        val actual = SpotifyWebApi.SCOPES.split(" ").filter { it.isNotBlank() }.toSet()
        assertEquals(expectedScopes, actual)
    }

    @Test
    fun `SCOPES has no duplicate entries`() {
        val tokens = SpotifyWebApi.SCOPES.split(" ").filter { it.isNotBlank() }
        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun `SCOPES is space-separated with no stray whitespace, since Spotify parses it verbatim`() {
        // A leading/trailing/double space would not break Spotify's own parsing, but it WOULD
        // break isAuthorized's exact-string equality against a grant recorded with the clean
        // version, so this is a real regression guard, not just tidiness.
        assertTrue(SpotifyWebApi.SCOPES == SpotifyWebApi.SCOPES.trim())
        assertTrue(!SpotifyWebApi.SCOPES.contains("  "))
    }
}
