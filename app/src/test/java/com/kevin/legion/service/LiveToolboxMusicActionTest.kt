package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM coverage for [MusicControlToolbox.MusicAction] (ticket 03,
 * `.scratch/spotify-voice/issues/03-tool-surface.md`) - the action-dispatch shape `control_music`
 * grows into so the map's remaining music tickets (04-06) can fold in as enum entries rather than
 * spawning sibling tool declarations.
 *
 * [MusicControlToolbox.MusicAction.fromWire] is a plain function over a plain string, same shape as
 * the navigation tools' pure parsers (it used to name the retired
 * `NavigationController.uriFor`) - no Context, no coroutines, no Spotify
 * SDK.
 */
class LiveToolboxMusicActionTest {

    @Test
    fun `every current wire value round-trips to its own action`() {
        assertEquals(MusicControlToolbox.MusicAction.PLAY, MusicControlToolbox.MusicAction.fromWire("play"))
        assertEquals(MusicControlToolbox.MusicAction.PAUSE, MusicControlToolbox.MusicAction.fromWire("pause"))
        assertEquals(MusicControlToolbox.MusicAction.NEXT, MusicControlToolbox.MusicAction.fromWire("next"))
        assertEquals(MusicControlToolbox.MusicAction.PREVIOUS, MusicControlToolbox.MusicAction.fromWire("previous"))
        // Landed ticket 04: queue shares one action with add-to-queue semantics only.
        assertEquals(MusicControlToolbox.MusicAction.QUEUE, MusicControlToolbox.MusicAction.fromWire("queue"))
        // Landed ticket 05: library writes on whatever's currently playing.
        assertEquals(MusicControlToolbox.MusicAction.LIKE, MusicControlToolbox.MusicAction.fromWire("like"))
        assertEquals(MusicControlToolbox.MusicAction.UNLIKE, MusicControlToolbox.MusicAction.fromWire("unlike"))
        assertEquals(MusicControlToolbox.MusicAction.FOLLOW_ARTIST, MusicControlToolbox.MusicAction.fromWire("follow_artist"))
        assertEquals(MusicControlToolbox.MusicAction.UNFOLLOW_ARTIST, MusicControlToolbox.MusicAction.fromWire("unfollow_artist"))
        // Landed ticket 06: shuffle, repeat, seek, restart.
        assertEquals(MusicControlToolbox.MusicAction.SHUFFLE, MusicControlToolbox.MusicAction.fromWire("shuffle"))
        assertEquals(MusicControlToolbox.MusicAction.SHUFFLE_ON, MusicControlToolbox.MusicAction.fromWire("shuffle_on"))
        assertEquals(MusicControlToolbox.MusicAction.SHUFFLE_OFF, MusicControlToolbox.MusicAction.fromWire("shuffle_off"))
        assertEquals(MusicControlToolbox.MusicAction.REPEAT_OFF, MusicControlToolbox.MusicAction.fromWire("repeat_off"))
        assertEquals(MusicControlToolbox.MusicAction.REPEAT_TRACK, MusicControlToolbox.MusicAction.fromWire("repeat_track"))
        assertEquals(MusicControlToolbox.MusicAction.REPEAT_CONTEXT, MusicControlToolbox.MusicAction.fromWire("repeat_context"))
        assertEquals(MusicControlToolbox.MusicAction.SEEK_FORWARD, MusicControlToolbox.MusicAction.fromWire("seek_forward"))
        assertEquals(MusicControlToolbox.MusicAction.SEEK_BACK, MusicControlToolbox.MusicAction.fromWire("seek_back"))
        assertEquals(MusicControlToolbox.MusicAction.RESTART, MusicControlToolbox.MusicAction.fromWire("restart"))
        // Landed ticket 08: add the currently playing track to a named playlist.
        assertEquals(MusicControlToolbox.MusicAction.ADD_TO_PLAYLIST, MusicControlToolbox.MusicAction.fromWire("add_to_playlist"))
        // Landed ticket 13: catalogue navigation off the current artist, no search.
        assertEquals(MusicControlToolbox.MusicAction.MORE_FROM_ARTIST, MusicControlToolbox.MusicAction.fromWire("more_from_artist"))
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
        assertEquals(20, MusicControlToolbox.MusicAction.entries.size)
    }

    @Test
    fun `garbage and blank input is unrecognized, never a crash`() {
        assertNull(MusicControlToolbox.MusicAction.fromWire(""))
        assertNull(MusicControlToolbox.MusicAction.fromWire("PLAY"))
        assertNull(MusicControlToolbox.MusicAction.fromWire("play "))
        assertNull(MusicControlToolbox.MusicAction.fromWire("nonsense"))
    }
}
