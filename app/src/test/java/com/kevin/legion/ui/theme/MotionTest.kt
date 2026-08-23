package com.kevin.legion.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared motion vocabulary (command-center ticket 14) and the pure gate that keeps
 * entrance/state-change animation off an ALARM surface. This is deliberately the "pure parts"
 * half of the ticket's verification - [LegionMotion] and [deckEntranceEnabled] have no Compose
 * runtime dependency, so a plain JVM unit test can pin them without Robolectric or a Compose
 * test rule. [legionPressScale] itself is a `@Composable` and is exercised on-device, not here.
 */
class MotionTest {

    @Test
    fun `route fade is fast, under the ticket's own ceiling for a route switch`() {
        assertTrue(LegionMotion.ROUTE_FADE_MS in 1..250)
    }

    @Test
    fun `press response is shorter than a route fade - a tap has to feel instant`() {
        assertTrue(LegionMotion.PRESS_MS < LegionMotion.ROUTE_FADE_MS)
    }

    @Test
    fun `press scale is subtle - a shrink, not a bounce`() {
        assertTrue(LegionMotion.PRESS_SCALE in 0.9f..0.99f)
    }

    @Test
    fun `pane entrance stays under the ticket's 250ms ceiling`() {
        assertTrue(LegionMotion.PANE_ENTRANCE_MS < 250)
    }

    @Test
    fun `content-change duration matches pane entrance so the two motions read as one vocabulary`() {
        assertEquals(LegionMotion.PANE_ENTRANCE_MS, LegionMotion.CONTENT_CHANGE_MS)
    }

    @Test
    fun `pulse duration is the one AssistantStrip's PhaseDot already used, not a second invented value`() {
        assertEquals(700, LegionMotion.PULSE_MS)
    }

    // -------------------------------------------------------- deckEntranceEnabled

    @Test
    fun `entrance runs on an ordinary pane with motion enabled`() {
        assertTrue(deckEntranceEnabled(alarm = false, motionEnabled = true))
    }

    @Test
    fun `entrance is off for an ALARM pane even with motion enabled - it must appear instantly`() {
        assertTrue(!deckEntranceEnabled(alarm = true, motionEnabled = true))
    }

    @Test
    fun `entrance is off under reduced motion even for an ordinary pane`() {
        assertTrue(!deckEntranceEnabled(alarm = false, motionEnabled = false))
    }

    @Test
    fun `entrance stays off when a pane is somehow both alarming and reduced-motion`() {
        assertTrue(!deckEntranceEnabled(alarm = true, motionEnabled = false))
    }
}
