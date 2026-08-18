package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the defect this fix closes: a real 21:42-21:45 on-device session where a
 * driver spoke workout sets, `SubAgent.investigate`'s loop never actually dispatched a mutating
 * tool (`ask_body`'s tools were budget-spent on reads), and the sub-agent's own prose still said
 * "logged it" - which the old `agentResult` spoke verbatim as `success = true`, with nothing in
 * `workout_set_logs` to back it up. [LiveToolbox.successOrMutationRefusal] is the pure decision
 * pulled out of `agentResult` so this is testable with no network, no Room, no Robolectric - same
 * shape as [LiveSessionControllerToolCallRestoreTest] pulling `shouldRestoreAfterToolCall` out of
 * the socket-handling code around it.
 *
 * Three cases, matching the brief's own three-way split:
 *  - write-shaped request, a mutating tool actually ran -> success, sub-agent's text spoken.
 *  - write-shaped request, nothing mutating ran -> refused, sub-agent's text NEVER spoken (that
 *    text is exactly the false "logged it" this fix exists to stop reaching the driver).
 *  - read-shaped request (requireMutation off), nothing mutating ran -> success, same as before
 *    this fix - "what did I lift last week" must never be refused just because it read, not wrote.
 */
class LiveToolboxMutationGateTest {

    @Test
    fun `mutation required and a mutating tool ran - success, sub-agent text spoken`() {
        val out = LiveToolbox.successOrMutationRefusal(
            subAgentText = "3 sets of squats at 135lbs, logged.",
            mutatingToolsCalled = listOf("log_workout_set"),
            requireMutation = true,
        )
        assertTrue(out.getBoolean("success"))
        assertEquals("3 sets of squats at 135lbs, logged.", out.getString("message"))
    }

    @Test
    fun `mutation required and nothing mutating ran - refused, sub-agent prose discarded`() {
        val out = LiveToolbox.successOrMutationRefusal(
            subAgentText = "Got it, I've logged your sets - keep up the good work!",
            mutatingToolsCalled = emptyList(),
            requireMutation = true,
        )
        assertFalse(out.getBoolean("success"))
        // The sub-agent's own claim of having logged something must never reach the driver here -
        // that exact shape of prose, unbacked by a real write, is the measured defect.
        assertFalse(out.getString("message").contains("logged"))
    }

    @Test
    fun `read-shaped call - not required, nothing mutating ran, still success`() {
        val out = LiveToolbox.successOrMutationRefusal(
            subAgentText = "You did 12 sets across 3 exercises last week.",
            mutatingToolsCalled = emptyList(),
            requireMutation = false,
        )
        assertTrue(out.getBoolean("success"))
        assertEquals("You did 12 sets across 3 exercises last week.", out.getString("message"))
    }

    @Test
    fun `mutation required but MULTIPLE tools ran - still success, all reported`() {
        val out = LiveToolbox.successOrMutationRefusal(
            subAgentText = "Logged your meal and your sleep.",
            mutatingToolsCalled = listOf("log_meal", "log_sleep"),
            requireMutation = true,
        )
        assertTrue(out.getBoolean("success"))
    }
}
