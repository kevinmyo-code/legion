package com.kevin.legion.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that the prompt's honesty rules are still IN the prompt.
 *
 * Ticket 04 of `.scratch/drive-test-2026-08-18/`. Nothing in this repo asserted anything about
 * `sharedInstructions` before this file: every rule in it - the tool-before-claiming rule, the
 * invented-appointment rule, the garage relay's forbidden verbs, the currency rule - was one
 * careless edit from vanishing with a green suite and no compile error, and each of them is there
 * because a real failure shipped once.
 *
 * **What this test does NOT do, deliberately.** It cannot tell you whether the model OBEYS the
 * clause. Nothing inspects the spoken audio, so obedience is unverifiable on this machine at any
 * price; Kevin was offered an always-on transcript detector and an offline eval and declined both
 * (2026-08-19). Presence is the whole of what is checked here, and a green run here is NOT evidence
 * that the assistant told the truth on a drive.
 */
class AriaBrainHonestyClauseTest {

    @Test
    fun `the cannot-clause is wired into the shared instructions`() {
        assertTrue(
            "CANNOT_CLAUSE must be part of the prompt the session actually uploads - a clause that " +
                "exists only as a constant is not a rule the model ever sees",
            SHARED_INSTRUCTIONS.contains(CANNOT_CLAUSE),
        )
    }

    /**
     * The clause's method is a forbidden-vocabulary list, copied from the garage relay. If these
     * verbs stop being named, the rule has quietly become "be careful", which is the shape of rule
     * that already failed once on a motorway.
     */
    @Test
    fun `the clause names the outcome-asserting verbs it forbids`() {
        for (verb in listOf("done", "started", "sent", "opened", "booked", "played", "set")) {
            assertTrue(
                "the clause must forbid the outcome verb '$verb' by name, not by appeal to care",
                CANNOT_CLAUSE.contains(verb),
            )
        }
    }

    /** The permission is conditioned on a successful tool result, which is what makes it scale. */
    @Test
    fun `the clause ties those verbs to a successful tool result, and enumerates no capabilities`() {
        assertTrue(CANNOT_CLAUSE.contains("came back successful"))
        assertTrue(
            "an unsuccessful tool result must be treated as no tool at all - ticket 03's " +
                "open_navigation can come back false and the driver has to hear that",
            CANNOT_CLAUSE.contains("unsuccessful is the same as no tool"),
        )
        for (capability in listOf("navigation", "Maps", "garage", "Spotify")) {
            assertTrue(
                "the clause must not enumerate what LEGION cannot do - a negative list is correct " +
                    "only until the next tool lands ($capability found)",
                !CANNOT_CLAUSE.contains(capability),
            )
        }
    }

    /** Kevin's register call (2026-08-19), plus the trap that call carries. */
    @Test
    fun `the clause offers the nearest real thing and forbids inventing one`() {
        assertTrue(CANNOT_CLAUSE.contains("nearest thing"))
        assertTrue(
            "offering an alternative is only safe if the alternative must itself be a real tool",
            CANNOT_CLAUSE.contains("only ever name a capability you genuinely have a tool for"),
        )
    }

    /**
     * The four rules that were already in the prompt, each one paid for by a shipped failure. Named
     * individually so a deletion says in the failure message what was lost and why it was there.
     */
    @Test
    fun `the older honesty rules are all still present`() {
        val rules = mapOf(
            "Always call the matching tool before claiming you've done something" to
                "the action rule - the one that could not bind when no tool existed (ticket 03)",
            "NEVER state a fact about the driver's own record unless a tool call in THIS conversation" to
                "the fact rule - LEGION invented a dentist appointment at 3 (2026-08-18, on-device)",
            "never say 'opening' or 'closing' - say 'triggering' or 'hitting'" to
                "the garage relay's forbidden verbs - the app cannot observe the door",
            "don't claim to remember something without checking first" to
                "the memory rule",
        )
        for ((rule, why) in rules) {
            assertTrue("missing from the prompt: $why", SHARED_INSTRUCTIONS.contains(rule))
        }
    }
}
