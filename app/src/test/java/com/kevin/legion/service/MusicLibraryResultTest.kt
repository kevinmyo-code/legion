package com.kevin.legion.service

import com.kevin.legion.media.SpotifyPlaylist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a `browse_my_music` result may and may not let the model say
 * (`music-suggestions-1124` P7, P8).
 *
 * Both functions under test are pure, so this is a plain JVM test: `org.json` is a test
 * dependency and neither one touches a Context, Spotify or Room.
 */
class MusicLibraryResultTest {

    private fun rows(n: Int): List<JSONObject> = (1..n).map { JSONObject().put("name", "Row $it") }

    private fun playlists(vararg names: String): List<SpotifyPlaylist> =
        names.mapIndexed { i, n ->
            SpotifyPlaylist(name = n, uri = "spotify:playlist:p$i", id = "p$i", readable = true)
        }

    // ------------------------------------------------------------ the page versus the library

    @Test
    fun `a page short of the stated total is marked truncated`() {
        val json = MusicLibraryToolbox.pageResult(rows(5), total = 23, sourceLabel = "saved albums")
        assertEquals(5, json.getInt("count"))
        assertEquals(23, json.getInt("total"))
        assertTrue("five of twenty-three is a page, and the result has to say so", json.getBoolean("truncated"))
    }

    @Test
    fun `a page that is the whole library says so`() {
        val json = MusicLibraryToolbox.pageResult(rows(3), total = 3, sourceLabel = "saved albums")
        assertEquals(3, json.getInt("total"))
        assertFalse(json.getBoolean("truncated"))
    }

    @Test
    fun `a response that stated no total emits neither total nor truncated`() {
        // The whole point: `truncated: false` with no total behind it is a claim that this is
        // everything, made by a read that cannot know.
        val json = MusicLibraryToolbox.pageResult(rows(5), total = null, sourceLabel = "recently played")
        assertEquals(5, json.getInt("count"))
        assertFalse("no total means no total", json.has("total"))
        assertFalse("and never a truncated flag standing in for one", json.has("truncated"))
    }

    @Test
    fun `a total smaller than the page in hand is never published`() {
        // Spotify's cursor-paged bodies carry a `total` that does not count what the cursor
        // returned, and a 0 beside five rows would reach the model as a stated library figure of
        // zero AND `truncated: false`, both contradicted by the rows under them.
        val json = MusicLibraryToolbox.pageResult(rows(5), total = 0, sourceLabel = "recently played")
        assertEquals(5, json.getInt("count"))
        assertFalse("a library of zero beside five rows is not a figure to state", json.has("total"))
        assertFalse("and never truncated false standing in for \"that is all of them\"", json.has("truncated"))
        // Any total under the row count is the same contradiction, not just zero.
        val short = MusicLibraryToolbox.pageResult(rows(5), total = 3, sourceLabel = "recently played")
        assertFalse(short.has("total"))
        assertFalse(short.has("truncated"))
    }

    @Test
    fun `zero rows is a real answer, not a failure`() {
        val json = MusicLibraryToolbox.pageResult(emptyList(), total = 0, sourceLabel = "liked songs")
        assertTrue(json.getBoolean("success"))
        assertEquals(0, json.getInt("count"))
        assertTrue(json.getString("message").contains("no liked songs"))
    }

    // ------------------------------------------------------------ the named playlist lookup

    @Test
    fun `a named lookup names its match and keeps every row`() {
        val library = playlists(
            "Monday Mornings", "Kitchen Radio", "Long Drive", "Rain Window", "Gym Floor",
            "Slow Sunday", "Night Shift", "Garden Hour", "Tape Deck Nine", "Winter Light",
            "Open Road", "Last Train",
        )
        val rows = library.map(MusicLibraryToolbox::playlistRow)
        val json = MusicLibraryToolbox.pageResult(rows, library.size, "playlists")
        // Row nine, well past the old five-row page: the match must come from the whole list.
        val withMatch = MusicLibraryToolbox.withPlaylistMatch(json, library, "tape deck nine")
        assertEquals("Tape Deck Nine", withMatch.getString("match"))
        assertEquals(12, withMatch.getInt("count"))
        assertEquals(12, withMatch.getJSONArray("items").length())
        assertFalse(withMatch.has("matchNote"))
    }

    @Test
    fun `a name nothing matches says so and still lists what he has`() {
        val library = playlists("Monday Mornings", "Kitchen Radio")
        val rows = library.map(MusicLibraryToolbox::playlistRow)
        val json = MusicLibraryToolbox.pageResult(rows, library.size, "playlists")
        val missed = MusicLibraryToolbox.withPlaylistMatch(json, library, "harpsichord experiments")
        assertFalse(missed.has("match"))
        assertTrue(missed.getString("matchNote").contains("never that he has no playlists"))
        assertEquals("the list is still the answer to what he does have", 2, missed.getInt("count"))
    }

    @Test
    fun `a playlist row carries the uri play_music needs`() {
        val row = MusicLibraryToolbox.playlistRow(playlists("Kitchen Radio").single())
        assertEquals("Kitchen Radio", row.getString("name"))
        assertEquals("spotify:playlist:p0", row.getString("spotifyUri"))
        assertTrue(row.getBoolean("ownOrCollaborative"))
    }
}
