package com.kevin.midnightai.media

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pure parts of the Cruise playlist row: response parsing, cover choice, status/scope
 * classification, and the real request path against a MockWebServer (no Android types, so
 * plain JVM).
 */
class SpotifyPlaylistsTest {

    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun images(vararg wh: Pair<Int?, String>) = JSONArray().also { arr ->
        wh.forEach { (w, url) ->
            arr.put(org.json.JSONObject().put("url", url).put("width", w ?: org.json.JSONObject.NULL))
        }
    }

    @Test fun coverPicksSmallestAtLeast200() {
        val a = images(640 to "big", 300 to "mid", 60 to "tiny")
        assertEquals("mid", SpotifyPlaylistLogic.pickCoverUrl(a))
    }

    @Test fun coverFallsBackToLargestWhenAllSmall() {
        assertEquals("b", SpotifyPlaylistLogic.pickCoverUrl(images(60 to "a", 150 to "b")))
    }

    @Test fun coverUsesFirstWhenSizesNull() {
        assertEquals("first", SpotifyPlaylistLogic.pickCoverUrl(images(null to "first", null to "second")))
    }

    @Test fun coverNullForMissingOrEmpty() {
        assertNull(SpotifyPlaylistLogic.pickCoverUrl(null))
        assertNull(SpotifyPlaylistLogic.pickCoverUrl(JSONArray()))
    }

    @Test fun parseToleratesNullEntriesAndMissingImages() {
        val body = """
            {"items":[
              null,
              {"id":"p1","name":"Night Drive","uri":"spotify:playlist:p1","images":[{"url":"u1","width":640,"height":640}]},
              {"id":"p2","name":"","images":null},
              {"name":"no id"}
            ]}
        """.trimIndent()
        val out = SpotifyPlaylistLogic.parsePlaylists(body)
        assertEquals(2, out.size)
        assertEquals("Night Drive", out[0].name)
        assertEquals("u1", out[0].coverUrl)
        assertEquals("Playlist", out[1].name)
        assertEquals("spotify:playlist:p2", out[1].uri)
        assertNull(out[1].coverUrl)
    }

    @Test fun parseCapsAtFive() {
        val items = (1..8).joinToString(",") { """{"id":"p$it","name":"n$it"}""" }
        assertEquals(5, SpotifyPlaylistLogic.parsePlaylists("""{"items":[$items]}""", max = 5).size)
    }

    @Test fun parseEmptyItems() {
        assertTrue(SpotifyPlaylistLogic.parsePlaylists("""{"items":[]}""").isEmpty())
        assertTrue(SpotifyPlaylistLogic.parsePlaylists("{}").isEmpty())
    }

    @Test fun statusClassification() {
        assertEquals(true, SpotifyPlaylistLogic.classifyStatus(200))
        assertEquals(false, SpotifyPlaylistLogic.classifyStatus(401))
        assertEquals(false, SpotifyPlaylistLogic.classifyStatus(403))
        assertNull(SpotifyPlaylistLogic.classifyStatus(429))
        assertNull(SpotifyPlaylistLogic.classifyStatus(503))
    }

    @Test fun playlistScopeMissingOnlyLooksAtTheScopeThatListsPlaylists() {
        // A grant with the playlist scope but none of the newer ones still lists playlists (the
        // row keeps refreshing) even though scopeMissing says the full set needs a reconnect.
        val old = "playlist-read-private playlist-read-collaborative"
        assertFalse(SpotifyPlaylistLogic.playlistScopeMissing(old))
        assertTrue(SpotifyPlaylistLogic.scopeMissing(old))
        assertTrue(SpotifyPlaylistLogic.playlistScopeMissing(""))
        assertTrue(SpotifyPlaylistLogic.playlistScopeMissing("user-top-read"))
        assertFalse(SpotifyPlaylistLogic.playlistScopeMissing(SpotifyScopes.ALL))
    }

    @Test fun scopeMissingWhenAbsentOrNeverRecorded() {
        assertTrue(SpotifyPlaylistLogic.scopeMissing(""))
        assertTrue(SpotifyPlaylistLogic.scopeMissing("user-read-email"))
        // The playlist pair alone is no longer enough: one reconnect covers the whole scope set.
        assertTrue(SpotifyPlaylistLogic.scopeMissing("playlist-read-private playlist-read-collaborative"))
        assertTrue(SpotifyPlaylistLogic.scopeMissing("user-read-email playlist-read-private"))
        assertFalse(SpotifyPlaylistLogic.scopeMissing(SpotifyScopes.ALL))
    }

    @Test fun parseReadsTrackCountFromTracksOrItemsTotal() {
        val body = """
            {"items":[
              {"id":"a","name":"A","tracks":{"href":"h","total":42}},
              {"id":"b","name":"B","items":{"href":"h","total":7}},
              {"id":"c","name":"C"},
              {"id":"d","name":"D","tracks":{"total":null}},
              {"id":"e","name":"E","tracks":{"total":0}}
            ]}
        """.trimIndent()
        val out = SpotifyPlaylistLogic.parsePlaylists(body)
        assertEquals(listOf(42, 7, null, null, 0), out.map { it.trackCount })
    }

    @Test fun cacheRoundTrip() {
        val list = listOf(
            SpotifyPlaylist("a", "A", "spotify:playlist:a", "http://x/a", trackCount = 58),
            SpotifyPlaylist("b", "B", "spotify:playlist:b", null),
        )
        assertEquals(list, SpotifyPlaylistLogic.fromJson(SpotifyPlaylistLogic.toJson(list)))
        assertTrue(SpotifyPlaylistLogic.fromJson("not json").isEmpty())
        // A cache written before track counts existed still loads, with no count.
        val legacy = """[{"id":"a","name":"A","uri":"spotify:playlist:a","coverUrl":null}]"""
        assertNull(SpotifyPlaylistLogic.fromJson(legacy).single().trackCount)
    }

    @Test fun sampleSizeKeepsAtLeastTarget() {
        assertEquals(1, SpotifyPlaylistLogic.sampleSizeFor(300, 200))
        assertEquals(2, SpotifyPlaylistLogic.sampleSizeFor(640, 200))
        assertEquals(1, SpotifyPlaylistLogic.sampleSizeFor(0, 200))
    }

    @Test fun fetchSendsBearerAndLimitAndParses() {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"p1","name":"One"}]}"""))
        val r = SpotifyPlaylists.fetch(OkHttpClient(), server.url("/").toString().trimEnd('/'), "tok")
        assertTrue(r is PlaylistFetch.Ok)
        assertEquals(1, (r as PlaylistFetch.Ok).playlists.size)
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("/v1/me/playlists?limit=50&offset=0", req.path)
    }

    @Test fun fetch403And401MeanReconnect() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(401))
        val base = server.url("/").toString().trimEnd('/')
        assertEquals(PlaylistFetch.NeedsReconnect, SpotifyPlaylists.fetch(OkHttpClient(), base, "t"))
        assertEquals(PlaylistFetch.NeedsReconnect, SpotifyPlaylists.fetch(OkHttpClient(), base, "t"))
    }

    @Test fun emptyOkListGetsAnExplanationAndANonEmptyOneDoesNot() {
        assertEquals("Spotify returned no playlists for this account.", SpotifyPlaylistLogic.emptyListNote(emptyList()))
        assertNull(SpotifyPlaylistLogic.emptyListNote(listOf(SpotifyPlaylist("id", "n", "spotify:playlist:id", null))))
    }

    @Test fun fetch500AndOfflineAreFailedNotReconnect() {
        server.enqueue(MockResponse().setResponseCode(500))
        val base = server.url("/").toString().trimEnd('/')
        assertTrue(SpotifyPlaylists.fetch(OkHttpClient(), base, "t") is PlaylistFetch.Failed)
        server.shutdown()
        assertTrue(SpotifyPlaylists.fetch(OkHttpClient(), base, "t") is PlaylistFetch.Failed)
    }

    @Test fun fetchAllPagesUntilNoNext() {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","name":"A"}],"next":"https://x/next"}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"b","name":"B"}],"next":null}"""))
        val r = SpotifyPlaylists.fetchAll(OkHttpClient(), server.url("/").toString().trimEnd('/'), "t")
        assertEquals(listOf("a", "b"), (r as PlaylistFetch.Ok).playlists.map { it.id })
        assertEquals("/v1/me/playlists?limit=50&offset=0", server.takeRequest().path)
        assertEquals("/v1/me/playlists?limit=50&offset=50", server.takeRequest().path)
    }

    @Test fun fetchAllKeepsEarlierPagesIfLaterPageFails() {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","name":"A"}],"next":"https://x/next"}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        val r = SpotifyPlaylists.fetchAll(OkHttpClient(), server.url("/").toString().trimEnd('/'), "t")
        assertEquals(1, (r as PlaylistFetch.Ok).playlists.size)
    }

    @Test fun fetchAllFirstPage403IsReconnect() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(PlaylistFetch.NeedsReconnect, SpotifyPlaylists.fetchAll(OkHttpClient(), server.url("/").toString().trimEnd('/'), "t"))
    }
}
