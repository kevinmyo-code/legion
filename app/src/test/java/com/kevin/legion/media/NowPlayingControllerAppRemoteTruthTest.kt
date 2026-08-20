package com.kevin.legion.media

import android.media.session.PlaybackState
import com.spotify.protocol.types.Album
import com.spotify.protocol.types.Artist
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.PlayerOptions
import com.spotify.protocol.types.PlayerRestrictions
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [NowPlayingController.infoFromPlayerState] (ticket 07,
 * `.scratch/spotify-voice/issues/07-now-playing-truth.md` scope item 1): App Remote's own pushed
 * [PlayerState] is Spotify's truth, and this is the pure `PlayerState -> NowPlayingInfo` mapping
 * that lets it win over MediaSession metadata. Robolectric only because merely referencing
 * [NowPlayingController] loads its `mainHandler` field - same note as
 * [NowPlayingControllerHistoryTest].
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingControllerAppRemoteTruthTest {

    private fun playerState(
        title: String = "Discovery Track",
        artist: String = "Daft Punk",
        album: String = "Discovery",
        isPaused: Boolean = false,
        positionMs: Long = 42_000L,
        durationMs: Long = 200_000L,
    ) = PlayerState(
        Track(
            Artist(artist, "spotify:artist:xyz"),
            listOf(Artist(artist, "spotify:artist:xyz")),
            Album(album, "spotify:album:def456"),
            durationMs,
            title,
            "spotify:track:abc123",
            ImageUri("spotify:image:ghi789"),
            false,
            false,
        ),
        isPaused,
        1.0f,
        positionMs,
        PlayerOptions(false, 0),
        PlayerRestrictions(true, true, true, true, true, true),
    )

    @Test
    fun `title, artist and album are read straight from the App Remote track`() {
        val info = NowPlayingController.infoFromPlayerState(playerState(), previous = null)
        assertEquals("Discovery Track", info.title)
        assertEquals("Daft Punk", info.artist)
        assertEquals("Discovery", info.album)
    }

    @Test
    fun `isPaused false means playing and active`() {
        val info = NowPlayingController.infoFromPlayerState(playerState(isPaused = false), previous = null)
        assertTrue(info.isPlaying)
        assertTrue(info.isActive)
        assertEquals(PlaybackState.STATE_PLAYING, info.playbackStateRaw)
    }

    @Test
    fun `isPaused true means paused and inactive - App Remote is strict, unlike the AVRCP-lenient reading`() {
        val info = NowPlayingController.infoFromPlayerState(playerState(isPaused = true), previous = null)
        assertFalse(info.isPlaying)
        assertFalse(info.isActive)
        assertEquals(PlaybackState.STATE_PAUSED, info.playbackStateRaw)
    }

    @Test
    fun `position and duration come from playbackPosition and the track's own duration`() {
        val info = NowPlayingController.infoFromPlayerState(playerState(positionMs = 12_345L, durationMs = 210_000L), previous = null)
        assertEquals(12_345L, info.position)
        assertEquals(210_000L, info.duration)
    }

    @Test
    fun `art fields are carried forward from previous rather than invented from App Remote's ImageUri`() {
        val previous = NowPlayingInfo(
            title = "Old Track", artist = "Old Artist", album = "Old Album",
            isPlaying = true, position = 0L, duration = 0L,
            albumArtUri = "file:///cache/album_art_current.jpg", artSource = "bitmap",
        )
        val info = NowPlayingController.infoFromPlayerState(playerState(), previous)
        assertEquals("file:///cache/album_art_current.jpg", info.albumArtUri)
        assertEquals("bitmap", info.artSource)
    }

    @Test
    fun `no previous state means no art rather than a crash`() {
        val info = NowPlayingController.infoFromPlayerState(playerState(), previous = null)
        assertNull(info.albumArtUri)
        assertEquals("none", info.artSource)
    }

    @Test
    fun `a null track name falls back to Unknown title, never a blank string`() {
        val blankTrack = Track(
            Artist("Someone", "spotify:artist:xyz"), emptyList(), Album("Album", "spotify:album:def"),
            0L, "", "spotify:track:abc", ImageUri("spotify:image:ghi"), false, false,
        )
        val ps = PlayerState(
            blankTrack, false, 1.0f, 0L,
            PlayerOptions(false, 0),
            PlayerRestrictions(true, true, true, true, true, true),
        )
        assertEquals("Unknown title", NowPlayingController.infoFromPlayerState(ps, null).title)
    }
}
