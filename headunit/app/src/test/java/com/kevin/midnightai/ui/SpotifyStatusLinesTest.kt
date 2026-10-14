package com.kevin.midnightai.ui

import com.kevin.midnightai.media.SpotifyScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyStatusLinesTest {

    @Test fun unauthorizedShowsOnlyTheTwoConnectionLines() {
        val lines = spotifyStatusLines(false, false, "", 0, null)
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("not authorized"))
    }

    @Test fun missingScopesAreListedByName() {
        val lines = spotifyStatusLines(true, true, "user-read-private", 0, null)
        val missing = lines.single { it.startsWith("Permissions missing") }
        assertTrue(missing.contains("playlist-read-private"))
        assertFalse(missing.contains("user-read-private"))
    }

    @Test fun fullGrantSaysAllGranted() {
        val lines = spotifyStatusLines(true, true, SpotifyScopes.ALL, 7, null)
        assertTrue(lines.contains("Permissions granted: all requested"))
        assertTrue(lines.contains("7 playlists loaded"))
    }

    @Test fun anErrorBeatsNotFetchedYet() {
        val lines = spotifyStatusLines(false, true, SpotifyScopes.ALL, 0, "Spotify refused (403).")
        assertTrue(lines.last().contains("Spotify refused (403)."))
        assertFalse(lines.any { it.contains("not fetched yet") })
    }

    @Test fun playbackNotConnectedPointsAtConnect() {
        assertTrue(spotifyStatusLines(false, true, SpotifyScopes.ALL, 0, null).first().contains("tap CONNECT"))
        assertEquals("App Remote (playback): connected", spotifyStatusLines(true, true, SpotifyScopes.ALL, 0, null).first())
    }

    @Test fun noListNoErrorIsNotFetchedYet() {
        assertTrue(spotifyStatusLines(false, true, SpotifyScopes.ALL, 0, null).last().contains("not fetched yet"))
    }
}
