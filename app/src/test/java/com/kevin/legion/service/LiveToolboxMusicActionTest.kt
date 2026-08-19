package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM coverage for [LiveToolbox.MusicAction] (ticket 03,
 * `.scratch/spotify-voice/issues/03-tool-surface.md`) - the action-dispatch shape `control_music`
 * grows into so the map's remaining music tickets (04-06) can fold in as enum entries rather than
 * spawning sibling tool declarations.
 *
 * [LiveToolbox.MusicAction.fromWire] is a plain function over a plain string, same shape as
 * [com.kevin.legion.location.NavigationController.uriFor] - no Context, no coroutines, no Spotify
 * SDK.
 */
class LiveToolboxMusicActionTest {

    @Test
    fun `every current wire value round-trips to its own action`() {
        assertEquals(LiveToolbox.MusicAction.PLAY, LiveToolbox.MusicAction.fromWire("play"))
        assertEquals(LiveToolbox.MusicAction.PAUSE, LiveToolbox.MusicAction.fromWire("pause"))
        assertEquals(LiveToolbox.MusicAction.NEXT, LiveToolbox.MusicAction.fromWire("next"))
        assertEquals(LiveToolbox.MusicAction.PREVIOUS, LiveToolbox.MusicAction.fromWire("previous"))
        // Landed ticket 04: queue shares one action with add-to-queue semantics only.
        assertEquals(LiveToolbox.MusicAction.QUEUE, LiveToolbox.MusicAction.fromWire("queue"))
        // Landed ticket 05: library writes on whatever's currently playing.
        assertEquals(LiveToolbox.MusicAction.LIKE, LiveToolbox.MusicAction.fromWire("like"))
        assertEquals(LiveToolbox.MusicAction.UNLIKE, LiveToolbox.MusicAction.fromWire("unlike"))
        assertEquals(LiveToolbox.MusicAction.FOLLOW_ARTIST, LiveToolbox.MusicAction.fromWire("follow_artist"))
        assertEquals(LiveToolbox.MusicAction.UNFOLLOW_ARTIST, LiveToolbox.MusicAction.fromWire("unfollow_artist"))
        // Landed ticket 06: shuffle, repeat, seek, restart.
        assertEquals(LiveToolbox.MusicAction.SHUFFLE, LiveToolbox.MusicAction.fromWire("shuffle"))
        assertEquals(LiveToolbox.MusicAction.SHUFFLE_ON, LiveToolbox.MusicAction.fromWire("shuffle_on"))
        assertEquals(LiveToolbox.MusicAction.SHUFFLE_OFF, LiveToolbox.MusicAction.fromWire("shuffle_off"))
        assertEquals(LiveToolbox.MusicAction.REPEAT_OFF, LiveToolbox.MusicAction.fromWire("repeat_off"))
        assertEquals(LiveToolbox.MusicAction.REPEAT_TRACK, LiveToolbox.MusicAction.fromWire("repeat_track"))
        assertEquals(LiveToolbox.MusicAction.REPEAT_CONTEXT, LiveToolbox.MusicAction.fromWire("repeat_context"))
        assertEquals(LiveToolbox.MusicAction.SEEK_FORWARD, LiveToolbox.MusicAction.fromWire("seek_forward"))
        assertEquals(LiveToolbox.MusicAction.SEEK_BACK, LiveToolbox.MusicAction.fromWire("seek_back"))
        assertEquals(LiveToolbox.MusicAction.RESTART, LiveToolbox.MusicAction.fromWire("restart"))
        // Landed ticket 08: add the currently playing track to a named playlist.
        assertEquals(LiveToolbox.MusicAction.ADD_TO_PLAYLIST, LiveToolbox.MusicAction.fromWire("add_to_playlist"))
    }

    @Test
    fun `every MusicAction entry that exists today has landed - no future placeholder entries`() {
        // Ticket 03's discipline (the schema `enum` and this parser never claim more than is
        // actually wired) means there is no longer a "not landed yet" set to assert against -
        // tickets 03-06 (and now 08) are all in on this map. Asserting the exact count instead
        // pins the map's full surface as a single number that must be touched deliberately if it
        // ever grows again.
        assertEquals(19, LiveToolbox.MusicAction.entries.size)
    }

    @Test
    fun `garbage and blank input is unrecognized, never a crash`() {
        assertNull(LiveToolbox.MusicAction.fromWire(""))
        assertNull(LiveToolbox.MusicAction.fromWire("PLAY"))
        assertNull(LiveToolbox.MusicAction.fromWire("play "))
        assertNull(LiveToolbox.MusicAction.fromWire("nonsense"))
    }
}
