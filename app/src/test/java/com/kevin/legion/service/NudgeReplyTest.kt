package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brush-off detector behind "never nag twice" (ticket 05 call 5).
 *
 * **The asymmetry is the design, so it is what these tests pin.** A false decline silences a rule
 * for 24 hours that Kevin wanted, and he never learns why - the nudge just stops. A missed decline
 * means it returns on schedule and he waves it off again, which is mild and self-correcting. So
 * most of the cases below assert that something is NOT a decline.
 */
class NudgeReplyTest {

    @Test
    fun `plain refusals are declines`() {
        listOf("no", "nope", "nah", "not now", "later", "leave it", "no thanks", "forget it")
            .forEach { assertTrue("should be a decline: $it", NudgeReply.isDecline(it)) }
    }

    @Test
    fun `punctuation and case do not hide a refusal`() {
        assertTrue(NudgeReply.isDecline("No."))
        assertTrue(NudgeReply.isDecline("NOT NOW"))
        assertTrue(NudgeReply.isDecline("nope!"))
    }

    /** Silence is not a refusal. He may not have heard it, or may be mid-thought - suppressing a
     * rule for a day because nobody spoke quietly loses something he wanted. */
    @Test
    fun `silence is never a decline`() {
        assertFalse(NudgeReply.isDecline(""))
        assertFalse(NudgeReply.isDecline("   "))
    }

    /** The expensive mistake. A real sentence is a conversation, not a dismissal. */
    @Test
    fun `a real sentence is not a decline even when it contains a no`() {
        assertFalse(NudgeReply.isDecline("no I was actually going to ask you about that anyway"))
        assertFalse(NudgeReply.isDecline("there is no way I am doing that tonight, remind me tomorrow"))
    }

    @Test
    fun `a positive word anywhere cancels it`() {
        assertFalse(NudgeReply.isDecline("no actually yes"))
        assertFalse(NudgeReply.isDecline("no thanks do it"))
        assertTrue("sanity: the same shape without a positive IS a decline", NudgeReply.isDecline("no thanks"))
    }

    /**
     * Whole-word matching. A `contains` check would fire on every one of these, and each is a
     * perfectly normal thing to say to an assistant that keeps notes and a calendar.
     */
    @Test
    fun `words that merely contain a negative are not refusals`() {
        listOf("notes", "November", "nothing", "stopwatch", "canceled plans exist")
            .forEach { assertFalse("must not be a decline: $it", NudgeReply.isDecline(it)) }
    }

    @Test
    fun `an ordinary request is not a decline`() {
        listOf("what is on today", "play something", "call him back", "remind me at six")
            .forEach { assertFalse("must not be a decline: $it", NudgeReply.isDecline(it)) }
    }
}
