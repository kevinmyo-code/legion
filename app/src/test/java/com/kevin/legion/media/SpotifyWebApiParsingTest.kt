package com.kevin.legion.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for [SpotifyWebApi]'s pure JSON-parsing functions (ticket 05,
 * `.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`) - no network, no
 * Robolectric, org.json is a plain test dependency (`app/build.gradle.kts`), same shape as the
 * ledger parsers' own pure-function tests.
 */
class SpotifyWebApiParsingTest {

    // ------------------------------------------------------------ saved albums

    @Test
    fun `parseSavedAlbums reads name, first artist, and uri from each item's nested album`() {
        val json = JSONObject(
            """
            {"items": [
                {"album": {"name": "Discovery", "uri": "spotify:album:abc",
                    "artists": [{"name": "Daft Punk"}, {"name": "Someone Else"}]}},
                {"album": {"name": "Random Access Memories", "uri": "spotify:album:def",
                    "artists": [{"name": "Daft Punk"}]}}
            ]}
            """.trimIndent(),
        )
        val albums = SpotifyWebApi.parseSavedAlbums(json)
        assertEquals(2, albums.size)
        assertEquals(SavedAlbum("Discovery", "Daft Punk", "spotify:album:abc"), albums[0])
        assertEquals(SavedAlbum("Random Access Memories", "Daft Punk", "spotify:album:def"), albums[1])
    }

    @Test
    fun `parseSavedAlbums skips an item with no album name rather than throwing`() {
        val json = JSONObject("""{"items": [{"album": {"uri": "spotify:album:abc", "artists": []}}]}""")
        assertTrue(SpotifyWebApi.parseSavedAlbums(json).isEmpty())
    }

    @Test
    fun `parseSavedAlbums on a missing items array returns empty, not a throw`() {
        assertTrue(SpotifyWebApi.parseSavedAlbums(JSONObject("{}")).isEmpty())
    }

    // ------------------------------------------------------------ recently played

    @Test
    fun `parseRecentlyPlayed reads the nested track and the item's own played_at`() {
        val json = JSONObject(
            """
            {"items": [
                {"played_at": "2026-08-18T10:00:00Z",
                 "track": {"name": "Plastic Love", "artists": [{"name": "Mariya Takeuchi"}]}}
            ]}
            """.trimIndent(),
        )
        val plays = SpotifyWebApi.parseRecentlyPlayed(json)
        assertEquals(1, plays.size)
        assertEquals(RecentlyPlayedTrack("Plastic Love", "Mariya Takeuchi", "2026-08-18T10:00:00Z"), plays[0])
    }

    @Test
    fun `parseRecentlyPlayed skips an item with no nested track`() {
        val json = JSONObject("""{"items": [{"played_at": "2026-08-18T10:00:00Z"}]}""")
        assertTrue(SpotifyWebApi.parseRecentlyPlayed(json).isEmpty())
    }

    // ------------------------------------------------------------ top artists / tracks

    @Test
    fun `parseTopArtists reads a flat list of artist names`() {
        val json = JSONObject("""{"items": [{"name": "Tame Impala"}, {"name": "Mac DeMarco"}]}""")
        assertEquals(listOf(TopArtist("Tame Impala"), TopArtist("Mac DeMarco")), SpotifyWebApi.parseTopArtists(json))
    }

    @Test
    fun `parseTopArtists skips a blank name rather than emitting an empty entry`() {
        val json = JSONObject("""{"items": [{"name": ""}, {"name": "Real Artist"}]}""")
        assertEquals(listOf(TopArtist("Real Artist")), SpotifyWebApi.parseTopArtists(json))
    }

    @Test
    fun `parseTopTracks reads name and first artist from each track object`() {
        val json = JSONObject(
            """{"items": [{"name": "The Less I Know The Better", "artists": [{"name": "Tame Impala"}]}]}""",
        )
        assertEquals(listOf(TopTrack("The Less I Know The Better", "Tame Impala")), SpotifyWebApi.parseTopTracks(json))
    }

    // ------------------------------------------------------------ queue (ticket 04)

    @Test
    fun `parseQueue reads bare track objects from the top-level queue array, not nested under track`() {
        // Unlike recently-played, GET /v1/me/player/queue's "queue" entries ARE the track
        // objects - there is no wrapping "track" key, which is exactly the shape difference
        // this test guards against a copy-paste of parseRecentlyPlayed's nesting.
        val json = JSONObject(
            """
            {"currently_playing": {"name": "Not This One"},
             "queue": [
                {"name": "Plastic Love", "artists": [{"name": "Mariya Takeuchi"}]},
                {"name": "Midnight City", "artists": [{"name": "M83"}]}
             ]}
            """.trimIndent(),
        )
        val queue = SpotifyWebApi.parseQueue(json)
        assertEquals(2, queue.size)
        assertEquals(QueuedTrack("Plastic Love", "Mariya Takeuchi"), queue[0])
        assertEquals(QueuedTrack("Midnight City", "M83"), queue[1])
    }

    @Test
    fun `parseQueue skips an entry with no name rather than throwing`() {
        val json = JSONObject("""{"queue": [{"artists": [{"name": "Nobody"}]}]}""")
        assertTrue(SpotifyWebApi.parseQueue(json).isEmpty())
    }

    @Test
    fun `parseQueue on a missing queue array returns empty, not a throw`() {
        assertTrue(SpotifyWebApi.parseQueue(JSONObject("""{"currently_playing": {"name": "X"}}""")).isEmpty())
    }
}
