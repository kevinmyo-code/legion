package com.kevin.midnightai.service

import com.kevin.midnightai.media.SpotifyWebApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A library read's `count` is this page, not how many the driver has
 * (`music-suggestions-1124` P8, mirrored from the phone app so Moose and Alfred answer the same
 * way). Pure: both functions under test take JSON and return JSON.
 */
class SpotifyVoicePageResultTest {

    private fun rows(n: Int): List<JSONObject> = (1..n).map { JSONObject().put("name", "Row $it") }

    @Test
    fun `a page short of the stated total is marked truncated`() {
        val json = SpotifyVoice.pageResult(rows(5), total = 23, sourceLabel = "saved albums")
        assertEquals(5, json.getInt("count"))
        assertEquals(23, json.getInt("total"))
        assertTrue(json.getBoolean("truncated"))
    }

    @Test
    fun `a page that is the whole library says so`() {
        val json = SpotifyVoice.pageResult(rows(3), total = 3, sourceLabel = "top tracks")
        assertFalse(json.getBoolean("truncated"))
    }

    @Test
    fun `a response that stated no total emits neither total nor truncated`() {
        val json = SpotifyVoice.pageResult(rows(5), total = null, sourceLabel = "recently played")
        assertFalse("no total means no total", json.has("total"))
        assertFalse("and never a truncated flag standing in for one", json.has("truncated"))
    }

    @Test
    fun `zero rows is a real answer, not a failure`() {
        val json = SpotifyVoice.pageResult(emptyList(), total = 0, sourceLabel = "saved albums")
        assertTrue(json.getBoolean("success"))
        assertTrue(json.getString("message").contains("no saved albums"))
    }

    @Test
    fun `the offset-paged sources carry Spotify's own total and the rest carry none`() {
        val paged = JSONObject("""{"total": 23, "items": []}""")
        assertEquals(23, SpotifyWebApi.savedAlbumsPage(paged).total)
        assertEquals(23, SpotifyWebApi.topArtistsPage(paged).total)
        assertEquals(23, SpotifyWebApi.topTracksPage(paged).total)
        // The queue is not paged, and an artist's own figure counts market and edition duplicates
        // that the parser collapses, so neither is a figure about what came back.
        assertEquals(null, SpotifyWebApi.queuePage(JSONObject("""{"queue": [], "total": 99}""")).total)
        assertEquals(null, SpotifyWebApi.artistAlbumsPage(JSONObject("""{"items": [], "total": 99}""")).total)
        assertEquals(null, SpotifyWebApi.savedAlbumsPage(JSONObject("""{"items": []}""")).total)
        // Recently-played is cursor-paged: its documented total does not count what the cursor
        // returned, so it is dropped at the reader rather than passed on to be contradicted.
        assertEquals(null, SpotifyWebApi.recentlyPlayedPage(JSONObject("""{"items": [], "total": 50}""")).total)
    }

    @Test
    fun `a total smaller than the page in hand is never published`() {
        // A 0 beside five rows would reach the model as a stated library figure of zero AND
        // `truncated: false`, both contradicted by the rows under them (phone parity).
        val json = SpotifyVoice.pageResult(rows(5), total = 0, sourceLabel = "recently played")
        assertEquals(5, json.getInt("count"))
        assertFalse("a library of zero beside five rows is not a figure to state", json.has("total"))
        assertFalse("and never truncated false standing in for \"that is all of them\"", json.has("truncated"))
        val short = SpotifyVoice.pageResult(rows(5), total = 3, sourceLabel = "recently played")
        assertFalse(short.has("total"))
        assertFalse(short.has("truncated"))
    }
}
