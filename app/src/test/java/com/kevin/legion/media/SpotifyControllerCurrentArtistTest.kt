package com.kevin.legion.media

import com.kevin.legion.media.SpotifyController.CurrentArtist
import com.spotify.protocol.types.Album
import com.spotify.protocol.types.Artist
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.PlayerOptions
import com.spotify.protocol.types.PlayerRestrictions
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM coverage for [SpotifyController.currentArtistFrom] (ticket 13,
 * `.scratch/spotify-voice/issues/13-more-from-this-artist.md` scope items 1/2/5): "more from this
 * artist" and "what else does he have" both derive the artist from App Remote's own pushed player
 * state, never from a guess. No Robolectric needed - unlike [NowPlayingController],
 * [SpotifyController] has no eagerly-initialized Android-framework property (same reasoning
 * [SpotifyControllerPlayOutcomeTest]'s own doc gives).
 */
class SpotifyControllerCurrentArtistTest {

    private fun playerStateWithArtist(artistName: String, artistUri: String) = PlayerState(
        Track(
            Artist(artistName, artistUri),
            listOf(Artist(artistName, artistUri)),
            Album("Some Album", "spotify:album:def456"),
            200_000L,
            "Some Track",
            "spotify:track:abc123",
            ImageUri("spotify:image:ghi789"),
            false,
            false,
        ),
        false,
        1.0f,
        0L,
        PlayerOptions(false, 0),
        PlayerRestrictions(true, true, true, true, true, true),
    )

    @Test
    fun `a normal player state yields the current track's artist uri and name`() {
        val state = playerStateWithArtist("Daft Punk", "spotify:artist:xyz")
        assertEquals(CurrentArtist("spotify:artist:xyz", "Daft Punk"), SpotifyController.currentArtistFrom(state))
    }

    @Test
    fun `null player state (nothing playing, or App Remote never pushed) yields no artist`() {
        assertNull(SpotifyController.currentArtistFrom(null))
    }

    @Test
    fun `a blank artist uri yields no artist rather than a broken one`() {
        val state = playerStateWithArtist("Daft Punk", "")
        assertNull(SpotifyController.currentArtistFrom(state))
    }

    @Test
    fun `a blank artist name yields no artist rather than a broken one`() {
        val state = playerStateWithArtist("", "spotify:artist:xyz")
        assertNull(SpotifyController.currentArtistFrom(state))
    }
}
