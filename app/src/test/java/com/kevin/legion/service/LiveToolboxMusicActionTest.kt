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
    }

    @Test
    fun `an action ticket 06 has not landed yet is unrecognized, not a guess`() {
        // These are the map's remaining future entries (shuffle_on, shuffle_off, repeat_off,
        // repeat_track, repeat_context, seek_forward, seek_back, restart). Ticket 03's whole
        // point is that the JSON schema `enum` and this parser never claim more than is actually
        // wired - so every one of these must currently fail closed (null) rather than silently
        // succeed against a case that doesn't exist yet.
        for (future in listOf(
            "shuffle_on", "shuffle_off", "repeat_off", "repeat_track", "repeat_context",
            "seek_forward", "seek_back", "restart",
        )) {
            assertNull("\"$future\" has not landed yet and must not parse to an action", LiveToolbox.MusicAction.fromWire(future))
        }
    }

    @Test
    fun `garbage and blank input is unrecognized, never a crash`() {
        assertNull(LiveToolbox.MusicAction.fromWire(""))
        assertNull(LiveToolbox.MusicAction.fromWire("PLAY"))
        assertNull(LiveToolbox.MusicAction.fromWire("play "))
        assertNull(LiveToolbox.MusicAction.fromWire("nonsense"))
    }
}
