package com.kevin.legion.ui.media

import com.kevin.legion.media.SpotifyWebApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [MediaSearchResolver] - the search-and-play panel's outcome mapping.
 * [SpotifyWebApi.SearchOutcome] carries no Android dependency, so this is a plain JVM test, no
 * Robolectric, same shape as [com.kevin.legion.media.SpotifyWebApiParsingTest].
 */
class MediaSearchResolverTest {

    @Test
    fun `rowFor carries Spotify's own name and subtitle, not the driver's query`() {
        val found = SpotifyWebApi.SearchOutcome.Found(uri = "spotify:track:abc", name = "Discovery", subtitle = "Daft Punk")
        val row = MediaSearchResolver.rowFor(found)
        assertEquals("spotify:track:abc", row.uri)
        assertEquals("Discovery", row.title)
        assertEquals("Daft Punk", row.subtitle)
    }

    @Test
    fun `failureMessage is null only for Found`() {
        val found = SpotifyWebApi.SearchOutcome.Found(uri = "spotify:track:abc", name = "Discovery", subtitle = "Daft Punk")
        assertNull(MediaSearchResolver.failureMessage(found, "discovery"))
    }

    @Test
    fun `NoMatch names the exact query that failed`() {
        val message = MediaSearchResolver.failureMessage(SpotifyWebApi.SearchOutcome.NoMatch, "some obscure b-side")
        assertTrue(message!!.contains("some obscure b-side"))
    }

    @Test
    fun `NeedsAuthorization points at Setup, Spotify, AUTHORIZE`() {
        val message = MediaSearchResolver.failureMessage(SpotifyWebApi.SearchOutcome.NeedsAuthorization, "x")
        assertTrue(message!!.contains("AUTHORIZE"))
    }

    @Test
    fun `Unauthorized carries Spotify's own detail through rather than paraphrasing it`() {
        val message = MediaSearchResolver.failureMessage(
            SpotifyWebApi.SearchOutcome.Unauthorized(detail = "user may not be registered"),
            "x",
        )
        assertTrue(message!!.contains("user may not be registered"))
    }

    @Test
    fun `Unreachable never claims Spotify said no - it says nothing was reached`() {
        val message = MediaSearchResolver.failureMessage(SpotifyWebApi.SearchOutcome.Unreachable, "x")
        assertTrue(message!!.contains("reach"))
    }

    @Test
    fun `Failed carries the HTTP code`() {
        val message = MediaSearchResolver.failureMessage(SpotifyWebApi.SearchOutcome.Failed(code = 429, detail = "rate limited"), "x")
        assertTrue(message!!.contains("429"))
        assertTrue(message.contains("rate limited"))
    }
}
