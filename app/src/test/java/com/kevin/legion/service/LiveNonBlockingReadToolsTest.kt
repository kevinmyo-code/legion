package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR 0065: only a read may run NON_BLOCKING. A write is a user-visible record change the assistant
 * could claim as an outcome (Kevin, 2026-10-28); internal bookkeeping (`lookup_vin`'s spec-cache
 * refresh, `ask_advisor`'s advice log) is not one. [LiveToolbox.NON_BLOCKING_READ_TOOLS] is checked
 * against the write set, against what a dispatcher can reach behind it, and against the names
 * ticket 01 ruled out. Robolectric for `org.json` (declarations are built as JSON).
 */
@RunWith(RobolectricTestRunner::class)
class LiveNonBlockingReadToolsTest {

    private val set = LiveToolbox.NON_BLOCKING_READ_TOOLS

    @Test
    fun `the set is exactly the four read dispatchers Kevin ruled in`() {
        assertEquals(
            "NON_BLOCKING_READ_TOOLS changed. Read ADR 0065 before editing it: a write is a " +
                "user-visible record change, bookkeeping is not, and every name must stay a read.",
            setOf("ask_fleet", "ask_body", "ask_goals", "ask_pantry"),
            set,
        )
    }

    @Test
    fun `no write is ever non-blocking`() {
        for (name in set) assertFalse("$name writes", LiveToolbox.isMutatingTool(name))
    }

    @Test
    fun `a non-blocking dispatcher can reach no write behind it`() {
        // If this fails for ask_fleet because lookup_vin joined MUTATING_TOOLS, do not just drop
        // fleet: ADR 0065 (Kevin, 2026-10-28) rules lookup_vin's spec-cache refresh bookkeeping,
        // not a write. Decide which of the two rulings changed, and record it there.
        for (name in set.filter { it.startsWith("ask_") }) {
            val domain = name.removePrefix("ask_")
            val behind = LiveToolbox.DISPATCHED[domain] ?: continue
            for (tool in behind) {
                assertFalse(
                    "$name reaches $tool, which isMutatingTool calls a write. See ADR 0065 before " +
                        "removing $name from NON_BLOCKING_READ_TOOLS.",
                    LiveToolbox.isMutatingTool(tool),
                )
            }
        }
    }

    @Test
    fun `ticket 01's exclusions stay out`() {
        // ask_engine and aspect_clerk can write; ask_mail's read-through rule keys off its turn.
        for (name in listOf("ask_engine", "aspect_clerk", "ask_mail")) assertFalse(name, name in set)
    }

    @Test
    fun `nothing read-through is non-blocking`() {
        for (name in set) assertFalse(name, name in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
    }

    @Test
    fun `every name is really declared to the Live socket - no dormant entries`() {
        val decls = LiveToolbox.declarations()
        val live = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }.toSet()
        for (name in set) {
            assertTrue("$name is not on the Live socket, so a stamp would do nothing", name in live)
        }
    }
}
