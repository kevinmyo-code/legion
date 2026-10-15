package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is left of the old Web API fallback tests after 2026-10-15: only the App Remote pacing
 * decisions. Device picking, transport over HTTP and the launch policy were deleted with the code.
 */
class SpotifyConnectLogicTest {

    @Test fun `App Remote is tried when connected, in flight, never failed, or failed long ago`() {
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(isConnected = true, attemptInFlight = false, msSinceLastFailure = 1))
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(isConnected = false, attemptInFlight = true, msSinceLastFailure = 1))
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(isConnected = false, attemptInFlight = false, msSinceLastFailure = null))
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(false, false, SpotifyConnectLogic.RECENT_FAILURE_MS))
    }

    @Test fun `a fresh App Remote failure fails fast instead of waiting on another attempt`() {
        assertFalse(SpotifyConnectLogic.shouldTryAppRemote(isConnected = false, attemptInFlight = false, msSinceLastFailure = 2_000))
    }

    @Test fun `reconnect schedule is three increasing waits`() {
        val s = SpotifyConnectLogic.RECONNECT_SCHEDULE_MS
        assertEquals(3, s.size)
        assertEquals(s.sorted(), s)
    }
}
