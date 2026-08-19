package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the bug this whole fix exists for: a tool call left the UI on
 * "Listening..." for the entire duration of an investigate()-backed sub-agent
 * (up to ~30-45s), because handleToolCall never moved the phase to THINKING and,
 * once it did, a naive restore risked either (a) dropping the UI out of THINKING
 * while a SIBLING concurrent tool call was still running, or (b) stomping a phase
 * a raced event (SpeakingStarted, Closed) had already moved on to.
 *
 * [LiveSessionController] itself needs a live Context/GeminiLiveSession/Room to
 * construct at all, so - same shape as [GeminiLiveSessionEpisodicExclusionTest] -
 * this asserts the real, pure decision function the production restore path calls
 * ([LiveSessionController.shouldRestoreAfterToolCall]), not a parallel
 * re-implementation that could drift from what handleToolCall actually does.
 */
class LiveSessionControllerToolCallRestoreTest {

    @Test
    fun `restores when the last concurrent tool call finishes and the phase is still THINKING`() {
        assertTrue(LiveSessionController.shouldRestoreAfterToolCall(0, Phase.THINKING))
    }

    @Test
    fun `does NOT restore while a sibling tool call is still in flight`() {
        // refcount hasn't reached zero yet - one or more concurrent calls remain.
        assertFalse(LiveSessionController.shouldRestoreAfterToolCall(1, Phase.THINKING))
        assertFalse(LiveSessionController.shouldRestoreAfterToolCall(2, Phase.THINKING))
    }

    @Test
    fun `does NOT restore when something else already moved the phase off THINKING`() {
        // The model started speaking, or the socket closed, while the tool was
        // still running - a late restore here would stomp that state.
        assertFalse(LiveSessionController.shouldRestoreAfterToolCall(0, Phase.SPEAKING))
        assertFalse(LiveSessionController.shouldRestoreAfterToolCall(0, Phase.IDLE))
        assertFalse(LiveSessionController.shouldRestoreAfterToolCall(0, Phase.LISTENING))
        assertFalse(LiveSessionController.shouldRestoreAfterToolCall(0, Phase.CONNECTING))
    }

    @Test
    fun `a negative refcount never restores - defensive against a double-decrement bug`() {
        assertFalse(LiveSessionController.shouldRestoreAfterToolCall(-1, Phase.THINKING))
    }
}
