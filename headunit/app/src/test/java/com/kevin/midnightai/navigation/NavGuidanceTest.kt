package com.kevin.midnightai.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review NAV-1: when the phone stops sending, the NAV face never shows an old turn, time or distance
 * as current. Past 3 s (the CAR face's rule) the words say the position is lost; the next fresh fix
 * brings guidance back.
 */
class NavGuidanceTest {
    private val guiding = NavState(
        NavPhase.GUIDING,
        "Guiding to home.",
        NavDestination("home", GeoPoint(29.76, -95.37)),
        progress = NavProgress(600.0, 8_000.0, NavTurn("Turn left onto Main St", 480.0, "turn", "left")),
    )

    @Test fun aFreshFixShowsTheTurnTimeAndDistance() {
        val lines = NavGuidance.lines(guiding, fixAgeMs = 900)
        assertFalse(lines.stale)
        assertEquals("In 0.3 mi: Turn left onto Main St", lines.banner)
        assertTrue(lines.bottom, lines.bottom.contains("10 min left"))
    }

    @Test fun aStaleFixHidesTheTurnTimeAndDistanceInWords() {
        val lines = NavGuidance.lines(guiding, fixAgeMs = 3_100)
        assertTrue(lines.stale)
        assertEquals(NavGuidance.POSITION_LOST, lines.banner)
        assertFalse("an old instruction shown as current: ${lines.bottom}", lines.bottom.contains("min left") || lines.bottom.contains("Main St"))
    }

    @Test fun noFixAtAllIsStale() = assertTrue(NavGuidance.lines(guiding, fixAgeMs = null).stale)

    @Test fun notGuidingShowsTheStateWords() {
        val idle = NavState(NavPhase.IDLE, NavController.IDLE_WORDS)
        val lines = NavGuidance.lines(idle, fixAgeMs = null)
        assertNull(lines.banner)
        assertEquals(NavController.IDLE_WORDS, lines.bottom)
    }
}
