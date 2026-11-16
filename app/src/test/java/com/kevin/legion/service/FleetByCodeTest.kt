package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ask_fleet only reads: a request to clear codes is refused there in words that name the car tool,
 * while a question about past clears still reaches the helper (2026-11-16, stage 7 fleet eval).
 */
class FleetByCodeTest {

    @Test
    fun `a clear request is refused at ask_fleet, a clear-history question is not`() {
        for (q in listOf("clear the codes", "erase all the codes", "can you reset the check engine light")) {
            assertTrue(q, LiveToolbox.isFleetClearRequest(q))
        }
        for (q in listOf(
            "when did I last clear the codes", "did the clear take", "were the codes cleared",
            "any fault codes?", "scan every module",
        )) {
            assertFalse(q, LiveToolbox.isFleetClearRequest(q))
        }
        assertTrue(LiveToolbox.FLEET_NO_CLEAR.startsWith("Nothing was cleared"))
        assertTrue(LiveToolbox.FLEET_NO_CLEAR.contains("clear_codes"))
    }
}
