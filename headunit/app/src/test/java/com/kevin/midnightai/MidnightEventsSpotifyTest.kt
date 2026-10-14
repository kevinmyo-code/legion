package com.kevin.midnightai

import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotify Crashlytics helpers' pure parts: the failure label and the once-per-class rate limit
 * on the connect-failure non-fatal. The Firebase calls themselves are wrapped in safe() and are a
 * no-op on a plain JVM; these tests also prove calling them here never throws.
 */
class MidnightEventsSpotifyTest {

    @Test fun labelNamesClassAndMessage() {
        assertEquals("none", MidnightEvents.spotifyFailureLabel(null))
        assertEquals("NotLoggedInException: x", MidnightEvents.spotifyFailureLabel(NotLoggedInException("x", null)))
        assertEquals("IllegalStateException", MidnightEvents.spotifyFailureLabel(IllegalStateException()))
    }

    @Test fun labelCapsAndFlattensTheMessage() {
        val label = MidnightEvents.spotifyFailureLabel(RuntimeException("a\nb" + "z".repeat(500)))
        assertFalse(label.contains('\n'))
        assertTrue(label.length <= "RuntimeException: ".length + 60)
    }

    @Test fun eachFailureClassIsReportedOncePerProcess() {
        // A class no other test uses, so test order cannot pre-mark it.
        class OddFailure : Exception()
        assertTrue(MidnightEvents.firstSpotifyFailureOfItsKind(OddFailure()))
        assertFalse(MidnightEvents.firstSpotifyFailureOfItsKind(OddFailure()))
        assertFalse(MidnightEvents.firstSpotifyFailureOfItsKind(null))
    }

    @Test fun differentClassesAreTrackedSeparately() {
        class FailureA : Exception()
        class FailureB : Exception()
        assertTrue(MidnightEvents.firstSpotifyFailureOfItsKind(FailureA()))
        assertTrue(MidnightEvents.firstSpotifyFailureOfItsKind(FailureB()))
    }

    @Test fun firebaseCallsNeverThrowOffDevice() {
        MidnightEvents.spotifyConnectFinished(false, true, CouldNotFindSpotifyApp())
        MidnightEvents.spotifyConnectFinished(true, false, null)
        MidnightEvents.spotifyDisconnected()
        MidnightEvents.spotifyClientIdSet(true)
        MidnightEvents.spotifyWebAuthorized(false)
        MidnightEvents.playMusicResult("unavailable_NotLoggedInException", "Bad Bunny", late = true)
    }
}
