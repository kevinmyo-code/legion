package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resumption-handle rule behind the [LiveEvent.Closed] branch,
 * [LiveSessionController.resumeHandleAfterClose].
 *
 * History: ticket 24 (`.scratch/hands-and-senses/issues/24-the-socket-that-never-rests.md`) built a
 * 10 minute idle window here, inside which every unattended close re-prewarmed a socket. Kevin
 * retired the idle socket outright on 2026-11-05 (*"stop keeping a voice session open while idle;
 * only open one when you tap or say the wake word"*), so the window and its tests are gone; what
 * reopens after a close is now [LiveSessionLifecycle.afterClose] ([LiveSessionLifecycleTest]). The
 * handle rule stays, and since nothing reopens on an unattended close, such a close always drops
 * the handle rather than only past the window.
 */
class LiveSessionControllerIdleReconnectTest {

    private fun handleAfter(
        reason: String = "Session closed due to client inactivity.",
        everConnected: Boolean = true,
        userInitiated: Boolean = false,
    ) = LiveSessionController.resumeHandleAfterClose("h1", reason, everConnected, userInitiated)

    /**
     * The A25's idle chain (11-01..11-04): the next tap's first connect sent the idle socket's
     * handle and was refused at once, costing a second setup. Nothing reopens on this close now, so
     * the handle would only ever be sent by that tap.
     */
    @Test
    fun `a socket no conversation was using drops its handle when it closes`() {
        assertEquals(null, handleAfter())
        assertEquals(null, handleAfter(reason = "idle"))
        assertEquals(null, handleAfter(reason = "warm expired"))
        assertEquals(null, handleAfter(reason = "Software caused connection abort"))
    }

    /** The mid-conversation recovery: the next tap resumes the dropped conversation with this. */
    @Test
    fun `a conversation's own drop keeps its handle`() {
        assertEquals("h1", handleAfter(reason = "Software caused connection abort", userInitiated = true))
        assertEquals("h1", handleAfter(reason = "goAway", userInitiated = true))
        assertEquals("h1", handleAfter(reason = "Unable to resolve host \"generativelanguage.googleapis.com\"", userInitiated = true))
    }

    @Test
    fun `a deliberate stop and a resume that never connected still drop it`() {
        assertEquals(null, handleAfter(reason = "stopped", userInitiated = true))
        assertEquals(null, handleAfter(everConnected = false, userInitiated = true))
    }

    @Test
    fun `no handle stays no handle`() {
        assertEquals(
            null,
            LiveSessionController.resumeHandleAfterClose(null, "goAway", everConnected = true, userInitiated = true),
        )
    }
}
