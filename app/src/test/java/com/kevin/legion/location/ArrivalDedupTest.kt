package com.kevin.legion.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Arrival announcements are now driven by TWO independent signals - the OS geofence receiver
 * (ticket 05) and the 20-second GPS poll that predates it - and on a real arrival **both will
 * usually fire, seconds apart**. Without a claim, the driver hears the same reminder twice for one
 * arrival.
 *
 * The build that added geofences flagged this as an unresolved fork rather than improvising a
 * mechanism, which was the right call. These tests are the resolution.
 */
class ArrivalDedupTest {

    @Before
    fun setUp() = ArrivalController.resetForTest()

    @Test
    fun `the first signal for a place wins`() {
        assertTrue(ArrivalController.claimAnnouncement("home", 1_000L))
    }

    @Test
    fun `the second signal seconds later is refused - this is the whole point`() {
        assertTrue(ArrivalController.claimAnnouncement("home", 1_000L))
        // The geofence and the poll landing 3 seconds apart is the COMMON case, not the edge.
        assertFalse(ArrivalController.claimAnnouncement("home", 4_000L))
    }

    @Test
    fun `a different place is never suppressed by another place's arrival`() {
        assertTrue(ArrivalController.claimAnnouncement("home", 1_000L))
        assertTrue(ArrivalController.claimAnnouncement("work", 1_500L))
    }

    @Test
    fun `a genuine later return announces again`() {
        assertTrue(ArrivalController.claimAnnouncement("home", 1_000L))
        // Well past the suppression window - leaving and coming back hours later is a real arrival.
        assertTrue(ArrivalController.claimAnnouncement("home", 1_000L + 6 * 60 * 1000L))
    }

    @Test
    fun `announcing again re-arms the window rather than leaving it open`() {
        val t0 = 1_000L
        val later = t0 + 6 * 60 * 1000L
        assertTrue(ArrivalController.claimAnnouncement("home", t0))
        assertTrue(ArrivalController.claimAnnouncement("home", later))
        // The second announcement must start a fresh window, or the next duplicate slips through.
        assertFalse(ArrivalController.claimAnnouncement("home", later + 1_000L))
    }
}
