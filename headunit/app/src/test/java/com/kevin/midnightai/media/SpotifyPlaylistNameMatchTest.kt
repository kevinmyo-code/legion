package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for the fuzzy playlist-name matcher in [SpotifyPlaylistLogic], the half of
 * "play my Roadtrip playlist" that runs against the driver's own library before any public search.
 * No network: [SpotifyPlaylistLogic.bestMatch] runs on an in-memory list, which is the point of
 * resolving against the cached library rather than the search endpoint.
 */
class SpotifyPlaylistNameMatchTest {

    private fun playlist(name: String) =
        SpotifyPlaylist(id = name.hashCode().toString(), name = name, uri = "spotify:playlist:${name.hashCode()}", coverUrl = null)

    @Test fun `matchScore is 1_0 for an exact case-insensitive match`() {
        assertEquals(1.0, SpotifyPlaylistLogic.matchScore("roadtrip", "Roadtrip"), 0.0001)
        assertEquals(1.0, SpotifyPlaylistLogic.matchScore("ROADTRIP", "roadtrip"), 0.0001)
    }

    @Test fun `matchScore ignores punctuation and extra whitespace`() {
        assertEquals(1.0, SpotifyPlaylistLogic.matchScore("kev's roadtrip", "Kev's  Roadtrip!"), 0.0001)
    }

    @Test fun `matchScore scores a multi-word name containing the spoken words highly`() {
        val score = SpotifyPlaylistLogic.matchScore("roadtrip", "Roadtrip 2024")
        assertTrue("expected containment score >= 0.9 but was $score", score >= 0.9)
    }

    @Test fun `a generic one-word playlist does not swallow a longer request`() {
        assertTrue(SpotifyPlaylistLogic.matchScore("some city pop", "Pop") < SpotifyPlaylistLogic.MATCH_THRESHOLD)
        assertNull(SpotifyPlaylistLogic.bestMatch(listOf(playlist("Pop")), "some city pop"))
    }

    @Test fun `a two-token spoken name still matches a longer playlist`() {
        assertNotNull(SpotifyPlaylistLogic.bestMatch(listOf(playlist("City Pop Classics")), "city pop"))
    }

    @Test fun `chill matches Chill and roadtrip matches Roadtrip 2024`() {
        assertEquals("Chill", SpotifyPlaylistLogic.bestMatch(listOf(playlist("Chill")), "chill")?.name)
        assertEquals("Roadtrip 2024", SpotifyPlaylistLogic.bestMatch(listOf(playlist("Roadtrip 2024")), "roadtrip")?.name)
    }

    @Test fun `matchScore is below threshold for two unrelated short names`() {
        val score = SpotifyPlaylistLogic.matchScore("Gym", "Chill")
        assertTrue("expected a low score but was $score", score < SpotifyPlaylistLogic.MATCH_THRESHOLD)
    }

    @Test fun `matchScore tolerates a single typo in a longer name`() {
        val score = SpotifyPlaylistLogic.matchScore("roadtrp", "roadtrip")
        assertTrue("expected >= threshold but was $score", score >= SpotifyPlaylistLogic.MATCH_THRESHOLD)
    }

    @Test fun `matchScore is zero when either side normalises to nothing`() {
        assertEquals(0.0, SpotifyPlaylistLogic.matchScore("!!!", "Roadtrip"), 0.0)
        assertEquals(0.0, SpotifyPlaylistLogic.matchScore("Roadtrip", ""), 0.0)
    }

    @Test fun `bestMatch picks the closest name among several candidates`() {
        val playlists = listOf(playlist("Gym"), playlist("Roadtrip"), playlist("Chill Vibes"))
        assertEquals("Roadtrip", SpotifyPlaylistLogic.bestMatch(playlists, "road trip")?.name)
    }

    @Test fun `bestMatch returns null when nothing clears the threshold - fall through to search, not no playlists`() {
        val playlists = listOf(playlist("Gym"), playlist("Chill Vibes"))
        assertNull(SpotifyPlaylistLogic.bestMatch(playlists, "Discover Weekly"))
    }

    @Test fun `bestMatch returns null on an empty library or a blank spoken name`() {
        assertNull(SpotifyPlaylistLogic.bestMatch(emptyList(), "Roadtrip"))
        assertNull(SpotifyPlaylistLogic.bestMatch(listOf(playlist("Roadtrip")), ""))
    }

    @Test fun `bestMatch hands back the full playlist so the caller can play its uri`() {
        val match = SpotifyPlaylistLogic.bestMatch(listOf(playlist("Roadtrip")), "roadtrip")
        assertNotNull(match)
        assertTrue(match!!.uri.startsWith("spotify:playlist:"))
    }

    @Test fun `levenshtein of identical strings is zero`() {
        assertEquals(0, SpotifyPlaylistLogic.levenshtein("roadtrip", "roadtrip"))
    }

    @Test fun `levenshtein counts a single substitution as distance one`() {
        assertEquals(1, SpotifyPlaylistLogic.levenshtein("roadtrip", "roadtrup"))
    }

    @Test fun `levenshtein handles an empty string as the length of the other`() {
        assertEquals(5, SpotifyPlaylistLogic.levenshtein("", "abcde"))
        assertEquals(5, SpotifyPlaylistLogic.levenshtein("abcde", ""))
    }
}
