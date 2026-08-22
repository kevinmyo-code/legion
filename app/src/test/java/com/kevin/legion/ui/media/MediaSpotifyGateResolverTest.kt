package com.kevin.legion.ui.media

import com.kevin.legion.ui.spotify.SpotifyConnectResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [MediaSpotifyGateResolver] - the search-and-play / library-browse /
 * queue gate on [MediaScreen]. No Android dependency, plain JVM test, same shape as
 * [com.kevin.legion.ui.spotify.SpotifyConnectResolverTest].
 */
class MediaSpotifyGateResolverTest {

    @Test
    fun `only READY is search-ready`() {
        assertTrue(MediaSpotifyGateResolver.searchReady(SpotifyConnectResolver.Stage.READY))
        assertFalse(MediaSpotifyGateResolver.searchReady(SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID))
        assertFalse(MediaSpotifyGateResolver.searchReady(SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION))
        assertFalse(MediaSpotifyGateResolver.searchReady(SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION))
    }

    @Test
    fun `every not-ready stage names Setup, Spotify as the fix - never a blank panel`() {
        val notReady = listOf(
            SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID,
            SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION,
            SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION,
        )
        notReady.forEach { stage ->
            val message = MediaSpotifyGateResolver.notReadyMessage(stage)
            assertTrue("stage $stage should name Setup > Spotify: $message", message.contains("Setup"))
            assertTrue("stage $stage should name Spotify: $message", message.contains("Spotify"))
            assertFalse("stage $stage should not be blank", message.isBlank())
        }
    }

    @Test
    fun `re-approving reads as a refresh, not starting over, matching SpotifyScreen's own wording`() {
        val message = MediaSpotifyGateResolver.notReadyMessage(SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION)
        assertTrue(message.contains("re-approving"))
    }
}
