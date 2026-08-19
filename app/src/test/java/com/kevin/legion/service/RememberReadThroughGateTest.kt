package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 21 (google-account-integration), "close the remember leak": the mail read-through rule
 * (CLAUDE.md §7, ticket 07) says mail is read, used, dropped - nothing reaches
 * [com.kevin.legion.data.local.EpisodicTurn] or [com.kevin.legion.data.local.CompanionMemory]. The
 * episodic half was enforced; `remember` (a second, independent writer straight into
 * [com.kevin.legion.data.local.MemoryEntry]) was not gated on that rule at all. This asserts the
 * two pieces that close the hole:
 *
 * 1. [LiveToolbox.rememberBlockedByReadThroughTool] - the gate `remember`'s dispatch branch
 *    applies. Pure and Android-free the same way [GeminiLiveSession.isEpisodicExcludedTool] is,
 *    for the same reason: [LiveToolbox.dispatch] needs a live [android.content.Context] and this
 *    decision does not, so it is pulled out to where a fast JVM test can prove it directly.
 * 2. [GeminiLiveSession.isEpisodicExcludedTool]'s injectable-set overload - the actual membership
 *    test the gate is built on, proven here to key off SET MEMBERSHIP rather than the two literal
 *    tool names "search_mail"/"read_mail", exactly as ticket 07's map entry requires ("precedent
 *    for every future read-through sense").
 *
 * [GeminiLiveSessionEpisodicExclusionTest] is untouched by this ticket and still asserts the
 * original two-tool, single-argument behaviour on its own.
 */
class RememberReadThroughGateTest {

    // --- rememberBlockedByReadThroughTool ---------------------------------------------------

    @Test
    fun `refuses when the turn touched an excluded tool`() {
        assertTrue(LiveToolbox.rememberBlockedByReadThroughTool(touchedExcludedTool = true))
    }

    @Test
    fun `allows when the turn did not touch an excluded tool`() {
        assertFalse(LiveToolbox.rememberBlockedByReadThroughTool(touchedExcludedTool = false))
    }

    @Test
    fun `allows again on the next turn - the flag resets, the gate carries no state of its own`() {
        // GeminiLiveSession clears mailToolCalledThisTurn on every turnComplete (see its doc
        // comment at the field declaration); the gate itself is a pure function of whatever it's
        // handed, so two calls in a row with different inputs must never influence each other -
        // there is no static/companion state here for a stale "still refusing" bug to hide in.
        assertTrue(LiveToolbox.rememberBlockedByReadThroughTool(touchedExcludedTool = true))
        assertFalse(LiveToolbox.rememberBlockedByReadThroughTool(touchedExcludedTool = false))
        assertTrue(LiveToolbox.rememberBlockedByReadThroughTool(touchedExcludedTool = true))
    }

    // --- the membership test the gate is built on --------------------------------------------

    @Test
    fun `the gate's underlying membership test keys off the set, not the two literal tool names`() {
        // A name that is NOT one of the two real mail tools, and is not in the real
        // EPISODIC_EXCLUDED_TOOLS, still gets excluded once it's a member of an injected set -
        // proving the decision is "is this name in the set I was given", never a hardcoded
        // `name == "search_mail" || name == "read_mail"` check that a future read-through tool
        // (notifications, the document vault, anything else CLAUDE.md §7 anticipates) would need
        // a code change to inherit.
        val fakeExcludedTools = setOf("read_notifications")
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("read_notifications", fakeExcludedTools))
        // The same fake name against the REAL production set (the default parameter) is not
        // excluded - confirming the injected-set test above isn't accidentally passing because
        // the function ignores its argument.
        assertFalse(GeminiLiveSession.isEpisodicExcludedTool("read_notifications"))
    }

    @Test
    fun `an injected set can also legitimately exclude nothing`() {
        assertFalse(GeminiLiveSession.isEpisodicExcludedTool("search_mail", emptySet()))
    }
}
