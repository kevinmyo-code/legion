package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 02 (drive-test-2026-08-18, "the context dies with the socket, and nothing says
 * so"): covers [LiveSessionController.shouldNotifyThreadLoss], the pure decision behind
 * the [LiveEvent.Closed] branch's thread-loss flag. Same shape as
 * [LiveSessionControllerToolCallRestoreTest] - [LiveSessionController] needs a live
 * Context/GeminiLiveSession/Room to construct at all, so this is the seam a plain JVM
 * test can exercise directly against the real production decision, not a
 * re-implementation that could silently drift from it.
 *
 * The binding rule this protects (CLAUDE.md sec 7's honesty rule, applied to
 * conversation memory rather than actions): a driver must be TOLD when the thread was
 * lost rather than being answered cold. This function is the whole of "was it actually
 * lost" - true only when a real conversation was running, the driver didn't end it
 * themselves, and there is no resumption handle to carry it forward.
 */
class LiveSessionControllerThreadLossTest {

    @Test
    fun `a real conversation dropped with no resume handle and no driver stop IS a loss`() {
        assertTrue(
            LiveSessionController.shouldNotifyThreadLoss(
                wasConversationActive = true, closeReason = "connection failed", hasResumeHandle = false,
            )
        )
    }

    @Test
    fun `the same drop with a resume handle in hand is NOT a loss - resumption is expected to cover it`() {
        assertFalse(
            LiveSessionController.shouldNotifyThreadLoss(
                wasConversationActive = true, closeReason = "connection failed", hasResumeHandle = true,
            )
        )
    }

    @Test
    fun `a driver-initiated stop is never a loss, handle or not - they already know`() {
        assertFalse(
            LiveSessionController.shouldNotifyThreadLoss(
                wasConversationActive = true, closeReason = "stopped", hasResumeHandle = false,
            )
        )
        assertFalse(
            LiveSessionController.shouldNotifyThreadLoss(
                wasConversationActive = true, closeReason = "stopped", hasResumeHandle = true,
            )
        )
    }

    @Test
    fun `no conversation was ever active - nothing to lose, regardless of reason or handle`() {
        assertFalse(
            LiveSessionController.shouldNotifyThreadLoss(
                wasConversationActive = false, closeReason = "connection failed", hasResumeHandle = false,
            )
        )
        assertFalse(
            LiveSessionController.shouldNotifyThreadLoss(
                wasConversationActive = false, closeReason = "quota", hasResumeHandle = true,
            )
        )
    }

    @Test
    fun `a deliberate goAway close with no handle still counts as a loss`() {
        // The proactive goAway close (GeminiLiveSession.handleGoAway) is deliberate on OUR
        // side, but the server still ended the socket, and if it never sent a resumable
        // SessionResumptionUpdate first the conversation genuinely has nowhere to continue.
        assertTrue(
            LiveSessionController.shouldNotifyThreadLoss(
                wasConversationActive = true, closeReason = "goAway", hasResumeHandle = false,
            )
        )
    }
}
