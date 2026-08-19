package com.kevin.legion.car

import androidx.media3.common.Player
import com.kevin.legion.media.NowPlayingInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [describeProxyState] - the pure decision behind [LegionProxyPlayer.getState],
 * pulled out specifically so this mapping is a plain JVM test (no Robolectric, no Looper) rather
 * than something only exercisable by tapping the transport bar on a head unit. See
 * [LegionMediaLibraryService]'s class doc, WAVE 5 paragraph, for the design this backs: the media
 * card must show nothing playing when nothing IS, must never lie about play/pause, and must never
 * claim to be loading/buffering something it already has metadata for.
 */
class LegionProxyPlayerStateTest {

    private fun info(isPlaying: Boolean) = NowPlayingInfo(
        title = "Song",
        artist = "Artist",
        album = "Album",
        isPlaying = isPlaying,
        position = 1_000L,
        duration = 200_000L,
    )

    @Test
    fun `nothing playing anywhere LEGION can see reports no item and IDLE`() {
        val description = describeProxyState(null)
        assertFalse("a null NowPlayingInfo must never report an item", description.hasItem)
        assertEquals(Player.STATE_IDLE, description.playbackState)
        assertFalse("must not claim to be playing when there is nothing to play", description.playWhenReady)
    }

    @Test
    fun `a paused real track reports an item, READY, and playWhenReady false`() {
        val description = describeProxyState(info(isPlaying = false))
        assertTrue(description.hasItem)
        assertEquals(Player.STATE_READY, description.playbackState)
        assertFalse("PAUSE must render as paused, not as still playing", description.playWhenReady)
    }

    @Test
    fun `a playing real track reports an item, READY, and playWhenReady true`() {
        val description = describeProxyState(info(isPlaying = true))
        assertTrue(description.hasItem)
        assertEquals(Player.STATE_READY, description.playbackState)
        assertTrue(description.playWhenReady)
    }

    @Test
    fun `playWhenReady follows the STRICT isPlaying flag, not the lenient isActive one`() {
        // isActive defaults to isPlaying when not given explicitly, so construct a case where
        // they'd diverge (AVRCP metadata present but no explicit STATE_PLAYING report) to prove
        // describeProxyState reads the strict field and not a "music looks up" cosmetic guess -
        // a transport bar that says PLAYING is a factual claim, per this file's own doc.
        val strictlyPaused = NowPlayingInfo(
            title = "Song",
            artist = "Artist",
            album = "Album",
            isPlaying = false,
            isActive = true,
            position = 0L,
            duration = 0L,
        )
        val description = describeProxyState(strictlyPaused)
        assertFalse(description.playWhenReady)
    }
}
