package com.kevin.midnightai.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRankingTest {
    private val none = PlaybackState.STATE_NONE
    private val playing = PlaybackState.STATE_PLAYING
    private val paused = PlaybackState.STATE_PAUSED

    @Test fun emptyListPicksNothing() {
        assertNull(pickBestSession(emptyList()))
    }

    @Test fun btNoneWithMetadataBeatsStalePausedSpotify() {
        // Spotify listed first (as after a BT reconnect); the BT session must still win.
        val spotify = SessionCandidate(paused, hasTitle = true, packageName = "com.spotify.music")
        val bt = SessionCandidate(none, hasTitle = true, packageName = "com.android.bluetooth")
        assertEquals(1, pickBestSession(listOf(spotify, bt)))
    }

    @Test fun bothNoneOneWithoutMetadataPicksTheOneWithMetadata() {
        val bare = SessionCandidate(none, hasTitle = false)
        val titled = SessionCandidate(none, hasTitle = true)
        assertEquals(1, pickBestSession(listOf(bare, titled)))
    }

    @Test fun playingAlwaysWins() {
        val titledNone = SessionCandidate(none, hasTitle = true)
        val playingBare = SessionCandidate(playing, hasTitle = false)
        assertEquals(1, pickBestSession(listOf(titledNone, playingBare)))
    }

    @Test fun pausedWithTitleBeatsEmptySession() {
        val empty = SessionCandidate(none, hasTitle = false)
        val pausedTitled = SessionCandidate(paused, hasTitle = true)
        assertEquals(1, pickBestSession(listOf(empty, pausedTitled)))
    }

    @Test fun tiesGoToTheEarliestEntry() {
        val a = SessionCandidate(none, hasTitle = true)
        val b = SessionCandidate(none, hasTitle = true)
        assertEquals(0, pickBestSession(listOf(a, b)))
    }

    @Test fun tiePrefersSessionMatchingActiveSource() {
        val bt = SessionCandidate(none, hasTitle = true, packageName = "com.android.bluetooth")
        val spotify = SessionCandidate(none, hasTitle = true, packageName = "com.spotify.music")
        assertEquals(1, pickBestSession(listOf(bt, spotify)) { it == "com.spotify.music" })
        assertEquals(0, pickBestSession(listOf(bt, spotify)) { it != "com.spotify.music" })
    }

    @Test fun preferenceNeverOverridesHigherRank() {
        val playingBt = SessionCandidate(playing, hasTitle = true, packageName = "com.android.bluetooth")
        val spotify = SessionCandidate(none, hasTitle = true, packageName = "com.spotify.music")
        assertEquals(0, pickBestSession(listOf(playingBt, spotify)) { it == "com.spotify.music" })
    }

    @Test fun tieWithNoPreferredMatchKeepsListOrder() {
        val a = SessionCandidate(none, hasTitle = true, packageName = "a")
        val b = SessionCandidate(none, hasTitle = true, packageName = "b")
        assertEquals(0, pickBestSession(listOf(a, b)) { false })
    }

    @Test fun allEmptyFallsBackToFirst() {
        val a = SessionCandidate(none, hasTitle = false)
        val b = SessionCandidate(paused, hasTitle = false)
        assertEquals(0, pickBestSession(listOf(a, b)))
    }
}
