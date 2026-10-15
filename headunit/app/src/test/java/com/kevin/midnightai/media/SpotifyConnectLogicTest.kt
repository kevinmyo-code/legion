package com.kevin.midnightai.media

import com.kevin.midnightai.media.SpotifyConnectLogic.Device
import com.kevin.midnightai.media.SpotifyConnectLogic.Failure
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyConnectLogicTest {

    private fun dev(id: String, name: String, type: String = "Smartphone", active: Boolean = false, restricted: Boolean = false) =
        Device(id, name, type, active, restricted)

    // --- device parsing ---------------------------------------------------------------------

    @Test fun `parseDevices keeps fields and drops null or blank ids`() {
        val json = JSONObject(
            """{"devices":[
                {"id":"a1","name":"rk3399","type":"Smartphone","is_active":true,"is_restricted":false},
                {"id":null,"name":"ghost","type":"Speaker","is_active":false,"is_restricted":false},
                {"id":"","name":"blank","type":"Speaker"},
                {"id":"b2","name":"Kitchen","type":"Speaker","is_active":false,"is_restricted":true}
            ]}""",
        )
        val list = SpotifyConnectLogic.parseDevices(json)
        assertEquals(listOf("a1", "b2"), list.map { it.id })
        assertTrue(list[0].isActive)
        assertTrue(list[1].isRestricted)
        assertEquals("Smartphone", list[0].type)
    }

    @Test fun `parseDevices on an empty body is empty`() {
        assertTrue(SpotifyConnectLogic.parseDevices(JSONObject("{}")).isEmpty())
    }

    // --- device pick ------------------------------------------------------------------------

    @Test fun `no devices picks nothing`() {
        assertNull(SpotifyConnectLogic.pickDevice(emptyList(), listOf("rk3399")))
    }

    @Test fun `only restricted devices picks nothing`() {
        assertNull(SpotifyConnectLogic.pickDevice(listOf(dev("a", "rk3399", restricted = true)), listOf("rk3399")))
    }

    @Test fun `this unit by name beats an active device elsewhere`() {
        val devices = listOf(
            dev("desk", "DESKTOP-KEVIN", type = "Computer", active = true),
            dev("car", "rk3399 Car", type = "Smartphone"),
        )
        assertEquals("car", SpotifyConnectLogic.pickDevice(devices, listOf("rk3399"))?.id)
    }

    @Test fun `Automobile type matches by name too`() {
        val devices = listOf(dev("x", "Other"), dev("car", "MyHeadUnit", type = "Automobile"))
        assertEquals("car", SpotifyConnectLogic.pickDevice(devices, listOf("myheadunit"))?.id)
    }

    @Test fun `with no name match the active device wins`() {
        val devices = listOf(dev("p", "Pixel"), dev("s", "Speaker", type = "Speaker", active = true))
        assertEquals("s", SpotifyConnectLogic.pickDevice(devices, listOf("rk3399"))?.id)
    }

    @Test fun `with no match and nothing active the first phone-class device wins`() {
        val devices = listOf(dev("s", "Speaker", type = "Speaker"), dev("p", "Generic", type = "Smartphone"))
        assertEquals("p", SpotifyConnectLogic.pickDevice(devices, listOf("rk3399"))?.id)
    }

    @Test fun `otherwise the first usable device`() {
        val devices = listOf(dev("r", "Locked", type = "Speaker", restricted = true), dev("s", "Speaker", type = "Speaker"))
        assertEquals("s", SpotifyConnectLogic.pickDevice(devices, emptyList())?.id)
    }

    @Test fun `a restricted name match is skipped`() {
        val devices = listOf(dev("car", "rk3399", restricted = true), dev("p", "Pixel"))
        assertEquals("p", SpotifyConnectLogic.pickDevice(devices, listOf("rk3399"))?.id)
    }

    @Test fun `name match ignores case and blanks, and short names need exact`() {
        assertTrue(SpotifyConnectLogic.nameMatches("RK3399", listOf(" rk3399 ")))
        assertFalse(SpotifyConnectLogic.nameMatches("rk3399", listOf("", "  ")))
        assertFalse(SpotifyConnectLogic.nameMatches("ab-car", listOf("ab")))
        assertTrue(SpotifyConnectLogic.nameMatches("ab", listOf("ab")))
        assertFalse(SpotifyConnectLogic.nameMatches("", listOf("rk3399")))
    }

    // --- play body ----------------------------------------------------------------------------

    @Test fun `playlist album and artist play as a context`() {
        listOf("spotify:playlist:abc", "spotify:album:abc", "spotify:artist:abc", "spotify:user:kev:playlist:abc").forEach {
            assertEquals(it, SpotifyConnectLogic.playBody(it).getString("context_uri"))
        }
    }

    @Test fun `track and episode play as uris`() {
        val body = SpotifyConnectLogic.playBody("spotify:track:xyz")
        assertFalse(body.has("context_uri"))
        assertEquals("spotify:track:xyz", body.getJSONArray("uris").getString(0))
        assertFalse(SpotifyConnectLogic.isContextUri("spotify:episode:e1"))
    }

    // --- error mapping ------------------------------------------------------------------------

    @Test fun `2xx is no failure`() {
        assertNull(SpotifyConnectLogic.failureForResponse(204, ""))
        assertNull(SpotifyConnectLogic.failureForResponse(202, ""))
    }

    @Test fun `403 PREMIUM_REQUIRED is premium`() {
        val body = """{"error":{"status":403,"message":"Player command failed: Premium required","reason":"PREMIUM_REQUIRED"}}"""
        assertEquals(Failure.PremiumRequired, SpotifyConnectLogic.failureForResponse(403, body))
    }

    @Test fun `404 NO_ACTIVE_DEVICE is no device`() {
        val body = """{"error":{"status":404,"message":"Player command failed: No active device found","reason":"NO_ACTIVE_DEVICE"}}"""
        assertEquals(Failure.NoDevice(spotifyInstalled = true), SpotifyConnectLogic.failureForResponse(404, body))
    }

    @Test fun `401 is a rejected token`() {
        assertEquals(Failure.TokenRejected, SpotifyConnectLogic.failureForResponse(401, "{}"))
    }

    @Test fun `403 insufficient scope asks to re-authorize`() {
        val body = """{"error":{"status":403,"message":"Insufficient client scope"}}"""
        assertTrue(SpotifyConnectLogic.failureForResponse(403, body) is Failure.MissingScopes)
    }

    @Test fun `anything else is rejected with Spotify's message`() {
        val body = """{"error":{"status":429,"message":"API rate limit exceeded"}}"""
        assertEquals(Failure.Rejected(429, "API rate limit exceeded"), SpotifyConnectLogic.failureForResponse(429, body))
        assertEquals(Failure.Rejected(500, null), SpotifyConnectLogic.failureForResponse(500, "<html>"))
    }

    @Test fun `every failure has a short and a spoken line`() {
        val all = listOf(
            Failure.NeverAuthorized, Failure.TokenRejected, Failure.MissingScopes(SpotifyConnectLogic.PLAYBACK_SCOPES),
            Failure.PremiumRequired, Failure.NoDevice(true), Failure.NoDevice(false), Failure.Unreachable,
            Failure.Rejected(500, "x"),
        )
        all.forEach {
            assertTrue(SpotifyConnectLogic.shortReason(it).isNotBlank())
            assertTrue(SpotifyConnectLogic.spokenReason(it, "Chill").isNotBlank())
        }
        assertEquals("Spotify Premium required", SpotifyConnectLogic.shortReason(Failure.PremiumRequired))
        assertTrue(SpotifyConnectLogic.spokenReason(Failure.PremiumRequired, "Chill").contains("Premium"))
    }

    // --- scopes -------------------------------------------------------------------------------

    @Test fun `playback scopes are checked against the grant`() {
        assertEquals(SpotifyConnectLogic.PLAYBACK_SCOPES, SpotifyConnectLogic.missingPlaybackScopes("user-read-private"))
        assertTrue(SpotifyConnectLogic.missingPlaybackScopes(SpotifyScopes.ALL).isEmpty())
        assertEquals(
            listOf("user-modify-playback-state"),
            SpotifyConnectLogic.missingPlaybackScopes("user-read-playback-state playlist-read-private"),
        )
    }

    @Test fun `the requested scope list includes both playback scopes`() {
        assertTrue(SpotifyScopes.REQUESTED.containsAll(SpotifyConnectLogic.PLAYBACK_SCOPES))
    }

    // --- fallback decision --------------------------------------------------------------------

    @Test fun `App Remote is tried when connected, in flight, never failed, or failed long ago`() {
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(isConnected = true, attemptInFlight = false, msSinceLastFailure = 1))
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(isConnected = false, attemptInFlight = true, msSinceLastFailure = 1))
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(isConnected = false, attemptInFlight = false, msSinceLastFailure = null))
        assertTrue(
            SpotifyConnectLogic.shouldTryAppRemote(false, false, SpotifyConnectLogic.RECENT_FAILURE_MS),
        )
    }

    @Test fun `a caller that already waited on App Remote does not join the timed-out attempt again`() {
        // play_music's own 4s connect timed out; the attempt is still in flight. Joining it a second
        // time stacked another 6s ahead of the Web API leg (2026-10-15 car report).
        assertFalse(
            SpotifyConnectLogic.shouldTryAppRemote(
                isConnected = false, attemptInFlight = true, msSinceLastFailure = null, callerAlreadyTried = true,
            ),
        )
        assertFalse(
            SpotifyConnectLogic.shouldTryAppRemote(
                isConnected = false, attemptInFlight = false, msSinceLastFailure = null, callerAlreadyTried = true,
            ),
        )
        assertFalse(
            SpotifyConnectLogic.shouldTryAppRemote(
                false, false, SpotifyConnectLogic.RECENT_FAILURE_MS, callerAlreadyTried = true,
            ),
        )
    }

    @Test fun `a link that came up meanwhile is still used even if the caller already tried`() {
        assertTrue(
            SpotifyConnectLogic.shouldTryAppRemote(
                isConnected = true, attemptInFlight = false, msSinceLastFailure = null, callerAlreadyTried = true,
            ),
        )
    }

    @Test fun `a fresh App Remote failure goes straight to the Web API`() {
        assertFalse(SpotifyConnectLogic.shouldTryAppRemote(isConnected = false, attemptInFlight = false, msSinceLastFailure = 2_000))
    }

    @Test fun `Web API never authorized reports App Remote's own reason`() {
        assertEquals(
            SpotifyController.PlayOutcome.NotInstalled,
            SpotifyController.outcomeForWebFailure(Failure.NeverAuthorized, CouldNotFindSpotifyApp()),
        )
        assertEquals(
            SpotifyController.PlayOutcome.NotAuthorized,
            SpotifyController.outcomeForWebFailure(Failure.NeverAuthorized, UserNotAuthorizedException("x", null)),
        )
    }

    @Test fun `any other Web API failure is reported as itself`() {
        assertEquals(
            SpotifyController.PlayOutcome.WebFailed(Failure.PremiumRequired),
            SpotifyController.outcomeForWebFailure(Failure.PremiumRequired, CouldNotFindSpotifyApp()),
        )
        val noDevice = SpotifyController.outcomeForWebFailure(Failure.NoDevice(true), null)
        assertFalse(SpotifyController.succeeded(noDevice))
        assertTrue(SpotifyController.message(noDevice, "Chill").contains("Chill"))
    }

    @Test fun `a Web API start counts as started`() {
        assertTrue(SpotifyController.succeeded(SpotifyController.PlayOutcome.Started(viaWebApi = true)))
    }

    // --- now playing --------------------------------------------------------------------------

    @Test fun `currently-playing parses context and track, 204 is idle, errors are null`() {
        val now = SpotifyConnectLogic.parseCurrentlyPlaying(
            200,
            """{"is_playing":true,"context":{"uri":"spotify:playlist:p1"},"item":{"name":"Plastic Love"}}""",
        )
        assertEquals(SpotifyConnectLogic.CurrentlyPlaying(true, "spotify:playlist:p1", "Plastic Love"), now)
        assertEquals(SpotifyConnectLogic.CurrentlyPlaying(false, null, null), SpotifyConnectLogic.parseCurrentlyPlaying(204, ""))
        assertNull(SpotifyConnectLogic.parseCurrentlyPlaying(401, "{}"))
        assertNull(SpotifyConnectLogic.parseCurrentlyPlaying(200, "not json"))
        assertEquals(
            SpotifyConnectLogic.CurrentlyPlaying(false, null, null),
            SpotifyConnectLogic.parseCurrentlyPlaying(200, """{"is_playing":false,"context":null}"""),
        )
    }

    @Test fun `transport commands map to the player endpoints`() {
        assertEquals("PUT" to "/play", SpotifyConnectLogic.commandRequest(SpotifyConnectLogic.Command.RESUME))
        assertEquals("PUT" to "/pause", SpotifyConnectLogic.commandRequest(SpotifyConnectLogic.Command.PAUSE))
        assertEquals("POST" to "/next", SpotifyConnectLogic.commandRequest(SpotifyConnectLogic.Command.NEXT))
        assertEquals("POST" to "/previous", SpotifyConnectLogic.commandRequest(SpotifyConnectLogic.Command.PREVIOUS))
    }
}
