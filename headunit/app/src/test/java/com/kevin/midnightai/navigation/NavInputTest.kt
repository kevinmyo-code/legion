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

    @Test fun parkedAllowsAll() {
        assertTrue(NavInput.mapPanAllowed(driving = false))
        assertTrue(NavInput.listScrollAllowed(driving = false))
    }
}
