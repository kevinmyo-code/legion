package com.kevin.legion.service

import com.kevin.legion.data.local.UNTRANSCRIBED_USER_TURN
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the 2026-09-07 "a blank transcription loses the turn" defect.
 *
 * **What went wrong.** `auditConversationTurn` wrote a USER row only when `inputAudioTranscription`
 * came back non-blank, and it sometimes comes back blank on a turn the model plainly heard and
 * acted on - the motivating evidence being a 09-05 turn where the assistant answered "I've created
 * the grocery list and added milk for you" with no USER row anywhere beside it, against a
 * `conversation_audit` holding 31 user rows and an `episodic_turns` holding none.
 *
 * Two consequences, both measured: the audit trail was quietly missing turns, and the connect meter
 * read the same transcript and so reported 24 connects in a day with ZERO carrying a turn on a
 * phone that had been used. The second one is the worse of the pair, because it looks like
 * evidence - it was the number that made "I must be out of credits" the obvious reading.
 *
 * **Neither fix invents anything.** The row records the ABSENCE of a transcript, which is a true
 * statement; no row at all was a false one. The counter reads three signals the app actually
 * observed rather than the one it happened to be looking at.
 */
class GeminiLiveSessionTurnAccountingTest {

    // --- which sockets count as used -------------------------------------

    @Test
    fun `a transcript counts, as it always did`() {
        assertTrue(GeminiLiveSession.turnCarriedWork("what's the oil like", "", toolCalled = false))
    }

    @Test
    fun `a tool call with no transcript counts`() {
        // The exact 09-05 shape: heard nothing back from transcription, ran create_list anyway.
        assertTrue(GeminiLiveSession.turnCarriedWork("", "", toolCalled = true))
    }

    @Test
    fun `a spoken reply with no transcript counts`() {
        assertTrue(
            GeminiLiveSession.turnCarriedWork(
                "", "I've created the grocery list and added milk for you", toolCalled = false,
            ),
        )
    }

    @Test
    fun `a socket that did nothing at all still counts as unused`() {
        // The number the meter exists to report. Widening the definition must not widen it to
        // everything, or "how many connects were wasted" stops answering anything.
        assertFalse(GeminiLiveSession.turnCarriedWork("", "", toolCalled = false))
        assertFalse(GeminiLiveSession.turnCarriedWork("   ", "  ", toolCalled = false))
    }

    // --- when a USER row is written with no transcript behind it ---------

    @Test
    fun `mic audio plus a tool call with no transcript is a recordable turn`() {
        assertTrue(
            GeminiLiveSession.userSpokeButWasNotTranscribed(
                transcript = "",
                micWasOpen = true,
                micBytesForwarded = 48_000L,
                companionText = "",
                toolCalled = true,
            ),
        )
    }

    @Test
    fun `mic audio plus a spoken answer with no transcript is a recordable turn`() {
        assertTrue(
            GeminiLiveSession.userSpokeButWasNotTranscribed(
                transcript = "",
                micWasOpen = true,
                micBytesForwarded = 48_000L,
                companionText = "I've created the grocery list and added milk for you",
                toolCalled = false,
            ),
        )
    }

    @Test
    fun `a greeting is never recorded as something the user said`() {
        // The failure mode that matters more than the missing row. A greeting, a proactive line and
        // an onboarding prompt all complete a turn with no transcript and no person in the room;
        // writing "the user said something" for those would be inventing a person, which is worse
        // than the gap being fixed. No mic bytes went out, so no user row.
        assertFalse(
            GeminiLiveSession.userSpokeButWasNotTranscribed(
                transcript = "",
                micWasOpen = false,
                micBytesForwarded = 0L,
                companionText = "Evening. What can I do for you?",
                toolCalled = false,
            ),
        )
    }

    @Test
    fun `an open mic that forwarded nothing is not a turn`() {
        // The mic was open and silence went out - a barge-in, a mis-fired wake word, a turn that
        // ended before anything was said. Nothing was observed, so nothing is asserted.
        assertFalse(
            GeminiLiveSession.userSpokeButWasNotTranscribed(
                transcript = "",
                micWasOpen = true,
                micBytesForwarded = 0L,
                companionText = "Sorry, I didn't catch that.",
                toolCalled = false,
            ),
        )
    }

    @Test
    fun `audio that produced no reply and no tool is not claimed as a turn`() {
        // Bytes alone are not evidence the model received anything usable. The trailing clause is
        // what says it acted, which is the shape of the evidence that started this.
        assertFalse(
            GeminiLiveSession.userSpokeButWasNotTranscribed(
                transcript = "",
                micWasOpen = true,
                micBytesForwarded = 48_000L,
                companionText = "",
                toolCalled = false,
            ),
        )
    }

    @Test
    fun `a turn that WAS transcribed never takes the marker path`() {
        // Belt and braces: the marker must never displace real words.
        assertFalse(
            GeminiLiveSession.userSpokeButWasNotTranscribed(
                transcript = "add milk to the grocery list",
                micWasOpen = true,
                micBytesForwarded = 48_000L,
                companionText = "Done.",
                toolCalled = true,
            ),
        )
    }

    // --- what the marker itself says --------------------------------------

    @Test
    fun `the marker states the absence and does not read as speech`() {
        // Bracketed and explicit, the same shape READ_THROUGH_REDACTED already established. A
        // reader six weeks later must not mistake this row's content for what was actually said.
        assertTrue(UNTRANSCRIBED_USER_TURN.startsWith("["))
        assertTrue(UNTRANSCRIBED_USER_TURN.endsWith("]"))
        assertTrue(UNTRANSCRIBED_USER_TURN.contains("no transcription"))
    }
}
