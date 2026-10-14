package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyScopesTest {

    @Test fun `ALL and REQUESTED agree and carry no duplicates or blanks`() {
        assertEquals(SpotifyScopes.ALL, SpotifyScopes.REQUESTED.joinToString(" "))
        assertEquals(SpotifyScopes.REQUESTED.size, SpotifyScopes.REQUESTED.toSet().size)
        assertTrue(SpotifyScopes.REQUESTED.none { it.isBlank() })
    }

    @Test fun `app-remote-control is requested but never required in the token`() {
        assertTrue("app-remote-control" in SpotifyScopes.REQUESTED)
        assertFalse("app-remote-control" in SpotifyScopes.REQUIRED)
        assertTrue(SpotifyScopes.REQUIRED.all { it in SpotifyScopes.REQUESTED })
    }

    @Test fun `a grant without app-remote-control does not trip the reconnect`() {
        assertFalse(SpotifyScopes.anyMissing(SpotifyScopes.REQUIRED.joinToString(" ")))
    }

    @Test fun `the set covers the playlist row AND every read the voice tools make`() {
        val need = listOf(
            "playlist-read-private", "playlist-read-collaborative", // Cruise row + playlists by name
            "user-library-read", "user-read-recently-played", "user-top-read", // browse_my_music
            "user-read-playback-state", "user-read-currently-playing", // get_music_queue needs both
        )
        need.forEach { assertTrue("missing $it", it in SpotifyScopes.REQUIRED) }
    }

    @Test fun `no playlist-modify scope is requested since nothing writes playlists`() {
        assertTrue(SpotifyScopes.REQUIRED.none { it.startsWith("playlist-modify") })
    }

    @Test fun `a blank grant is missing everything and trips the reconnect`() {
        assertEquals(SpotifyScopes.REQUIRED, SpotifyScopes.missing(""))
        assertTrue(SpotifyScopes.anyMissing(""))
    }

    @Test fun `the old playlists-only grant trips the reconnect once for the new scopes`() {
        val old = "playlist-read-private playlist-read-collaborative"
        assertTrue(SpotifyScopes.anyMissing(old))
        assertFalse("playlist-read-private" in SpotifyScopes.missing(old))
        assertTrue("user-top-read" in SpotifyScopes.missing(old))
    }

    @Test fun `a full grant is not missing anything, in any order or with commas`() {
        assertFalse(SpotifyScopes.anyMissing(SpotifyScopes.ALL))
        assertFalse(SpotifyScopes.anyMissing(SpotifyScopes.REQUIRED.reversed().joinToString(" ")))
        assertFalse(SpotifyScopes.anyMissing(SpotifyScopes.REQUIRED.joinToString(",")))
    }

    @Test fun `a grant missing exactly one scope names exactly that one`() {
        val grant = SpotifyScopes.REQUIRED.filter { it != "user-top-read" }.joinToString(" ")
        assertEquals(listOf("user-top-read"), SpotifyScopes.missing(grant))
    }

    @Test fun `extra scopes beyond the required set are harmless`() {
        assertFalse(SpotifyScopes.anyMissing(SpotifyScopes.ALL + " playlist-modify-private user-read-email"))
    }
}
