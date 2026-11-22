package com.kevin.legion.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Read it back first" (2026-11-22): a backlog idea saves only on a clear yes in a later turn. */
class IdeaSaveGateTest {
    private var now = 1_000L
    private val gate = IdeaSaveGate { now }

    @Test
    fun `a clear yes in the next turn saves the idea read back`() {
        gate.register("id-1", "add to the backlog a bigger orb")
        assertEquals(IdeaSaveGate.Decision.Go("id-1"), gate.check("yes please"))
    }

    @Test
    fun `the turn that drafted it cannot also say yes`() {
        gate.register("id-1", "add a bigger orb to the backlog, yes")
        val decision = gate.check("add a bigger orb to the backlog, yes")
        assertEquals(IdeaSaveGate.Decision.Refused(IdeaSaveGate.NOT_ANSWERED_YET), decision)
    }

    @Test
    fun `silence, a no, a hedge or a question is never a yes`() {
        gate.register("id-1", "an idea")
        for (words in listOf("", "no", "yes but change it", "wait", "should I add it?", "maybe later")) {
            assertTrue(words, gate.check(words) is IdeaSaveGate.Decision.Refused)
        }
    }

    @Test
    fun `nothing drafted means nothing to save`() {
        assertEquals(IdeaSaveGate.Decision.Refused(IdeaSaveGate.NOTHING_WAITING), gate.check("yes"))
    }

    @Test
    fun `a stale draft cannot be said yes to`() {
        gate.register("id-1", "an idea")
        now += IdeaSaveGate.TTL_MS + 1
        assertEquals(IdeaSaveGate.Decision.Refused(IdeaSaveGate.NOTHING_WAITING), gate.check("yes"))
    }

    @Test
    fun `explicit yes words`() {
        assertTrue(IdeaSaveGate.isExplicitYes("Yeah, add it."))
        assertTrue(IdeaSaveGate.isExplicitYes("ok save it"))
        assertFalse(IdeaSaveGate.isExplicitYes("not now"))
        assertFalse(IdeaSaveGate.isExplicitYes("sounds good"))
        for (no in listOf("ok forget it", "okay drop it", "go away", "please don't", "do it later")) {
            assertFalse(no, IdeaSaveGate.isExplicitYes(no))
        }
    }
}
