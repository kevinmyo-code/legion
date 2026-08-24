package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineToolbox.clerkResult] formatting - the same "pure decision pulled out of the network call"
 * shape [LiveToolbox.successOrMutationRefusal] uses, so `aspect_clerk`'s five outcome branches are
 * testable with no real Gemini call. Ticket 07's own finding #3 (a Flash run that hit the forced
 * tool-free round with an EMPTY final answer) is already covered upstream: [SubAgent.investigate]
 * only ever returns [AgentResult.Success] when `answerText` is non-null, so that exact case already
 * arrives here as [AgentResult.Failed] - the "failed" branch below is what that finding needs.
 */
class EngineToolboxClerkResultTest {

    @Test
    fun `success speaks the worker's own text`() = runBlocking {
        val out = EngineToolbox.clerkResult("fallback") { AgentResult.Success("Wrote 1 row, 0 failed.") }
        assertTrue(out.getBoolean("success"))
        assertEquals("Wrote 1 row, 0 failed.", out.getString("message"))
    }

    @Test
    fun `rate limited fails without claiming anything was written`() = runBlocking {
        val out = EngineToolbox.clerkResult("fallback") { AgentResult.RateLimited }
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("rate limit"))
    }

    @Test
    fun `key invalid fails and points at Setup`() = runBlocking {
        val out = EngineToolbox.clerkResult("fallback") { AgentResult.KeyInvalid }
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("Setup"))
    }

    @Test
    fun `offline fails without claiming a write happened`() = runBlocking {
        val out = EngineToolbox.clerkResult("fallback") { AgentResult.Offline }
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("signal"))
    }

    @Test
    fun `failed or overloaded falls back to the caller's own message`() = runBlocking {
        val failedOut = EngineToolbox.clerkResult("could not reach it") { AgentResult.Failed }
        assertFalse(failedOut.getBoolean("success"))
        assertEquals("could not reach it", failedOut.getString("message"))

        val overloadedOut = EngineToolbox.clerkResult("could not reach it") { AgentResult.Overloaded }
        assertFalse(overloadedOut.getBoolean("success"))
        assertEquals("could not reach it", overloadedOut.getString("message"))
    }
}
