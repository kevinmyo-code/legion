package com.kevin.legion.ui.media

import com.kevin.legion.media.SpotifyWebApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [MediaLibraryResolver] - the library-browse and queue panels' outcome
 * mapping, over the SAME [SpotifyWebApi.LibraryOutcome] shape `browse_my_music`/`get_music_queue`
 * read. No Android dependency, plain JVM test.
 */
class MediaLibraryResolverTest {

    @Test
    fun `a non-empty Found renders the list, not a message`() {
        val outcome = SpotifyWebApi.LibraryOutcome.Found(listOf("one item"))
        assertNull(MediaLibraryResolver.message(outcome, "saved albums", stale = false))
    }

    @Test
    fun `an EMPTY Found is a real distinct answer, never silently rendered as nothing`() {
        val outcome = SpotifyWebApi.LibraryOutcome.Found<String>(emptyList())
        val message = MediaLibraryResolver.message(outcome, "saved albums", stale = false)
        assertTrue(message!!.contains("saved albums"))
        assertTrue(message.contains("no"))
    }

    @Test
    fun `NeedsAuthorization names the source it was trying to read`() {
        val message = MediaLibraryResolver.message(SpotifyWebApi.LibraryOutcome.NeedsAuthorization, "top tracks", stale = false)
        assertTrue(message!!.contains("top tracks"))
        assertTrue(message.contains("AUTHORIZE"))
    }

    @Test
    fun `Unauthorized reads differently when the grant is stale versus never connected`() {
        val outcome = SpotifyWebApi.LibraryOutcome.Unauthorized(detail = "expired")
        val staleMessage = MediaLibraryResolver.message(outcome, "recently played", stale = true)
        val freshMessage = MediaLibraryResolver.message(outcome, "recently played", stale = false)
        assertTrue(staleMessage!!.contains("re-approving") || staleMessage.contains("AUTHORIZE again"))
        assertTrue(freshMessage!!.contains("expired"))
        assertTrue(staleMessage != freshMessage)
    }

    @Test
    fun `Unreachable names the source too`() {
        val message = MediaLibraryResolver.message(SpotifyWebApi.LibraryOutcome.Unreachable, "the upcoming queue", stale = false)
        assertTrue(message!!.contains("the upcoming queue"))
    }

    @Test
    fun `Failed carries the HTTP code and the source label`() {
        val outcome = SpotifyWebApi.LibraryOutcome.Failed(code = 500, detail = "server error")
        val message = MediaLibraryResolver.message(outcome, "top tracks", stale = false)
        assertEquals(true, message!!.contains("500"))
        assertTrue(message.contains("top tracks"))
    }
}
