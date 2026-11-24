package com.kevin.midnightai.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round edd3a320, item 5: while the car moves no map panning and no list scrolling; single taps stay. */
class NavInputTest {
    @Test fun movingTurnsOffPanningAndScrollingButNotTaps() {
        assertFalse(NavInput.mapPanAllowed(driving = true))
        assertFalse(NavInput.listScrollAllowed(driving = true))
        assertTrue(NavInput.tapsAllowed(driving = true))
    }

    /**
     * Review confirm 0cac90f7: when the phone feed stops while the car was moving, the pan/scroll
     * lock stays until a FRESH fix says the car has stopped, not until a timed hold runs out.
     */
    @Test fun aFeedThatStoppedWhileMovingKeepsTheLock() {
        assertTrue("unlocked on a stale moving fix", NavInput.locked(drivingNow = false, lastSpeedMps = 12f, fixFresh = false))
        assertFalse(NavInput.locked(drivingNow = false, lastSpeedMps = 0f, fixFresh = true))
        assertFalse(NavInput.locked(drivingNow = false, lastSpeedMps = 0.5f, fixFresh = false))
        assertTrue(NavInput.locked(drivingNow = true, lastSpeedMps = null, fixFresh = true))
    }

    @Test fun parkedAllowsAll() {
        assertTrue(NavInput.mapPanAllowed(driving = false))
        assertTrue(NavInput.listScrollAllowed(driving = false))
    }
}
