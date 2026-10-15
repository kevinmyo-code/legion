package com.kevin.midnightai.media

import com.kevin.midnightai.media.SpotifyVoiceLogic.MusicAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyVoiceLogicTest {

    // ---------------------------------------------------------------- actions

    @Test fun `every action round-trips through its wire value`() {
        MusicAction.entries.forEach { assertEquals(it, MusicAction.fromWire(it.wire)) }
        assertEquals(MusicAction.entries.size, SpotifyVoiceLogic.WIRE_VALUES.toSet().size)
    }

    @Test fun `an unknown wire value is null, not a guess`() {
        assertNull(MusicAction.fromWire("teleport"))
        assertNull(MusicAction.fromWire(""))
    }

    @Test fun `the four long-standing transport actions are NOT spotify-only, everything else is`() {
        val transport = setOf(MusicAction.PLAY, MusicAction.PAUSE, MusicAction.NEXT, MusicAction.PREVIOUS)
        MusicAction.entries.forEach { assertEquals(it !in transport, it.spotifyOnly) }
    }

    @Test fun `the wire values the model is offered start with the original four`() {
        assertEquals(listOf("play", "pause", "next", "previous"), SpotifyVoiceLogic.WIRE_VALUES.take(4))
    }

    // ---------------------------------------------------------------- the source gate

    @Test fun `head-unit Spotify may proceed`() {
        assertNull(SpotifyVoiceLogic.sourceBlocker(Source.SPOTIFY))
    }

    @Test fun `phone and mixtape sources are refused with a sentence naming why`() {
        val phone = SpotifyVoiceLogic.sourceBlocker(Source.PHONE)
        val tape = SpotifyVoiceLogic.sourceBlocker(Source.MIXTAPE)
        assertNotNull(phone)
        assertNotNull(tape)
        assertTrue(phone!!.contains("phone"))
        assertTrue(tape!!.contains("mixtape"))
        assertNotEquals(phone, tape)
        // The refusal tells the driver the way out, so the model can relay it.
        assertTrue(phone.contains("head unit"))
    }

    // ---------------------------------------------------------------- seek

    @Test fun `seek direction sets the sign and seconds become milliseconds`() {
        assertEquals(30_000L, SpotifyVoiceLogic.seekDeltaMs(forward = true, seconds = 30))
        assertEquals(-45_000L, SpotifyVoiceLogic.seekDeltaMs(forward = false, seconds = 45))
    }

    @Test fun `seek seconds are clamped to 1 through 600, and the spoken line reports the clamped value`() {
        assertEquals(1_000L, SpotifyVoiceLogic.seekDeltaMs(true, 0))
        assertEquals(1_000L, SpotifyVoiceLogic.seekDeltaMs(true, -5))
        assertEquals(600_000L, SpotifyVoiceLogic.seekDeltaMs(true, 99_999))
        assertEquals("Jumped forward 600 seconds.", SpotifyVoiceLogic.seekMessage(true, 99_999))
        assertEquals("Jumped back 30 seconds.", SpotifyVoiceLogic.seekMessage(false, 30))
    }

    // ---------------------------------------------------------------- limits and types

    @Test fun `clampLimit defaults to 5 and never exceeds 10 or drops below 1`() {
        assertEquals(5, SpotifyVoiceLogic.clampLimit(0))
        assertEquals(5, SpotifyVoiceLogic.clampLimit(-3))
        assertEquals(3, SpotifyVoiceLogic.clampLimit(3))
        assertEquals(10, SpotifyVoiceLogic.clampLimit(50))
        assertEquals(1, SpotifyVoiceLogic.clampLimit(1))
    }

    @Test fun `type normalisation keeps the three named kinds and treats anything else as a song`() {
        assertEquals("artist", SpotifyVoiceLogic.normalizeType("Artist"))
        assertEquals("album", SpotifyVoiceLogic.normalizeType(" album "))
        assertEquals("playlist", SpotifyVoiceLogic.normalizeType("playlist"))
        assertEquals("song", SpotifyVoiceLogic.normalizeType("song"))
        assertEquals("song", SpotifyVoiceLogic.normalizeType(""))
        assertEquals("song", SpotifyVoiceLogic.normalizeType(null))
        assertEquals("song", SpotifyVoiceLogic.normalizeType("podcast"))
    }

    @Test fun `song is Spotify's track and the rest pass through`() {
        assertEquals("track", SpotifyVoiceLogic.searchType("song"))
        assertEquals("album", SpotifyVoiceLogic.searchType("album"))
        assertEquals("playlist", SpotifyVoiceLogic.searchType("playlist"))
    }

    // ---------------------------------------------------------------- playlist fallback honesty

    @Test fun `a playlist fallback names what actually played and says it was not among the driver's own`() {
        val note = SpotifyVoiceLogic.playlistFallbackNote("Roadtrip", "Road Trip Hits", libraryChecked = true)
        assertTrue(note.contains("Road Trip Hits"))
        assertTrue(note.contains("among your own playlists"))
    }

    @Test fun `when the library was never read the note must not claim it was searched`() {
        val note = SpotifyVoiceLogic.playlistFallbackNote("Roadtrip", "Road Trip Hits", libraryChecked = false)
        assertFalse(note.contains("among your own playlists"))
        assertTrue(note.contains("couldn't read your own playlists"))
    }

    @Test fun `a fallback with no name still reads cleanly`() {
        assertFalse(SpotifyVoiceLogic.playlistFallbackNote("Roadtrip", null, libraryChecked = true).contains("null"))
    }

    // --- get_now_playing and the live-context source phrase (2026-10-15) -------------------------

    @Test fun `now playing names title, artist, album, state and the real source`() {
        val m = SpotifyVoiceLogic.nowPlayingMessage(
            SpotifyVoiceLogic.NowPlayingFacts("Mask Off", "Future", "FUTURE", Source.SPOTIFY, playing = true),
        )!!
        assertTrue(m.contains("\"Mask Off\" by Future"))
        assertTrue(m.contains("album \"FUTURE\""))
        assertTrue(m.contains("is playing"))
        assertTrue(m.contains("Spotify on the head unit"))
    }

    @Test fun `a paused phone track says paused and over Bluetooth, never the head unit`() {
        val m = SpotifyVoiceLogic.nowPlayingMessage(
            SpotifyVoiceLogic.NowPlayingFacts("Plastic Love", "", "", Source.PHONE, playing = false),
        )!!
        assertTrue(m.contains("is paused"))
        assertTrue(m.contains("phone over Bluetooth"))
        assertFalse(m.contains(" by "))
        assertFalse(m.contains("album"))
    }

    @Test fun `no track reported yields no sentence rather than an invented one`() {
        assertNull(SpotifyVoiceLogic.nowPlayingMessage(null))
        assertNull(SpotifyVoiceLogic.nowPlayingMessage(SpotifyVoiceLogic.NowPlayingFacts(" ", "x", "y", Source.PHONE, true)))
        assertFalse(SpotifyVoiceLogic.NOTHING_REPORTED.contains('\u2014'))
    }

    @Test fun `the source phrase is different for every source (F14)`() {
        val phrases = Source.entries.map { SpotifyVoiceLogic.sourcePhrase(it) }
        assertEquals(Source.entries.size, phrases.toSet().size)
        assertFalse(SpotifyVoiceLogic.sourcePhrase(Source.PHONE).contains("head unit"))
    }
}
