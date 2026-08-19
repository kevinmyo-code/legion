package com.kevin.legion.media

import com.spotify.protocol.types.Album
import com.spotify.protocol.types.Artist
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [NowPlayingController.shouldLogHistoryEntry] - the dedup rule behind LEGION's own
 * listening history (ticket 05,
 * `.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`). The function
 * itself is pure logic with no Android dependency, but merely REFERENCING the
 * [NowPlayingController] object loads its class, and that object's `mainHandler` property is
 * initialized eagerly with `Handler(Looper.getMainLooper())` - a stub that throws on a plain JVM
 * runner (same shape as [com.kevin.legion.ai.SubAgentPartsTest]'s `android.util.Base64` note).
 * Robolectric shadows it instead of merely making this function's OWN body callable.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingControllerHistoryTest {

    private fun track(title: String, artist: String = "Artist", album: String = "Album", loggedAt: Long = 0L) =
        NowPlayingController.LoggedTrack(title, artist, album, loggedAt)

    @Test
    fun `a first observation with no prior log always logs`() {
        assertTrue(NowPlayingController.shouldLogHistoryEntry(null, track("Song A", loggedAt = 1_000L)))
    }

    @Test
    fun `a different title always logs, regardless of timing`() {
        val last = track("Song A", loggedAt = 1_000L)
        val candidate = track("Song B", loggedAt = 1_001L) // one ms later - well inside the dedup window
        assertTrue(NowPlayingController.shouldLogHistoryEntry(last, candidate))
    }

    @Test
    fun `a different artist on the same title counts as a different track`() {
        val last = track("Song A", artist = "Artist One", loggedAt = 1_000L)
        val candidate = track("Song A", artist = "Artist Two", loggedAt = 1_001L)
        assertTrue(NowPlayingController.shouldLogHistoryEntry(last, candidate))
    }

    @Test
    fun `the identical track re-observed inside the dedup window does not log again`() {
        val last = track("Song A", loggedAt = 1_000L)
        val candidate = track("Song A", loggedAt = 1_000L + NowPlayingController.PLAY_HISTORY_DEDUP_WINDOW_MS - 1)
        assertFalse(NowPlayingController.shouldLogHistoryEntry(last, candidate))
    }

    @Test
    fun `the identical track re-observed exactly at the window boundary logs again`() {
        val last = track("Song A", loggedAt = 1_000L)
        val candidate = track("Song A", loggedAt = 1_000L + NowPlayingController.PLAY_HISTORY_DEDUP_WINDOW_MS)
        assertTrue(NowPlayingController.shouldLogHistoryEntry(last, candidate))
    }

    @Test
    fun `the identical track re-observed well after the window is a genuine new listen`() {
        val last = track("Song A", loggedAt = 1_000L)
        val candidate = track("Song A", loggedAt = 1_000L + NowPlayingController.PLAY_HISTORY_DEDUP_WINDOW_MS + 60_000L)
        assertTrue(NowPlayingController.shouldLogHistoryEntry(last, candidate))
    }

    // --- resolveSpotifyUri (ticket 09, .scratch/spotify-voice/issues/09-history-uri.md) -------
    //
    // Which source wins, spelled out as tests: only a Spotify-sourced MediaSession observation
    // whose title AND artist match App Remote's own player-state track gets a URI attached.
    // Anything else - a non-Spotify package, no player state at all, or a title/artist mismatch
    // (App Remote's push landing one track behind or ahead of the MediaSession callback) - must
    // come back null, never a guess.

    private fun spotifyTrack(name: String, artistName: String, uri: String = "spotify:track:abc123") = Track(
        Artist(artistName, "spotify:artist:xyz"),
        listOf(Artist(artistName, "spotify:artist:xyz")),
        Album("Some Album", "spotify:album:def456"),
        200_000L,
        name,
        uri,
        ImageUri("spotify:image:ghi789"),
        false,
        false,
    )

    @Test
    fun `a matching Spotify-sourced observation gets the App Remote track's URI`() {
        val candidate = track("Song A", artist = "Artist One")
        val uri = NowPlayingController.resolveSpotifyUri(
            "com.spotify.music", candidate, spotifyTrack("Song A", "Artist One", "spotify:track:matched"),
        )
        assertEquals("spotify:track:matched", uri)
    }

    @Test
    fun `a non-Spotify source never gets a URI, even if title and artist happen to match`() {
        val candidate = track("Song A", artist = "Artist One")
        val uri = NowPlayingController.resolveSpotifyUri(
            "com.some.other.app", candidate, spotifyTrack("Song A", "Artist One"),
        )
        assertNull(uri)
    }

    @Test
    fun `no App Remote player state at all means no URI`() {
        val candidate = track("Song A", artist = "Artist One")
        assertNull(NowPlayingController.resolveSpotifyUri("com.spotify.music", candidate, null))
    }

    @Test
    fun `a title mismatch against a stale App Remote track never attaches its URI`() {
        val candidate = track("Song A", artist = "Artist One")
        val uri = NowPlayingController.resolveSpotifyUri(
            "com.spotify.music", candidate, spotifyTrack("A Completely Different Song", "Artist One"),
        )
        assertNull(uri)
    }

    @Test
    fun `an artist mismatch against a stale App Remote track never attaches its URI`() {
        val candidate = track("Song A", artist = "Artist One")
        val uri = NowPlayingController.resolveSpotifyUri(
            "com.spotify.music", candidate, spotifyTrack("Song A", "A Different Artist"),
        )
        assertNull(uri)
    }
}
