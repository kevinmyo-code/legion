package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 24 (`.scratch/hands-and-senses/issues/24-the-socket-that-never-rests.md`, "the socket
 * restarts every 2.5 minutes, all day"): covers
 * [LiveSessionController.shouldAutoReconnectAfterClose], the pure decision behind the
 * [LiveEvent.Closed] branch's automatic re-prewarm. Same shape as
 * [LiveSessionControllerThreadLossTest]/[LiveSessionControllerToolCallRestoreTest] -
 * [LiveSessionController] needs a live Context/GeminiLiveSession/Room to construct at all, so
 * this is the seam a plain JVM test can exercise directly against the real production decision.
 *
 * The measured problem this protects against: the Live server closes an idle prewarmed socket
 * roughly every 153 seconds on its own, and every connect (cold or resumed) re-sends the whole
 * setup payload - ~16,189 estimated tokens. Reconnecting on every one of those closes forever is
 * what produced ~565 reconnects and ~9.1M estimated input tokens a day with nobody using the
 * app. This function is the fix's whole decision: inside the idle window of the last real
 * signal, keep reconnecting; past it, stop.
 */
class LiveSessionControllerIdleReconnectTest {

    private val window = 600_000L // 10 minutes, matches IDLE_RECONNECT_WINDOW_MS - passed
                                   // explicitly so this test pins its own behaviour rather than
                                   // silently tracking a production constant that could change.

    @Test
    fun `well inside the idle window - reconnect`() {
        assertTrue(
            LiveSessionController.shouldAutoReconnectAfterClose(
                lastInteractionMs = 0L, nowMs = 1_000L, idleWindowMs = window,
            )
        )
    }

    @Test
    fun `well past the idle window - do not reconnect`() {
        assertFalse(
            LiveSessionController.shouldAutoReconnectAfterClose(
                lastInteractionMs = 0L, nowMs = window * 3, idleWindowMs = window,
            )
        )
    }

    @Test
    fun `boundary - one millisecond inside the window still reconnects`() {
        assertTrue(
            LiveSessionController.shouldAutoReconnectAfterClose(
                lastInteractionMs = 0L, nowMs = window - 1, idleWindowMs = window,
            )
        )
    }

    @Test
    fun `boundary - exactly at the window edge does NOT reconnect`() {
        // Pinned deliberately: the comparison is a strict less-than, so the instant the window
        // elapses is already "past it", not the last moment still inside it. Elapsed time this
        // exact is not achievable from two System.currentTimeMillis() calls in practice, but the
        // pure function's boundary still has to be a specific, checkable answer.
        assertFalse(
            LiveSessionController.shouldAutoReconnectAfterClose(
                lastInteractionMs = 0L, nowMs = window, idleWindowMs = window,
            )
        )
    }

    @Test
    fun `boundary - one millisecond past the window does NOT reconnect`() {
        assertFalse(
            LiveSessionController.shouldAutoReconnectAfterClose(
                lastInteractionMs = 0L, nowMs = window + 1, idleWindowMs = window,
            )
        )
    }

    @Test
    fun `a signal that arrives after the close - now before last interaction - still reconnects`() {
        // Cannot happen with real wall-clock reads (nowMs is always >= lastInteractionMs in
        // production), but a negative elapsed time must not accidentally read as "expired" if the
        // clock ever moves backwards (e.g. NTP correction) - it should read as well within the
        // window, which the plain subtraction naturally gives.
        assertTrue(
            LiveSessionController.shouldAutoReconnectAfterClose(
                lastInteractionMs = 5_000L, nowMs = 4_000L, idleWindowMs = window,
            )
        )
    }

    @Test
    fun `the default idle window is the production constant, not a test-only value`() {
        // Exercises the default parameter directly (no idleWindowMs supplied) so a future edit to
        // IDLE_RECONNECT_WINDOW_MS is caught here rather than only in the field.
        assertTrue(
            LiveSessionController.shouldAutoReconnectAfterClose(lastInteractionMs = 0L, nowMs = window - 1)
        )
        assertFalse(
            LiveSessionController.shouldAutoReconnectAfterClose(lastInteractionMs = 0L, nowMs = window)
        )
    }

    // --- resumeHandleAfterClose (2026-11-05) --------------------------------------------------

    private fun handleAfter(
        reason: String = "Session closed due to client inactivity.",
        everConnected: Boolean = true,
        userInitiated: Boolean = false,
        autoReconnect: Boolean = true,
    ) = LiveSessionController.resumeHandleAfterClose("h1", reason, everConnected, userInitiated, autoReconnect)

    /**
     * The A25's idle chain (11-01..11-04): past the window nothing reconnects, and the next wake's
     * first connect sent the idle socket's handle and was refused at once, costing a second setup.
     */
    @Test
    fun `an idle socket closed past the window drops its handle`() {
        assertEquals(null, handleAfter(autoReconnect = false))
    }

    @Test
    fun `an idle socket closed inside the window keeps its handle for the reconnect`() {
        assertEquals("h1", handleAfter(autoReconnect = true))
    }

    @Test
    fun `a conversation's own drop keeps its handle even past the window`() {
        val dropped = handleAfter(
            reason = "Software caused connection abort", userInitiated = true, autoReconnect = false,
        )
        assertEquals("h1", dropped)
    }

    @Test
    fun `a deliberate stop and a resume that never connected still drop it`() {
        assertEquals(null, handleAfter(reason = "stopped", userInitiated = true))
        assertEquals(null, handleAfter(everConnected = false))
    }
}
