package com.kevin.legion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [SubAgent.buildAskBody]'s `generationConfig` plumbing (ticket 21) - no network, no
 * Android dependency (`org.json.JSONObject` parsing only, matching [SubAgentUsageMetadataTest]'s
 * reasoning for skipping Robolectric here; [imageBytes] is null in every case below, so
 * [SubAgent.userParts] never reaches the `android.util.Base64` call [SubAgentPartsTest] needs
 * Robolectric for).
 *
 * Two things this file is pinning down together:
 *  1. The config is genuinely OPTIONAL - absent when [SubAgent.buildAskBody] isn't handed a
 *     [StructuredOutputRequest], present with both `responseMimeType` and `responseSchema` when it
 *     is.
 *  2. [SubAgent.askTyped]'s three non-advisor production callers (`MemoryConsolidator`,
 *     `ReflectionEngine`, `AmbientListener` - grepped 2026-08-16, all three call
 *     `askTyped(context = ..., question = ...)` and nothing else) build a body with no
 *     `generationConfig` at all, i.e. byte-identical to the shape [SubAgent] sent before this
 *     ticket. Only [com.kevin.legion.advisor.AdvisorAgent] passes a [StructuredOutputRequest].
 */
class SubAgentStructuredOutputTest {

    private val agent = SubAgent(systemInstruction = "You are a test.", useSearch = false)

    // --- Absent by default -------------------------------------------------------------------

    @Test
    fun `no generationConfig key when structuredOutput is not supplied`() {
        val body = agent.buildAskBody(
            context = "some context",
            question = "some question",
            imageBytes = null,
            imageMimeType = "image/jpeg",
        )
        assertFalse(body.has("generationConfig"))
    }

    @Test
    fun `MemoryConsolidator-shaped call (context, question only) has no generationConfig`() {
        // Mirrors ai/MemoryConsolidator.kt:93's exact call shape.
        val body = agent.buildAskBody("transcript text", "distill this", null, "image/jpeg")
        assertFalse(body.has("generationConfig"))
    }

    @Test
    fun `ReflectionEngine-shaped call (context, question only) has no generationConfig`() {
        // Mirrors ai/ReflectionEngine.kt:84's exact call shape.
        val body = agent.buildAskBody("memory listing", "reflect on this", null, "image/jpeg")
        assertFalse(body.has("generationConfig"))
    }

    @Test
    fun `AmbientListener-shaped call (context, question only) has no generationConfig`() {
        // Mirrors service/AmbientListener.kt:233's exact call shape.
        val body = agent.buildAskBody("overheard conversation", "react?", null, "image/jpeg")
        assertFalse(body.has("generationConfig"))
    }

    // --- Present, with the exact wire shape Gemini expects ------------------------------------

    @Test
    fun `generationConfig carries responseMimeType and responseSchema when requested`() {
        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("spoken", JSONObject().put("type", "STRING"))
            })
            put("required", org.json.JSONArray().put("spoken"))
        }
        val body = agent.buildAskBody(
            context = "ctx",
            question = "q",
            imageBytes = null,
            imageMimeType = "image/jpeg",
            structuredOutput = StructuredOutputRequest(schema),
        )

        assertTrue(body.has("generationConfig"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertTrue(config.has("responseSchema"))
        // Round-trips the exact object handed in, not a re-derived copy.
        assertEquals(schema.toString(), config.getJSONObject("responseSchema").toString())
    }
}
