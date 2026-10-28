package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR 0065: only a read may run NON_BLOCKING. [LiveToolbox.NON_BLOCKING_READ_TOOLS] is checked
 * against the write set, against the names ruled out by ticket 01, and against what a dispatcher
 * can reach behind it, because a dispatcher is not in [LiveToolbox.isMutatingTool]'s set even when
 * a tool behind it writes. Robolectric for `org.json` (declarations are built as JSON).
 */
@RunWith(RobolectricTestRunner::class)
class LiveNonBlockingReadToolsTest {

    private val set = LiveToolbox.NON_BLOCKING_READ_TOOLS

    /**
     * Writes traced 2026-10-28 that [LiveToolbox.isMutatingTool] does not report. `lookup_vin`
     * upserts the car's spec row (`VehicleSpecController.refreshFromVin`); `ask_advisor` inserts an
     * `advisor_advice` row (`AdvisorAgent.ask`). Either one behind a dispatcher makes it a write.
     */
    private val unlistedWrites = setOf("lookup_vin", "ask_advisor")

    @Test
    fun `no write is ever non-blocking`() {
        for (name in set) assertFalse("$name writes", LiveToolbox.isMutatingTool(name))
        for (name in set) assertFalse("$name writes", name in unlistedWrites)
    }

    @Test
    fun `ticket 01's exclusions stay out`() {
        // ask_engine and aspect_clerk can write; ask_mail's read-through rule keys off its turn.
        for (name in listOf("ask_engine", "aspect_clerk", "ask_mail")) assertFalse(name, name in set)
        // Traced out of the first set: each reaches a write.
        for (name in listOf("ask_fleet", "ask_goals", "ask_advisor")) assertFalse(name, name in set)
    }

    @Test
    fun `nothing read-through is non-blocking`() {
        for (name in set) assertFalse(name, name in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
    }

    @Test
    fun `every name is really declared to the Live socket - no dormant entries`() {
        val decls = LiveToolbox.declarations()
        val live = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }.toSet()
        for (name in set) assertTrue("$name is not on the Live socket, so a stamp would do nothing", name in live)
    }

    @Test
    fun `a non-blocking dispatcher can reach no write behind it`() {
        for (name in set.filter { it.startsWith("ask_") }) {
            val domain = name.removePrefix("ask_")
            val behind = LiveToolbox.DISPATCHED[domain] ?: continue
            for (tool in behind) {
                assertFalse("$name reaches $tool, which writes", LiveToolbox.isMutatingTool(tool))
                assertFalse("$name reaches $tool, which writes", tool in unlistedWrites)
            }
        }
    }
}
