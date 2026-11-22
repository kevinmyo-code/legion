package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a drain wait may give up early (review H1, 2026-11-21). */
class DrainRuleTest {
    @Test
    fun `a slow-starting Bluetooth reply is never given up on before its head first moves`() {
        // The car's sink wakes after 1.2 s: the head has not moved yet, however long that takes.
        for (ms in listOf(0L, 500L, 1_200L, 3_000L)) {
            assertFalse("$ms", DrainRule.stalled(movedOnce = false, sinceLastMoveMs = ms))
        }
        // Once it plays, a pause shorter than the stall is still playing.
        assertFalse(DrainRule.stalled(movedOnce = true, sinceLastMoveMs = 200))
    }

    @Test
    fun `a head that played and then stopped is stalled`() {
        assertTrue(DrainRule.stalled(movedOnce = true, sinceLastMoveMs = DrainRule.STALL_MS))
        assertEquals(500L, DrainRule.STALL_MS)
    }
}
