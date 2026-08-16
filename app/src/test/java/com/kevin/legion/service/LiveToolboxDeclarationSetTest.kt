package com.kevin.legion.service

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards WHICH declaration set each tool lands in - the failure this file exists for shipped, on
 * 2026-08-13, and no other test could see it.
 *
 * `set_goal`, `list_goals`, `close_goal`, `ask_advisor` and `accept_proposal` were all appended
 * inside [LiveToolbox.onboardingDeclarations] instead of [LiveToolbox.declarations] - the natural
 * mistake when you add a `fns.put(...)` block at the end of a 4,000-line file and the last one you
 * scrolled past happened to belong to the other function. Everything else about them was correct:
 * the handlers worked, [LiveToolbox.dispatch] routed them, and **thirteen unit tests passed**,
 * because every one of those tests called `dispatch` directly and never asked whether the live
 * session is ever TOLD the tool exists.
 *
 * On the device the effect was total and silent: Kevin asked what his goals were, and the
 * assistant - holding no `list_goals` declaration in a normal session - answered "I do not seem to
 * have any recorded goals for you, sir" while the row sat in Room. Onboarding, meanwhile, was
 * advertising five tools it has no dispatch path for.
 *
 * A tool is only real when it is BOTH dispatchable and declared to the session that needs it, so
 * that is what these assert.
 */
class LiveToolboxDeclarationSetTest {

    private fun names(arr: JSONArray): Set<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.toSet()

    /** Every tool the driver can reach mid-drive must be in the MAIN set. */
    @Test
    fun `goal and advisor tools are declared to the live session`() {
        val live = names(LiveToolbox.declarations())
        for (tool in listOf("set_goal", "list_goals", "close_goal", "ask_advisor", "accept_proposal")) {
            assertTrue(
                "$tool is dispatchable but NOT declared in declarations() - the live model will " +
                    "never know it exists, which is exactly how 'I do not seem to have any " +
                    "recorded goals' happened with the goal sitting in the database",
                tool in live,
            )
        }
    }

    /**
     * `clear_codes` (`.scratch/hands-and-senses/issues/01-clear-dtc.md`, D10) is the exact shape
     * this file exists to catch: a destructive, confirm-gated tool that would be silently
     * dispatchable-but-invisible if it ever landed in the wrong array.
     */
    @Test
    fun `clear_codes is declared to the live session, not to onboarding`() {
        assertTrue("clear_codes must be declared() so the live model can call it", "clear_codes" in names(LiveToolbox.declarations()))
        assertTrue(
            "clear_codes must NOT be in onboardingDeclarations() - onboarding has no OBD write path",
            "clear_codes" !in names(LiveToolbox.onboardingDeclarations()),
        )
    }

    /**
     * Onboarding replaces the normal toolset rather than extending it, and its dispatch lives in
     * the onboarding screen, not [LiveToolbox.dispatch]. A tool that leaks in here is advertised
     * to a model that has no way to run it.
     */
    @Test
    fun `onboarding declares only its own five capture tools`() {
        val expected = setOf(
            "set_companion_name", "set_personality", "set_driver", "register_car", "finish_intro",
        )
        assertEquals(
            "onboardingDeclarations() must hold only the first-run capture tools - anything else " +
                "is either unreachable there or missing from the main set",
            expected,
            names(LiveToolbox.onboardingDeclarations()),
        )
    }

    /** No tool should be advertised twice, in both sets, under two different dispatch regimes. */
    @Test
    fun `the two declaration sets do not overlap`() {
        val overlap = names(LiveToolbox.declarations()) intersect names(LiveToolbox.onboardingDeclarations())
        assertTrue("a tool declared in both sets has two dispatch regimes: $overlap", overlap.isEmpty())
    }
}
