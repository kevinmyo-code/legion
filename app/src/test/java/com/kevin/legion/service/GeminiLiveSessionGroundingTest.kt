package com.kevin.legion.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3 (2026-11-09): [GeminiLiveSession.carriesWebResults], the pure decision
 * `handleServerContent` uses to mark a turn read-through when the Live model's own Google Search
 * grounding brought web text in with no functionCall. A turn so marked refuses `remember` and the
 * goal-plan constraint save, as a mail or browser turn does.
 */
class GeminiLiveSessionGroundingTest {

    private fun parts(vararg p: JSONObject) =
        JSONObject().put("modelTurn", JSONObject().put("parts", JSONArray().apply { p.forEach { put(it) } }))

    @Test
    fun `grounding metadata marks the turn`() {
        val content = JSONObject().put(
            "groundingMetadata",
            JSONObject().put("webSearchQueries", JSONArray().put("tonight's score")),
        )
        assertTrue(GeminiLiveSession.carriesWebResults(content))
    }

    @Test
    fun `grounding metadata riding with audio marks the turn`() {
        val content = parts(JSONObject().put("inlineData", JSONObject().put("data", "AAAA")))
            .put("groundingMetadata", JSONObject())
        assertTrue(GeminiLiveSession.carriesWebResults(content))
    }

    @Test
    fun `url context metadata marks the turn`() {
        assertTrue(GeminiLiveSession.carriesWebResults(JSONObject().put("urlContextMetadata", JSONObject())))
    }

    @Test
    fun `a search the model ran as code marks the turn`() {
        val search = "print(google_search.search(queries=['x']))"
        val code = JSONObject().put("executableCode", JSONObject().put("code", search))
        val result = JSONObject().put("codeExecutionResult", JSONObject().put("output", "x"))
        assertTrue(GeminiLiveSession.carriesWebResults(parts(code)))
        assertTrue(GeminiLiveSession.carriesWebResults(parts(result)))
    }

    @Test
    fun `plain audio, transcripts and turnComplete do not`() {
        assertFalse(GeminiLiveSession.carriesWebResults(parts(JSONObject().put("inlineData", JSONObject()))))
        assertFalse(
            GeminiLiveSession.carriesWebResults(
                JSONObject().put("outputTranscription", JSONObject().put("text", "Hello.")).put("turnComplete", true),
            ),
        )
        assertFalse(GeminiLiveSession.carriesWebResults(JSONObject()))
    }
}
