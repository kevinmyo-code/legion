package com.kevin.legion.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for ticket 08's pure playlist functions -
 * `.scratch/spotify-voice/issues/08-playlists-by-name.md`: parsing `/me/playlists` pages into
 * [SpotifyPlaylist], the `readable` (owned-or-collaborative) boundary, and [SpotifyWebApi]'s
 * fuzzy name matcher. No network, no Robolectric - same shape as [SpotifyWebApiParsingTest].
 */
class SpotifyPlaylistMatchTest {

    // ------------------------------------------------------------ parsePlaylistsPage / readable

    @Test
    fun `parsePlaylistsPage marks a playlist owned by the current user as readable`() {
        val json = JSONObject(
            """
            {"items": [
                {"name": "Roadtrip", "uri": "spotify:playlist:1", "id": "1", "collaborative": false,
                 "owner": {"id": "kevin"}}
            ]}
            """.trimIndent(),
        )
        val playlists = SpotifyWebApi.parsePlaylistsPage(json, userId = "kevin")
        assertEquals(1, playlists.size)
        assertTrue(playlists[0].readable)
    }

    @Test
    fun `parsePlaylistsPage marks a collaborative playlist owned by someone else as readable`() {
        val json = JSONObject(
            """
            {"items": [
                {"name": "Friends Mix", "uri": "spotify:playlist:2", "id": "2", "collaborative": true,
                 "owner": {"id": "someone_else"}}
            ]}
            """.trimIndent(),
        )
        val playlists = SpotifyWebApi.parsePlaylistsPage(json, userId = "kevin")
        assertTrue(playlists[0].readable)
    }

    @Test
    fun `parsePlaylistsPage marks a followed editorial playlist as NOT readable`() {
        // Discover Weekly's shape: owned by Spotify, not collaborative, still shows up in
        // /me/playlists because the driver follows it. This is the exact case ticket 08's
        // "unreadable, never silently skipped" requirement exists for.
        val json = JSONObject(
            """
            {"items": [
                {"name": "Discover Weekly", "uri": "spotify:playlist:37i9dQZEVXcJZyENOWUFo7",
                 "id": "37i9dQZEVXcJZyENOWUFo7", "collaborative": false,
                 "owner": {"id": "spotify"}}
            ]}
            """.trimIndent(),
        )
        val playlists = SpotifyWebApi.parsePlaylistsPage(json, userId = "kevin")
        assertEquals(1, playlists.size)
        assertTrue(!playlists[0].readable)
    }

    @Test
    fun `parsePlaylistsPage falls back to the collaborative flag alone when userId is unknown`() {
        val json = JSONObject(
            """
            {"items": [
                {"name": "Mine", "uri": "spotify:playlist:1", "id": "1", "collaborative": false,
                 "owner": {"id": "kevin"}},
                {"name": "Shared", "uri": "spotify:playlist:2", "id": "2", "collaborative": true,
                 "owner": {"id": "friend"}}
            ]}
            """.trimIndent(),
        )
        val playlists = SpotifyWebApi.parsePlaylistsPage(json, userId = null)
        // Can't prove ownership without a user id - never a false positive, only a possible
        // false negative (see SpotifyPlaylist's own doc comment).
        assertTrue(!playlists[0].readable)
        assertTrue(playlists[1].readable)
    }

    @Test
    fun `parsePlaylistsPage skips an item with no name or no uri rather than throwing`() {
        val json = JSONObject(
            """{"items": [{"uri": "spotify:playlist:1", "id": "1"}, {"name": "No URI", "id": "2"}]}""",
        )
        assertTrue(SpotifyWebApi.parsePlaylistsPage(json, userId = "kevin").isEmpty())
    }

    @Test
    fun `parsePlaylistsPage on a missing items array returns empty, not a throw`() {
        assertTrue(SpotifyWebApi.parsePlaylistsPage(JSONObject("{}"), userId = "kevin").isEmpty())
    }

    // ------------------------------------------------------------ playlistMatchScore / bestPlaylistMatch

    private fun playlist(name: String, readable: Boolean = true) =
        SpotifyPlaylist(name = name, uri = "spotify:playlist:${name.hashCode()}", id = name.hashCode().toString(), readable = readable)

    @Test
    fun `playlistMatchScore is 1_0 for an exact case-insensitive match`() {
        assertEquals(1.0, SpotifyWebApi.playlistMatchScore("roadtrip", "Roadtrip"), 0.0001)
        assertEquals(1.0, SpotifyWebApi.playlistMatchScore("ROADTRIP", "roadtrip"), 0.0001)
    }

    @Test
    fun `playlistMatchScore ignores punctuation and extra whitespace`() {
        assertEquals(1.0, SpotifyWebApi.playlistMatchScore("kev's roadtrip", "Kev's  Roadtrip!"), 0.0001)
    }

    @Test
    fun `playlistMatchScore scores a multi-word name containing the spoken words highly`() {
        val score = SpotifyWebApi.playlistMatchScore("roadtrip", "Summer Roadtrip 2024")
        assertTrue("expected containment score >= 0.9 but was $score", score >= 0.9)
    }

    @Test
    fun `playlistMatchScore is near zero for two unrelated short names`() {
        val score = SpotifyWebApi.playlistMatchScore("Gym", "Chill")
        assertTrue("expected a low score for unrelated names but was $score", score < SpotifyWebApi.PLAYLIST_MATCH_THRESHOLD)
    }

    @Test
    fun `playlistMatchScore tolerates a single typo in a longer name`() {
        val score = SpotifyWebApi.playlistMatchScore("roadtrp", "roadtrip")
        assertTrue("expected a near-miss score >= threshold but was $score", score >= SpotifyWebApi.PLAYLIST_MATCH_THRESHOLD)
    }

    @Test
    fun `bestPlaylistMatch picks the closest name among several candidates`() {
        val playlists = listOf(playlist("Gym"), playlist("Roadtrip"), playlist("Chill Vibes"))
        val match = SpotifyWebApi.bestPlaylistMatch(playlists, "road trip")
        assertEquals("Roadtrip", match?.name)
    }

    @Test
    fun `bestPlaylistMatch returns null when nothing clears the threshold`() {
        val playlists = listOf(playlist("Gym"), playlist("Chill Vibes"))
        assertNull(SpotifyWebApi.bestPlaylistMatch(playlists, "Discover Weekly"))
    }

    @Test
    fun `bestPlaylistMatch returns null on an empty library or a blank spoken name`() {
        assertNull(SpotifyWebApi.bestPlaylistMatch(emptyList(), "Roadtrip"))
        assertNull(SpotifyWebApi.bestPlaylistMatch(listOf(playlist("Roadtrip")), ""))
    }

    @Test
    fun `bestPlaylistMatch does not care whether the matched playlist is readable`() {
        // Matching and readability are separate questions (matching is "which playlist did the
        // driver mean", readability is "can LEGION act on it") - bestPlaylistMatch answers only
        // the first, on purpose, so it stays reusable for a future read-only lookup too.
        val playlists = listOf(playlist("Discover Weekly", readable = false))
        val match = SpotifyWebApi.bestPlaylistMatch(playlists, "discover weekly")
        assertNotNull(match)
        assertTrue(!match!!.readable)
    }

    // ------------------------------------------------------------ levenshtein

    @Test
    fun `levenshtein of identical strings is zero`() {
        assertEquals(0, SpotifyWebApi.levenshtein("roadtrip", "roadtrip"))
    }

    @Test
    fun `levenshtein counts a single substitution as distance one`() {
        assertEquals(1, SpotifyWebApi.levenshtein("roadtrip", "roadtrup"))
    }

    @Test
    fun `levenshtein handles an empty string as the length of the other`() {
        assertEquals(5, SpotifyWebApi.levenshtein("", "abcde"))
        assertEquals(5, SpotifyWebApi.levenshtein("abcde", ""))
    }
}
