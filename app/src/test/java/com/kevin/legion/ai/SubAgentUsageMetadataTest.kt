package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies [SubAgent.parseUsageMetadata] against fabricated response bodies -
 * no network, no key, nothing that could touch a real Gemini endpoint. Plain
 * JUnit (no Robolectric needed): `org.json.JSONObject` parsing has no Android
 * dependency, unlike [SubAgentPartsTest]'s `android.util.Base64` use.
 * `.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md` §6.
 */
class SubAgentUsageMetadataTest {

    private val agent = SubAgent()

    @Test
    fun `both token counts parsed when present`() {
        val json = """
            {"candidates": [{"content": {"parts": [{"text": "hi"}]}}],
             "usageMetadata": {"promptTokenCount": 123, "candidatesTokenCount": 45}}
        """.trimIndent()
        val (prompt, candidates) = agent.parseUsageMetadata(json)
        assertEquals(123, prompt)
        assertEquals(45, candidates)
    }

    @Test
    fun `nulls, not zeros, when usageMetadata is absent entirely`() {
        val json = """{"candidates": [{"content": {"parts": [{"text": "hi"}]}}]}"""
        val (prompt, candidates) = agent.parseUsageMetadata(json)
        assertNull(prompt)
        assertNull(candidates)
    }

    @Test
    fun `null for a specific field missing from an otherwise-present usageMetadata`() {
        val json = """{"usageMetadata": {"promptTokenCount": 90}}"""
        val (prompt, candidates) = agent.parseUsageMetadata(json)
        assertEquals(90, prompt)
        assertNull(candidates)
    }

    @Test
    fun `malformed json degrades to nulls rather than throwing`() {
        val (prompt, candidates) = agent.parseUsageMetadata("not json at all")
        assertNull(prompt)
        assertNull(candidates)
    }
}
