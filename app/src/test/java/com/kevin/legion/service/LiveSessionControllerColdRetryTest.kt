package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-02, "it says no connection, tap to retry, then it says listening": covers
 * [LiveSessionController.shouldRetryColdConnect], the pure decision behind the one silent cold
 * retry a tap's failed connect gets before any notice is shown.
 */
class LiveSessionControllerColdRetryTest {

    private fun decide(
        userInitiated: Boolean = true,
        everConnected: Boolean = false,
        reason: String = "connection failed",
        alreadyRetried: Boolean = false,
    ) = LiveSessionController.shouldRetryColdConnect(userInitiated, everConnected, reason, alreadyRetried)

    @Test
    fun `a tapped connect that never connected retries once`() {
        assertTrue(decide())
    }

    @Test
    fun `the retry is spent after one - no loop`() {
        assertFalse(decide(alreadyRetried = true))
    }

    @Test
    fun `a background prewarm is never retried here`() {
        assertFalse(decide(userInitiated = false))
    }

    @Test
    fun `a connection that was up and dropped is not retried`() {
        assertFalse(decide(everConnected = true))
    }

    @Test
    fun `key quota and microphone failures are never retried`() {
        assertFalse(decide(reason = "key rejected"))
        assertFalse(decide(reason = "quota"))
        assertFalse(decide(reason = "microphone permission not granted"))
    }

    @Test
    fun `normal closes are never retried`() {
        for (r in listOf("stopped", "idle", "destroyed", "warm expired", "goAway")) {
            assertFalse(r, decide(reason = r))
        }
    }

    @Test
    fun `a stale-handle server close is retried`() {
        assertTrue(decide(reason = "BidiGenerateContent session not found"))
    }
}
