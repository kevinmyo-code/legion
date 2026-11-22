package com.kevin.legion.service

import com.kevin.legion.service.MusicControlToolbox.MusicAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM coverage for [MusicAction] (ticket 03,
 * `.scratch/spotify-voice/issues/03-tool-surface.md`) - the action-dispatch shape `control_music`
 * grows into so the map's remaining music tickets (04-06) can fold in as enum entries rather than
 * spawning sibling tool declarations.
 *
 * [MusicAction.fromWire] is a plain function over a plain string, same shape as
 * the navigation tools' pure parsers (it used to name the retired
 * `NavigationController.uriFor`) - no Context, no coroutines, no Spotify
 * SDK.
 */
class LiveToolboxMusicActionTest {

    @Test
    fun `every current wire value round-trips to its own action`() {
        assertEquals(MusicAction.PLAY, MusicAction.fromWire("play"))
        assertEquals(MusicAction.PAUSE, MusicAction.fromWire("pause"))
        assertEquals(MusicAction.NEXT, MusicAction.fromWire("next"))
        assertEquals(MusicAction.PREVIOUS, MusicAction.fromWire("previous"))
        // Landed ticket 04: queue shares one action with add-to-queue semantics only.
        assertEquals(MusicAction.QUEUE, MusicAction.fromWire("queue"))
        // Landed ticket 05: library writes on whatever's currently playing.
        assertEquals(MusicAction.LIKE, MusicAction.fromWire("like"))
        assertEquals(MusicAction.UNLIKE, MusicAction.fromWire("unlike"))
        assertEquals(MusicAction.FOLLOW_ARTIST, MusicAction.fromWire("follow_artist"))
        assertEquals(MusicAction.UNFOLLOW_ARTIST, MusicAction.fromWire("unfollow_artist"))
        // Landed ticket 06: shuffle, repeat, seek, restart.
        assertEquals(MusicAction.SHUFFLE, MusicAction.fromWire("shuffle"))
        assertEquals(MusicAction.SHUFFLE_ON, MusicAction.fromWire("shuffle_on"))
        assertEquals(MusicAction.SHUFFLE_OFF, MusicAction.fromWire("shuffle_off"))
        assertEquals(MusicAction.REPEAT_OFF, MusicAction.fromWire("repeat_off"))
        assertEquals(MusicAction.REPEAT_TRACK, MusicAction.fromWire("repeat_track"))
        assertEquals(MusicAction.REPEAT_CONTEXT, MusicAction.fromWire("repeat_context"))
        assertEquals(MusicAction.SEEK_FORWARD, MusicAction.fromWire("seek_forward"))
        assertEquals(MusicAction.SEEK_BACK, MusicAction.fromWire("seek_back"))
        assertEquals(MusicAction.RESTART, MusicAction.fromWire("restart"))
        // Landed ticket 08: add the currently playing track to a named playlist.
        assertEquals(MusicAction.ADD_TO_PLAYLIST, MusicAction.fromWire("add_to_playlist"))
        // Landed ticket 13: catalogue navigation off the current artist, no search.
        assertEquals(MusicAction.MORE_FROM_ARTIST, MusicAction.fromWire("more_from_artist"))
    }

    @Test
    fun `every MusicAction entry that exists today has landed - no future placeholder entries`() {
        // Ticket 03's discipline (the schema `enum` and this parser never claim more than is
        // actually wired) means there is no longer a "not landed yet" set to assert against -
        // tickets 03-06, 08 and 13 are all in on this map. Asserting the exact count instead pins
        // the map's full surface as a single number that must be touched deliberately if it ever
        // grows again.
        //
        // It earned its keep at the 08/13 merge: both branches independently bumped this from 18
        // to 19, and the merged enum holds 20. Nothing else in the suite would have noticed that
        // the two action sets had been added past each other.
        assertEquals(20, MusicAction.entries.size)
    }

    @Test
    fun `garbage and blank input is unrecognized, never a crash`() {
        assertNull(MusicAction.fromWire(""))
        assertNull(MusicAction.fromWire("PLAY"))
        assertNull(MusicAction.fromWire("play "))
        assertNull(MusicAction.fromWire("nonsense"))
    }
}
