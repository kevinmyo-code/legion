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
 * [SpotifyController.playMusicUnavailableMessage]: what `play_music` says when App Remote is not
 * connected. The bug this covers: a driver with Spotify set up was told to "turn on Music app on
 * this head unit" (an unrelated toggle, under a label the Settings screen does not even use)
 * instead of the real reason App Remote failed. Since 2026-10-15 there is no other player to fall
 * through to, so the function always returns a sentence.
 */
class SpotifyControllerPlayMusicUnavailableTest {

    private fun msg(
        hasClientId: Boolean = true,
        failure: Throwable? = null,
        unreadable: Boolean = false,
    ) = SpotifyController.playMusicUnavailableMessage(
        hasClientId, failure, "Bad Bunny", clientIdUnreadable = unreadable,
    )

    @Test
    fun `client ID saved, not installed names that`() {
        val m = msg(failure = CouldNotFindSpotifyApp())
        assertTrue(m.contains("isn't installed"))
        assertTrue(m.contains("Bad Bunny"))
    }

    @Test
    fun `client ID saved, logged out says to log in`() {
        assertTrue(msg(failure = NotLoggedInException("x", null)).contains("log in"))
    }

    @Test
    fun `client ID saved, not authorized points at CONNECT in Settings, not the dashboard`() {
        val m = msg(failure = UserNotAuthorizedException("x", null))
        assertTrue(m.contains("CONNECT"))
        assertTrue(m.contains("Settings, Music, Spotify"))
        assertFalse(m.contains("dashboard"))
    }

    @Test
    fun `client ID saved, offline names offline mode`() {
        assertTrue(msg(failure = OfflineModeException("x", null)).contains("offline mode"))
    }

    @Test
    fun `client ID saved, unnamed failure carries the class name and the CONNECT hint`() {
        val m = msg(failure = IllegalStateException("wedged"))
        assertTrue(m.contains("IllegalStateException"))
        assertTrue(m.contains("CONNECT"))
    }

    @Test
    fun `client ID saved, no recorded failure still speaks a reason`() {
        assertTrue(msg(failure = null).isNotBlank())
    }

    @Test
    fun `the named failures read differently from each other`() {
        val lines = setOf(
            msg(failure = CouldNotFindSpotifyApp()),
            msg(failure = NotLoggedInException("x", null)),
            msg(failure = UserNotAuthorizedException("x", null)),
            msg(failure = OfflineModeException("x", null)),
            msg(failure = IllegalStateException()),
        )
        assertEquals(5, lines.size)
    }

    @Test
    fun `a connect that outran the budget says still connecting, never the stale failure`() {
        val m = SpotifyController.playMusicUnavailableMessage(
            hasClientId = true,
            failure = CouldNotFindSpotifyApp(),
            description = "Bad Bunny",
            connectTimedOut = true,
        )
        assertTrue(m.contains("still connecting"))
        assertFalse(m.contains("isn't installed"))
    }

    @Test
    fun `an unreadable saved client ID says so, not never set up (F11)`() {
        val m = msg(hasClientId = false, unreadable = true)
        assertTrue(m.contains("can't read the saved Spotify setup"))
        assertTrue(m.contains("re-enter the client ID"))
        assertFalse(m.contains("isn't set up"))
    }

    @Test
    fun `no client ID points at where to enter one and never offers to open an app`() {
        val m = msg(hasClientId = false)
        assertTrue(m.contains("Settings, Music, Spotify"))
        assertFalse(m.contains("Open music apps by voice"))
        assertFalse(m.lowercase().contains("open one"))
    }

    @Test
    fun `the pending-play line never claims playback started or failed, and never says ask again`() {
        val m = SpotifyController.playStillPendingMessage("Bad Bunny")
        assertTrue(m.contains("hasn't confirmed"))
        assertFalse(m.startsWith("Playing"))
        assertFalse(m.contains("couldn't"))
        assertFalse(m.lowercase().contains("ask me again"))
        assertTrue(m.contains("I'll tell you how it ends"))
    }
}
