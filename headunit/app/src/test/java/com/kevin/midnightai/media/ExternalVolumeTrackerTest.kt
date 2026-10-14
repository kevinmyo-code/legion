package com.kevin.midnightai.media

import com.kevin.midnightai.media.ExternalVolumeTracker.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalVolumeTrackerTest {

    @Test fun firstSightingOnlySeeds() {
        val t = ExternalVolumeTracker(quietMs = 600)
        assertEquals(Decision.IGNORE, t.onObserved(7, nowMs = 1_000))
    }

    @Test fun unchangedIndexIsIgnored() {
        val t = ExternalVolumeTracker(quietMs = 600)
        t.onObserved(7, 1_000)
        assertEquals(Decision.IGNORE, t.onObserved(7, 2_000))
    }

    @Test fun hardwareKeyChangeIsReasserted() {
        val t = ExternalVolumeTracker(quietMs = 600)
        t.onObserved(7, 1_000)
        assertEquals(Decision.REASSERT, t.onObserved(8, 2_000))
        // Same index arriving again via the second source (observer after broadcast).
        assertEquals(Decision.IGNORE, t.onObserved(8, 2_010))
    }

    @Test fun ownWriteEchoesAreDeferredNotReasserted() {
        val t = ExternalVolumeTracker(quietMs = 600)
        t.onObserved(7, 1_000)
        t.onOwnWrite(8, nowMs = 2_000)
        // The two-call re-assert briefly passes through 7 then lands on 8: both are ours.
        assertEquals(Decision.DEFER, t.onObserved(7, 2_050))
        assertEquals(Decision.DEFER, t.onObserved(8, 2_100))
        assertEquals(500L, t.quietRemaining(2_100))
        // After the window, the settled index matches what we wrote: no loop.
        assertEquals(Decision.IGNORE, t.onObserved(8, 2_700))
    }

    @Test fun keyPressDuringQuietWindowIsCaughtByRecheck() {
        val t = ExternalVolumeTracker(quietMs = 600)
        t.onOwnWrite(8, 2_000)
        assertEquals(Decision.DEFER, t.onObserved(9, 2_200))
        // The recheck after the window sees 9, which differs from our 8.
        assertEquals(Decision.REASSERT, t.onObserved(9, 2_601))
    }

    // --- voice-turn hold (2026-10-13: music dropped out on every voice tap) -------------------

    @Test fun changesWhileVoiceHoldsFocusAreAdoptedNotReasserted() {
        val t = ExternalVolumeTracker(quietMs = 600, holdGraceMs = 3_000)
        t.onObserved(10, 1_000)
        t.setHeld(true, 2_000)
        // The head unit ducks by moving the index itself: not a key press.
        assertEquals(Decision.IGNORE, t.onObserved(4, 2_100))
        t.setHeld(false, 5_000)
        // The un-duck lands inside the grace window: also not a key press.
        assertEquals(Decision.IGNORE, t.onObserved(10, 6_000))
        // After the grace window a real key press is re-asserted again, against the adopted index.
        assertEquals(Decision.REASSERT, t.onObserved(11, 8_100))
    }

    @Test fun holdGraceStartsAtRelease() {
        val t = ExternalVolumeTracker(quietMs = 600, holdGraceMs = 3_000)
        t.onObserved(10, 0)
        t.setHeld(true, 1_000)
        t.setHeld(false, 50_000)
        assertEquals(Decision.IGNORE, t.onObserved(9, 52_999))
        assertEquals(Decision.REASSERT, t.onObserved(8, 53_000))
    }

    // --- loop breaker ---------------------------------------------------------------------------

    @Test fun breakerTripsOnARunawayLoopAndRecovers() {
        val t = ExternalVolumeTracker(
            quietMs = 0, holdGraceMs = 0, breakerWindowMs = 10_000, breakerMax = 3, breakerOpenMs = 30_000,
        )
        t.onObserved(10, 0)
        assertEquals(Decision.REASSERT, t.onObserved(11, 100))
        assertEquals(Decision.REASSERT, t.onObserved(12, 200))
        assertEquals(Decision.REASSERT, t.onObserved(13, 300))
        assertEquals(0, t.breakerTrips)
        // Fourth inside the window: a loop. Trips and stays open.
        assertEquals(Decision.IGNORE, t.onObserved(14, 400))
        assertEquals(1, t.breakerTrips)
        assertEquals(Decision.IGNORE, t.onObserved(15, 20_000))
        // Closed again after the open period.
        assertEquals(Decision.REASSERT, t.onObserved(16, 30_401))
    }

    @Test fun reassertsSpreadOutsideTheWindowNeverTrip() {
        val t = ExternalVolumeTracker(
            quietMs = 0, holdGraceMs = 0, breakerWindowMs = 1_000, breakerMax = 2, breakerOpenMs = 30_000,
        )
        t.onObserved(0, 0)
        for (i in 1..10) assertEquals(Decision.REASSERT, t.onObserved(i, i * 600L))
        assertEquals(0, t.breakerTrips)
    }

    @Test fun quietRemainingNeverNegative() {
        val t = ExternalVolumeTracker(quietMs = 600)
        assertEquals(0L, t.quietRemaining(5_000))
        t.onOwnWrite(3, 5_000)
        assertEquals(0L, t.quietRemaining(9_000))
    }
}
