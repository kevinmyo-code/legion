package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What bounds an urgent alert's retries: offline asks nothing, a line that keeps not being spoken
 * stops after its asks, and the next engine start re-arms it.
 */
class AlertRetryBudgetTest {

    private val p0420 = setOf("P0420")

    @Test
    fun `offline asks for nothing and spends nothing`() {
        val budget = AlertRetryBudget()
        repeat(10) { assertEquals(emptySet<String>(), budget.requestable(p0420, online = false)) }
        // Signal comes back with the full budget intact.
        assertEquals(p0420, budget.requestable(p0420, online = true))
    }

    @Test
    fun `three asks, then it holds instead of asking every scan forever`() {
        val budget = AlertRetryBudget()
        repeat(AlertRetryBudget.MAX_REQUESTS) {
            assertEquals("ask ${it + 1}", p0420, budget.requestable(p0420, online = true))
            budget.requested(p0420)
        }
        assertEquals(emptySet<String>(), budget.requestable(p0420, online = true))
    }

    @Test
    fun `a gated ask spends nothing, because the caller only reports what left`() {
        val budget = AlertRetryBudget()
        // requestable said yes, speakProactive gated it: nothing is reported back.
        repeat(10) { budget.requestable(p0420, online = true) }
        assertEquals(p0420, budget.requestable(p0420, online = true))
    }

    @Test
    fun `the engine coming back on re-arms a held line`() {
        val budget = AlertRetryBudget()
        repeat(AlertRetryBudget.MAX_REQUESTS) { budget.requested(p0420) }
        assertEquals(emptySet<String>(), budget.requestable(p0420, online = true))
        budget.onEngineStart()
        assertEquals(p0420, budget.requestable(p0420, online = true))
    }

    @Test
    fun `a spoken line is forgotten, so the same code is news again later`() {
        val budget = AlertRetryBudget()
        budget.requested(p0420)
        budget.settled(p0420)
        repeat(AlertRetryBudget.MAX_REQUESTS) {
            assertEquals(p0420, budget.requestable(p0420, online = true))
            budget.requested(p0420)
        }
        assertEquals(emptySet<String>(), budget.requestable(p0420, online = true))
    }

    @Test
    fun `each key has its own budget`() {
        val budget = AlertRetryBudget()
        val overheat = setOf(AlertRetryBudget.OVERHEAT_KEY)
        repeat(AlertRetryBudget.MAX_REQUESTS) { budget.requested(overheat) }
        assertEquals(emptySet<String>(), budget.requestable(overheat, online = true))
        assertEquals(p0420, budget.requestable(p0420, online = true))
    }

    @Test
    fun `a scan asks only for the codes still within budget`() {
        val budget = AlertRetryBudget()
        repeat(AlertRetryBudget.MAX_REQUESTS) { budget.requested(p0420) }
        val both = setOf("P0420", "P0301")
        assertEquals(setOf("P0301"), budget.requestable(both, online = true))
    }

    @Test
    fun `an exhausted key settles when its episode ends`() {
        val budget = AlertRetryBudget()
        repeat(AlertRetryBudget.MAX_REQUESTS) { budget.requested(p0420) }
        budget.settled(p0420) // the code cleared, or the coolant came down
        assertTrue(budget.requestable(p0420, online = true).isNotEmpty())
    }
}
