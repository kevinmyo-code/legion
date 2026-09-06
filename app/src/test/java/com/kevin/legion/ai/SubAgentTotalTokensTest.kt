package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SubAgent.parseTotalTokens], the third figure the REST metering added on 2026-09-06 alongside
 * the prompt/candidates pair [SubAgentUsageMetadataTest] already covers.
 *
 * The property that matters is the one the fourth test states: the total is READ, never derived
 * from the other two. A derived total would always look self-consistent and would silently omit
 * thinking tokens and cached content, which some models count in the total and report in their own
 * fields - CLAUDE.md section 4 rule 6's shape, arriving in a token count.
 */
class SubAgentTotalTokensTest {
    private val agent = SubAgent()

    @Test
    fun `reads totalTokenCount when the response reports it`() {
        val json = """{"usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":34,"totalTokenCount":154}}"""
        assertEquals(154, agent.parseTotalTokens(json))
    }

    @Test
    fun `returns null when usageMetadata is absent entirely`() {
        // Gemini omits the block on some response shapes even inside an otherwise-200 body, which
        // is why the column is nullable and why this must not come back 0.
        val json = """{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}"""
        assertNull(agent.parseTotalTokens(json))
    }

    @Test
    fun `returns null when the block is present but the field is not`() {
        val json = """{"usageMetadata":{"promptTokenCount":120}}"""
        assertNull(agent.parseTotalTokens(json))
    }

    @Test
    fun `never derives the total from prompt plus candidates`() {
        // A real Gemini response where the reported total EXCEEDS the two visible components,
        // because thinking tokens are counted in it. Deriving would have produced 154 and quietly
        // under-reported by 500.
        val json = """{"usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":34,""" +
            """"thoughtsTokenCount":500,"totalTokenCount":654}}"""
        assertEquals(654, agent.parseTotalTokens(json))
    }

    @Test
    fun `unparseable input returns null rather than throwing into a live http handler`() {
        assertNull(agent.parseTotalTokens("not json at all"))
        assertNull(agent.parseTotalTokens(""))
    }

    @Test
    fun `a genuinely measured zero is preserved as zero`() {
        // The mirror image of the null cases: 0 reported IS information, and must not be collapsed
        // into "not reported" any more than the reverse.
        val json = """{"usageMetadata":{"totalTokenCount":0}}"""
        assertEquals(0, agent.parseTotalTokens(json))
    }
}
