package com.kevin.midnightai.media

import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OAuth-redirect toast used to say "Spotify connected" on the Web API grant alone, while App
 * Remote (playback) could still be unconnected. It must now report both halves.
 */
class SpotifyControllerRedirectToastTest {

    @Test
    fun `both connected says so`() {
        val t = SpotifyController.redirectToast(true, null)
        assertTrue(t.contains("playback"))
        assertFalse(t.contains("playback:"))
    }

    @Test
    fun `playback failure names the reason`() {
        assertEquals(
            "Spotify search connected; playback: not approved - tap CONNECT",
            SpotifyController.redirectToast(false, UserNotAuthorizedException("x", null)),
        )
    }

    @Test
    fun `playback still pending is not reported as connected`() {
        assertEquals(
            "Spotify search connected; playback: still connecting",
            SpotifyController.redirectToast(null, null),
        )
    }

    @Test
    fun `each named failure gets its own short reason`() {
        val reasons = setOf(
            SpotifyController.playbackReasonShort(CouldNotFindSpotifyApp()),
            SpotifyController.playbackReasonShort(NotLoggedInException("x", null)),
            SpotifyController.playbackReasonShort(UserNotAuthorizedException("x", null)),
            SpotifyController.playbackReasonShort(OfflineModeException("x", null)),
            SpotifyController.playbackReasonShort(IllegalStateException()),
            SpotifyController.playbackReasonShort(null),
        )
        assertEquals(6, reasons.size)
    }

    @Test
    fun `consent retry only for a missing grant and only while in front`() {
        assertTrue(SpotifyController.shouldRetryWithConsent(UserNotAuthorizedException("x", null), resumed = true))
        assertFalse(SpotifyController.shouldRetryWithConsent(UserNotAuthorizedException("x", null), resumed = false))
        assertFalse(SpotifyController.shouldRetryWithConsent(CouldNotFindSpotifyApp(), resumed = true))
        assertFalse(SpotifyController.shouldRetryWithConsent(NotLoggedInException("x", null), resumed = true))
        assertFalse(SpotifyController.shouldRetryWithConsent(OfflineModeException("x", null), resumed = true))
        assertFalse(SpotifyController.shouldRetryWithConsent(java.util.concurrent.TimeoutException(), resumed = true))
        assertFalse(SpotifyController.shouldRetryWithConsent(null, resumed = true))
    }

    @Test
    fun `an unnamed failure carries its class name`() {
        assertEquals("failed (IllegalStateException)", SpotifyController.playbackReasonShort(IllegalStateException()))
    }
}
