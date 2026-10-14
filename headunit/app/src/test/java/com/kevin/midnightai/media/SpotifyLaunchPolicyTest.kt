package com.kevin.midnightai.media

import com.kevin.midnightai.media.SpotifyConnectLogic.Device
import com.kevin.midnightai.media.SpotifyConnectLogic.PlayCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-13 car report: the Spotify app came to the front over Cruise. Opening its activity is
 * now the last resort of a tile tap only; these pin who may and when.
 */
class SpotifyLaunchPolicyTest {

    // --- uiLaunchRefusal ----------------------------------------------------------------------

    @Test fun `a tile tap with Midnight in front and no voice turn may open Spotify`() {
        assertNull(SpotifyConnectLogic.uiLaunchRefusal(PlayCaller.PINNED_TILE, midnightInFront = true, voiceBusy = false))
    }

    @Test fun `voice never opens Spotify, whatever the state`() {
        for (front in listOf(true, false)) {
            for (busy in listOf(true, false)) {
                assertNotNull(SpotifyConnectLogic.uiLaunchRefusal(PlayCaller.VOICE, front, busy))
            }
        }
    }

    @Test fun `a tile tapped during a voice turn does not open Spotify`() {
        val why = SpotifyConnectLogic.uiLaunchRefusal(PlayCaller.PINNED_TILE, midnightInFront = true, voiceBusy = true)
        assertEquals("voice turn running", why)
    }

    @Test fun `a tile play that finishes after the driver left Midnight does not open Spotify`() {
        val why = SpotifyConnectLogic.uiLaunchRefusal(PlayCaller.PINNED_TILE, midnightInFront = false, voiceBusy = false)
        assertEquals("Midnight not in front", why)
    }

    // --- parsePlayer --------------------------------------------------------------------------

    @Test fun `204 means nothing playing anywhere`() {
        assertEquals(false to null, SpotifyConnectLogic.parsePlayer(204, ""))
    }

    @Test fun `failed or unreadable player responses are unknown`() {
        assertNull(SpotifyConnectLogic.parsePlayer(401, """{"error":{"status":401}}"""))
        assertNull(SpotifyConnectLogic.parsePlayer(200, "not json"))
    }

    @Test fun `player response yields playing flag and device`() {
        val body = """{"is_playing":true,"device":{"id":"d1","name":"rk3399","type":"Smartphone",
            "is_active":true,"is_restricted":false}}"""
        val (playing, device) = SpotifyConnectLogic.parsePlayer(200, body)!!
        assertTrue(playing)
        assertEquals(Device("d1", "rk3399", "Smartphone", isActive = true, isRestricted = false), device)
    }

    @Test fun `player response with a null device id keeps a blank id`() {
        val body = """{"is_playing":false,"device":{"id":null,"name":"x","type":"Speaker"}}"""
        val (playing, device) = SpotifyConnectLogic.parsePlayer(200, body)!!
        assertFalse(playing)
        assertEquals("", device!!.id)
    }

    // --- playingOnThisUnit --------------------------------------------------------------------

    private fun dev(type: String, restricted: Boolean = false) = Device("d", "Car", type, true, restricted)

    @Test fun `playing on a phone-class device counts as this unit`() {
        assertTrue(SpotifyConnectLogic.playingOnThisUnit(true, dev("Smartphone")))
        assertTrue(SpotifyConnectLogic.playingOnThisUnit(true, dev("Automobile")))
        assertTrue(SpotifyConnectLogic.playingOnThisUnit(true, dev("Tablet")))
    }

    @Test fun `paused, elsewhere, restricted or deviceless is not this unit`() {
        assertFalse(SpotifyConnectLogic.playingOnThisUnit(false, dev("Smartphone")))
        assertFalse(SpotifyConnectLogic.playingOnThisUnit(true, dev("Computer")))
        assertFalse(SpotifyConnectLogic.playingOnThisUnit(true, dev("Speaker")))
        assertFalse(SpotifyConnectLogic.playingOnThisUnit(true, dev("Smartphone", restricted = true)))
        assertFalse(SpotifyConnectLogic.playingOnThisUnit(true, null))
    }
}
