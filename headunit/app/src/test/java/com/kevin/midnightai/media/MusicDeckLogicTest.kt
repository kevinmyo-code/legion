package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDeckLogicTest {
    private fun p(id: String) = SpotifyPlaylist(id, id, "spotify:playlist:$id", null)

    @Test fun slotsPadToTenWithEmpties() {
        val s = MusicDeckLogic.slots(listOf(p("a"), p("b"), p("c")))
        assertEquals(10, s.size)
        assertEquals(PinSlot.Pinned(p("a")), s[0])
        assertEquals(PinSlot.Pinned(p("c")), s[2])
        assertTrue(s.drop(3).all { it == PinSlot.Empty })
    }

    @Test fun slotsAllEmptyWithNoPlaylists() {
        assertEquals(List(10) { PinSlot.Empty }, MusicDeckLogic.slots(emptyList()))
    }

    @Test fun slotsCutToSize() {
        val s = MusicDeckLogic.slots((1..12).map { p("p$it") })
        assertEquals(10, s.size)
        assertEquals(PinSlot.Pinned(p("p10")), s.last())
    }

    @Test fun sourceLabels() {
        assertEquals("SPOTIFY", MusicDeckLogic.sourceLabel(Source.SPOTIFY))
        assertEquals("PHONE", MusicDeckLogic.sourceLabel(Source.PHONE))
        assertEquals("MIXTAPE", MusicDeckLogic.sourceLabel(Source.MIXTAPE))
    }

    @Test fun shuffleAndLikeOnlyOnConnectedSpotify() {
        assertTrue(MusicDeckLogic.spotifyExtrasEnabled(Source.SPOTIFY, true))
        assertFalse(MusicDeckLogic.spotifyExtrasEnabled(Source.SPOTIFY, false))
        assertFalse(MusicDeckLogic.spotifyExtrasEnabled(Source.PHONE, true))
        assertFalse(MusicDeckLogic.spotifyExtrasEnabled(Source.MIXTAPE, true))
    }

    @Test fun progressHiddenWithoutDuration() {
        assertFalse(MusicDeckLogic.showProgress(0))
        assertFalse(MusicDeckLogic.showProgress(-1))
        assertTrue(MusicDeckLogic.showProgress(1))
    }

    @Test fun clockFormatting() {
        assertEquals("0:00", MusicDeckLogic.formatClock(0))
        assertEquals("0:00", MusicDeckLogic.formatClock(-5_000))
        assertEquals("0:09", MusicDeckLogic.formatClock(9_999))
        assertEquals("3:02", MusicDeckLogic.formatClock(182_000))
        assertEquals("59:59", MusicDeckLogic.formatClock(3_599_000))
        assertEquals("1:00:00", MusicDeckLogic.formatClock(3_600_000))
        assertEquals("1:02:05", MusicDeckLogic.formatClock(3_725_000))
    }

    @Test fun remainingFormatting() {
        assertEquals("-4:52", MusicDeckLogic.formatRemaining(182_000, 474_000))
        assertEquals("-0:00", MusicDeckLogic.formatRemaining(500_000, 474_000))
    }

    @Test fun livePositionRunsOnlyWhilePlaying() {
        assertEquals(15_000, MusicDeckLogic.livePosition(10_000, 1_000, 6_000, 1f, true, 100_000))
        assertEquals(10_000, MusicDeckLogic.livePosition(10_000, 1_000, 6_000, 1f, false, 100_000))
        // Unknown sample time: no guessing.
        assertEquals(10_000, MusicDeckLogic.livePosition(10_000, 0, 6_000, 1f, true, 100_000))
        // Double speed.
        assertEquals(20_000, MusicDeckLogic.livePosition(10_000, 1_000, 6_000, 2f, true, 100_000))
    }

    @Test fun livePositionClampsToDuration() {
        assertEquals(100_000, MusicDeckLogic.livePosition(99_000, 1_000, 60_000, 1f, true, 100_000))
        // Unknown duration: no upper clamp.
        assertEquals(69_000, MusicDeckLogic.livePosition(10_000, 1_000, 60_000, 1f, true, 0))
    }

    @Test fun fractionIsBounded() {
        assertEquals(0f, MusicDeckLogic.fraction(5, 0), 0f)
        assertEquals(0.5f, MusicDeckLogic.fraction(50, 100), 0.0001f)
        assertEquals(1f, MusicDeckLogic.fraction(150, 100), 0f)
    }

    @Test fun subtitleDropsBlanks() {
        assertEquals("Mariya Takeuchi ・ Night Drive", MusicDeckLogic.subtitle("Mariya Takeuchi", "Night Drive"))
        assertEquals("Mariya Takeuchi", MusicDeckLogic.subtitle("Mariya Takeuchi", " "))
        assertEquals("Variety", MusicDeckLogic.subtitle(null, "Variety"))
        assertEquals("", MusicDeckLogic.subtitle("", null))
    }

    @Test fun playingTileFollowsSpotifysContextOnlyWhileSpotifyOwnsTheSpeakers() {
        val shown = listOf(p("a"), p("b"))
        assertEquals("b", MusicDeckLogic.playingPlaylistId(Source.SPOTIFY, "spotify:playlist:b", shown))
        assertNull(MusicDeckLogic.playingPlaylistId(Source.PHONE, "spotify:playlist:b", shown))
        assertNull(MusicDeckLogic.playingPlaylistId(Source.MIXTAPE, "spotify:playlist:b", shown))
        assertNull(MusicDeckLogic.playingPlaylistId(Source.SPOTIFY, null, shown))
        // An album or an unpinned playlist marks no tile.
        assertNull(MusicDeckLogic.playingPlaylistId(Source.SPOTIFY, "spotify:album:x", shown))
        assertNull(MusicDeckLogic.playingPlaylistId(Source.SPOTIFY, "spotify:playlist:zz", shown))
    }

    @Test fun samePlaylistAcceptsLegacyUserForm() {
        assertTrue(MusicDeckLogic.samePlaylist("spotify:user:kev:playlist:abc", "spotify:playlist:abc"))
        assertTrue(MusicDeckLogic.samePlaylist("spotify:playlist:abc", "spotify:playlist:abc"))
        assertFalse(MusicDeckLogic.samePlaylist("spotify:playlist:abc", "spotify:playlist:abd"))
        assertFalse(MusicDeckLogic.samePlaylist("spotify:album:abc", "spotify:playlist:abc"))
        assertFalse(MusicDeckLogic.samePlaylist("spotify:user:a:playlist:", "spotify:playlist:"))
    }

    // Card inner widths measured off the layout math: 1024x600 (u 10.24), 1280x720/1280x800
    // (u 12.29 / 12.8 capped by height), 800x480 (u 8).
    private val widths = listOf(221.5f, 283.6f, 340.4f, 354.0f)

    @Test fun transportModeFitsEveryRowAtFullHitSize() {
        for (w in widths) {
            when (MusicDeckLogic.transportMode(w)) {
                MusicDeckLogic.TransportMode.ONE_ROW -> {
                    val k = MusicDeckLogic.keySizes(w - 2 * 56f - 4 * 4f, 61.4f, 84f)
                    assertTrue("w=$w", 2 * 56f + 2 * k.mid + k.big + 4 * 4f <= w + 0.01f)
                }
                MusicDeckLogic.TransportMode.TWO_ROWS -> {
                    assertTrue("w=$w", 4 * 56f + 3 * 4f <= w)
                    val k = MusicDeckLogic.keySizes(w - 2 * 4f, 61.4f, 84f)
                    assertTrue("w=$w", 2 * k.mid + k.big + 2 * 4f <= w + 0.01f)
                }
                MusicDeckLogic.TransportMode.THREE_ROWS -> {
                    assertTrue("w=$w", 2 * 56f + 4f <= w)
                    val k = MusicDeckLogic.keySizes(w - 2 * 4f, 48f, 65.6f)
                    assertTrue("w=$w", 2 * k.mid + k.big + 2 * 4f <= w + 0.01f)
                }
            }
        }
    }

    @Test fun transportModeByWidth() {
        assertEquals(MusicDeckLogic.TransportMode.THREE_ROWS, MusicDeckLogic.transportMode(221.5f))
        assertEquals(MusicDeckLogic.TransportMode.TWO_ROWS, MusicDeckLogic.transportMode(283.6f))
        assertEquals(MusicDeckLogic.TransportMode.ONE_ROW, MusicDeckLogic.transportMode(340.4f))
    }

    @Test fun keySizesNeverBelowTouchOrBigFloors() {
        for (avail in listOf(176f, 200f, 213f, 275f, 400f)) {
            val k = MusicDeckLogic.keySizes(avail, 61.4f, 84f)
            assertTrue(k.mid >= 56f)
            assertTrue(k.big >= 64f)
            assertTrue("avail=$avail", 2 * k.mid + k.big <= avail + 0.01f)
        }
        // Room to spare: the prototype's own sizes, not stretched.
        assertEquals(MusicDeckLogic.KeySizes(61.4f, 84f), MusicDeckLogic.keySizes(400f, 61.4f, 84f))
    }

    @Test fun meterOnlyWhenItFits() {
        assertFalse(MusicDeckLogic.meterFits(283.6f, 4))
        assertTrue(MusicDeckLogic.meterFits(300f, 4))
        assertTrue(MusicDeckLogic.meterFits(221.5f, 2))
    }

    @Test fun trackCountIsSingularForOne() {
        assertEquals("1 TRACK", MusicDeckLogic.trackCountLabel(1))
        assertEquals("0 TRACKS", MusicDeckLogic.trackCountLabel(0))
        assertEquals("42 TRACKS", MusicDeckLogic.trackCountLabel(42))
    }

    @Test fun idleCaptionFollowsTheSource() {
        assertTrue(MusicDeckLogic.idleCaption(Source.PHONE).startsWith("Play from your phone"))
        assertEquals("Pick a playlist or press play", MusicDeckLogic.idleCaption(Source.SPOTIFY))
        assertTrue(MusicDeckLogic.idleCaption(Source.MIXTAPE).startsWith("Tape loaded"))
    }
}
