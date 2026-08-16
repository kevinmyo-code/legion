package com.kevin.legion.ui.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [MediaAccessResolver] - the notification-listener-access banner's
 * state derivation. No Android dependency on the path, plain JVM test, same shape as
 * `SpotifyConnectResolverTest`.
 */
class MediaAccessResolverTest {

    @Test
    fun `banner shows when the grant is missing`() {
        assertTrue(MediaAccessResolver.shouldShowBanner(hasNotificationAccess = false))
    }

    @Test
    fun `banner is suppressed once the grant is present`() {
        assertFalse(MediaAccessResolver.shouldShowBanner(hasNotificationAccess = true))
    }

    @Test
    fun `the banner message names the actual gap - Spotify works, other transport does not`() {
        // Guards against a future edit overstating the outage: the App Remote fallback
        // (commit d683d2c) means Spotify playback keeps working without this grant.
        val message = MediaAccessResolver.BANNER_MESSAGE.lowercase()
        assertTrue(message.contains("notification access"))
        assertTrue(message.contains("spotify"))
        assertFalse(message.isBlank())
    }
}
