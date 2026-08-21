package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two sentences that reach the user's ears on an incoming call - what the assistant is told
 * about WHO is calling ([CallerId.describe]) and what it is told about whether the call was
 * actually answered ([CallActions.describe]).
 *
 * Both are pure, so plain JUnit reaches them without Robolectric. Both are worth testing for the
 * same reason: **each has one branch where the honest answer and the convenient answer differ**,
 * and each is one careless edit away from asserting something untrue out loud.
 */
class CallerIdDescribeTest {

    // ------------------------------------------------------------------ who is calling

    @Test
    fun `a known contact is named`() {
        val text = CallerId.describe(CallerId.Caller.Known("Ray", "+15551234567"))
        assertTrue(text.contains("Ray"))
        assertTrue("must say the name came from contacts, not from nowhere", text.contains("contacts"))
    }

    @Test
    fun `a number with no contact is given as a number, and does not claim a name`() {
        val text = CallerId.describe(CallerId.Caller.NumberOnly("+15551234567")).lowercase()
        assertTrue(text.contains("+15551234567"))
        assertTrue(text.contains("matches nobody"))
    }

    /**
     * The case this file exists for.
     *
     * "I cannot see who is calling" and "the caller is unknown" are **different claims**. The first
     * is about the app's permission; the second is about the network. Collapsing them tells Kevin
     * his caller withheld their number when in fact the app was never allowed to look - the same
     * failure shape as rendering a refused calendar permission as an empty day.
     */
    @Test
    fun `no permission is never phrased as an unknown or withheld caller`() {
        val text = CallerId.describe(CallerId.Caller.CannotTell).lowercase()
        assertTrue("must say it cannot see", text.contains("cannot see"))
        assertTrue("must forbid the withheld phrasing explicitly", text.contains("withheld"))
        assertTrue("must forbid the unknown phrasing explicitly", text.contains("unknown"))
        // ...and it must forbid them rather than assert them.
        assertTrue(text.contains("do not say"))
    }

    @Test
    fun `a genuinely withheld number says the network withheld it`() {
        val text = CallerId.describe(CallerId.Caller.Withheld).lowercase()
        assertTrue(text.contains("network"))
        assertTrue(text.contains("withheld") || text.contains("blocked"))
        assertFalse(
            "a withheld number is a fact; it must not read as a permission problem",
            text.contains("permission"),
        )
    }

    @Test
    fun `the two not-known cases produce different sentences`() {
        assertFalse(
            CallerId.describe(CallerId.Caller.Withheld) ==
                CallerId.describe(CallerId.Caller.CannotTell)
        )
    }

    // ------------------------------------------------------------------ what actually happened

    @Test
    fun `only an observed answer is phrased as answered`() {
        assertEquals("The call is answered and connected.", CallActions.describe(CallActions.Outcome.Answered))
        assertEquals("The call was declined and has stopped ringing.", CallActions.describe(CallActions.Outcome.Rejected))
    }

    /**
     * CLAUDE.md §7: an outcome verb may follow only a tool result that came back successful.
     * `TelecomManager.acceptRingingCall()` returns `void`, so every non-observed path has to say
     * what did NOT happen - otherwise the lie moves one layer up into what is spoken.
     */
    @Test
    fun `every failure path says what did not happen, in words`() {
        val failures = listOf(
            CallActions.Outcome.NothingRinging,
            CallActions.Outcome.NoPermission,
            CallActions.Outcome.DidNotTake("the system refused the request"),
        )
        failures.forEach { outcome ->
            val text = CallActions.describe(outcome).lowercase()
            assertTrue(
                "a failure must state the negative outcome: $text",
                text.contains("nothing") || text.contains("did not") || text.contains("not changed"),
            )
            assertFalse(
                "a failure must never read as success: $text",
                text.contains("is answered") || text.contains("was declined"),
            )
        }
    }

    @Test
    fun `the VoIP limit is named rather than left as a mystery failure`() {
        // Telecom silently ignores self-managed calls, so the only way Kevin learns why "answer it"
        // did nothing is if the result says so.
        val text = CallActions.describe(
            CallActions.Outcome.DidNotTake(
                "the call did not connect - if it is a WhatsApp, Signal or Teams call, Android does " +
                    "not let this app answer it"
            )
        )
        assertTrue(text.contains("WhatsApp"))
    }

    @Test
    fun `a missing permission says nothing happened and where to fix it`() {
        val text = CallActions.describe(CallActions.Outcome.NoPermission)
        assertTrue(text.contains("Nothing happened"))
        assertTrue(text.contains("Setup"))
    }
}
