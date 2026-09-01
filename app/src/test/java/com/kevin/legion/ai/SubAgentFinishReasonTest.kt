package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Network-free coverage of [SubAgent.parseFinishReason] - the signal [SubAgent.askTyped] uses to
 * set [AgentResult.Success.truncated], which [VoiceNoteAgent.parseResponse]'s salvage path depends
 * on being a MEASURED fact from the real response rather than a guess.
 */
class SubAgentFinishReasonTest {

    private val agent = SubAgent()

    @Test
    fun `reads finishReason off the first candidate`() {
        val json = """{"candidates": [{"finishReason": "MAX_TOKENS", "content": {"parts": []}}]}"""
        assertEquals("MAX_TOKENS", agent.parseFinishReason(json))
    }

    @Test
    fun `an ordinary STOP finish is reported as-is, not swallowed`() {
        val json = """{"candidates": [{"finishReason": "STOP", "content": {"parts": []}}]}"""
        assertEquals("STOP", agent.parseFinishReason(json))
    }

    @Test
    fun `absent finishReason is null, not a false MAX_TOKENS`() {
        val json = """{"candidates": [{"content": {"parts": []}}]}"""
        assertNull(agent.parseFinishReason(json))
    }

    @Test
    fun `malformed json is null, not a throw`() {
        assertNull(agent.parseFinishReason("not json"))
    }
}
