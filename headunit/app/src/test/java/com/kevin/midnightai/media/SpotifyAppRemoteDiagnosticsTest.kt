package com.kevin.midnightai.media

import com.kevin.midnightai.ui.spotifyStatusLines
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeoutException

/** What Settings says about WHY App Remote is down: the failure text and the Spotify app found. */
class SpotifyAppRemoteDiagnosticsTest {

    @Test fun `no failure, no detail`() {
        assertNull(SpotifyController.failureDetail(null))
    }

    @Test fun `detail carries the class, Spotify's message and a hint`() {
        val d = SpotifyController.failureDetail(
            UserNotAuthorizedException("Explicit user authorization is required to use Spotify.", null),
        )!!
        assertTrue(d.startsWith("UserNotAuthorizedException: Explicit user authorization"))
        assertTrue(d.contains("Spotify app's own consent"))
    }

    @Test fun `an invalid client message is called out as a dashboard mismatch`() {
        val d = SpotifyController.failureDetail(UserNotAuthorizedException("INVALID_CLIENT: Invalid redirect URI", null))!!
        assertTrue(d.contains("client ID, redirect URI or SHA-1"))
    }

    @Test fun `not found names the signed package requirement`() {
        assertTrue(SpotifyController.failureDetail(CouldNotFindSpotifyApp())!!.contains("com.spotify.music"))
    }

    @Test fun `a timeout says the Spotify app never answered`() {
        val d = SpotifyController.failureDetail(TimeoutException("no answer from the Spotify app in 20s"))!!
        assertTrue(d.contains("in 20s"))
        assertTrue(d.contains("never answered"))
    }

    @Test fun `an unknown failure still shows its class and message`() {
        assertEquals("IllegalStateException: boom", SpotifyController.failureDetail(IllegalStateException("boom")))
        assertEquals("IllegalStateException", SpotifyController.failureDetail(IllegalStateException()))
    }

    // --- Spotify app probe --------------------------------------------------------------------

    private val playSigned = "644D12F22FE5AD82A2BF0BEC66E99E699EB0A41608919507C784F648009B9290"

    @Test fun `the Play-signed fingerprint is accepted, others are not`() {
        assertEquals(true, SpotifyAppProbe.signatureAccepted("com.spotify.music", playSigned))
        assertEquals(true, SpotifyAppProbe.signatureAccepted("com.spotify.music", playSigned.lowercase()))
        assertEquals(false, SpotifyAppProbe.signatureAccepted("com.spotify.music", "00".repeat(32)))
        assertNull(SpotifyAppProbe.signatureAccepted("com.spotify.lite", playSigned))
    }

    @Test fun `describe covers none, usable, modded and lite-only`() {
        assertEquals("Spotify app: not installed on this unit", SpotifyAppProbe.describe(emptyList()))
        assertEquals(
            "Spotify app: com.spotify.music 8.9.96",
            SpotifyAppProbe.describe(listOf(SpotifyAppProbe.Found("com.spotify.music", "8.9.96", true))),
        )
        assertTrue(
            SpotifyAppProbe.describe(listOf(SpotifyAppProbe.Found("com.spotify.music", "8.4.0", false)))
                .contains("not Spotify-signed"),
        )
        assertTrue(
            SpotifyAppProbe.describe(listOf(SpotifyAppProbe.Found("com.spotify.lite", "1.9", null)))
                .contains("only com.spotify.lite"),
        )
    }

    // --- Settings lines -----------------------------------------------------------------------

    @Test fun `the App Remote line carries the failure text`() {
        val lines = spotifyStatusLines(false, true, SpotifyScopes.ALL, 3, null, appRemoteFailure = "UserNotAuthorizedException: x")
        assertEquals("App Remote (playback): not connected - UserNotAuthorizedException: x", lines.first())
    }

    @Test fun `the Spotify app line follows the App Remote line`() {
        val lines = spotifyStatusLines(false, true, SpotifyScopes.ALL, 3, null, spotifyApp = "Spotify app: not installed on this unit")
        assertEquals("Spotify app: not installed on this unit", lines[1])
    }

    @Test fun `Web API playback is ready on a full grant`() {
        val lines = spotifyStatusLines(false, true, SpotifyScopes.ALL, 3, null)
        assertTrue(lines.contains("Web API playback (used when App Remote is off): ready"))
        assertEquals("3 playlists loaded", lines.last())
    }

    @Test fun `Web API playback asks to re-authorize when its scopes are missing`() {
        val line = spotifyStatusLines(false, true, "playlist-read-private", 3, null)
            .single { it.startsWith("Web API playback") }
        assertTrue(line.contains("RE-AUTHORIZE SPOTIFY"))
        assertTrue(line.contains("user-modify-playback-state"))
    }

    @Test fun `Web API playback shows its last failure`() {
        val line = spotifyStatusLines(false, true, SpotifyScopes.ALL, 3, null, webPlaybackFailure = "Spotify Premium required")
            .single { it.startsWith("Web API playback") }
        assertTrue(line.endsWith("last try failed - Spotify Premium required"))
    }

    @Test fun `no Web API playback line without a Web grant`() {
        assertFalse(spotifyStatusLines(false, false, "", 0, null).any { it.startsWith("Web API playback") })
    }
}
