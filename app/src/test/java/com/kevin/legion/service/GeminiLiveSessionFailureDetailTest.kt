package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the 2026-09-07 "the Live 429 discards the evidence" defect.
 *
 * **What went wrong.** The Live socket's HTTP-upgrade failure recorded
 * `KeyHealth.noteRateLimited("HTTP 429 opening the Gemini Live socket")` - the status, and nothing
 * else - while the REST path at [com.kevin.legion.ai.SubAgent] had always attached the server's own
 * body. Gemini returns 429 `RESOURCE_EXHAUSTED` for BOTH an exhausted key and a per-minute rate
 * limit, and the detail text is the only thing that tells them apart. That is the exact question
 * Kevin spent two days asking, and the app was throwing the answer away at the one moment it had
 * it.
 *
 * A verdict with no retrievable evidence behind it is CLAUDE.md sec 4 rule 8's failure landing in a
 * new place: the check ran, its input was discarded, and nobody can re-verify it afterwards.
 *
 * These are pure functions on [GeminiLiveSession]'s companion object for the usual reason - the
 * class needs a live Context, an OkHttp websocket, AudioTrack and Room to construct at all, so this
 * seam is the only one a fast JVM test can reach.
 */
class GeminiLiveSessionFailureDetailTest {

    private val quotaBody = """
        {"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.","status":"RESOURCE_EXHAUSTED"}}
    """.trimIndent()

    private val rateLimitBody =
        """{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}"""

    @Test
    fun `a 429 carries the server's own words`() {
        val detail = GeminiLiveSession.liveFailureDetail(429, quotaBody)
        assertTrue("the status must still be there", detail.contains("HTTP 429"))
        assertTrue("the socket it came from must still be named", detail.contains("Gemini Live socket"))
        assertTrue("the message is the whole point", detail.contains("exceeded your current quota"))
    }

    @Test
    fun `the two causes of a 429 are distinguishable from what is recorded`() {
        // The entire reason this change exists. Both are 429, both are RESOURCE_EXHAUSTED; only the
        // message differs, so only a recording that keeps the message can answer "is the key out of
        // credit, or did I just ask too fast?"
        val exhausted = GeminiLiveSession.liveFailureDetail(429, quotaBody)
        val throttled = GeminiLiveSession.liveFailureDetail(429, rateLimitBody)
        assertFalse("recording only the status makes these identical", exhausted == throttled)
        assertTrue(exhausted.contains("billing"))
        assertTrue(throttled.contains("check quota"))
    }

    @Test
    fun `a key rejection carries its body too`() {
        // 400/401/403 got the same bare treatment and the same fix - an API_KEY_INVALID and a
        // disabled project both come back 403 and read identically without the message.
        val body = "{\"error\":{\"code\":403,\"message\":\"Generative Language API " +
            "has not been used in project 42\",\"status\":\"PERMISSION_DENIED\"}}"
        val detail = GeminiLiveSession.liveFailureDetail(403, body)
        assertTrue(detail.contains("HTTP 403"))
        assertTrue(detail.contains("PERMISSION_DENIED"))
        assertTrue(detail.contains("has not been used in project 42"))
    }

    @Test
    fun `no body yields the bare status rather than an invented cause`() {
        // A transport-level failure has no upgrade response to read. Reporting the status alone is
        // honest; guessing at a cause is the thing CLAUDE.md sec 1 calls out - unreadable and
        // empty are different sentences.
        assertEquals(
            "HTTP 429 opening the Gemini Live socket",
            GeminiLiveSession.liveFailureDetail(429, null),
        )
        assertEquals(
            "HTTP 429 opening the Gemini Live socket",
            GeminiLiveSession.liveFailureDetail(429, "   "),
        )
    }

    @Test
    fun `a body that is not the expected JSON is kept verbatim rather than dropped`() {
        // An HTML error page from a proxy, or a shape Google changes next quarter. It is still
        // evidence, and discarding it would put this function straight back into the business of
        // throwing away the one thing that distinguishes two causes.
        val detail = GeminiLiveSession.liveFailureDetail(429, "<html><body>Too Many Requests</body></html>")
        assertTrue(detail.contains("Too Many Requests"))
    }

    @Test
    fun `a long body is truncated and says it was`() {
        // KeyHealth caps its detail at 160 characters and the Setup sentence renders this inline,
        // so an untrimmed wall of JSON would be chopped mid-word by the store and read as garbage.
        // Trimming here means the server's own words are what survive.
        val long = """{"error":{"message":"${"quota ".repeat(80)}","status":"RESOURCE_EXHAUSTED"}}"""
        val summary = checkNotNull(GeminiLiveSession.summariseGeminiError(long))
        assertTrue(summary.length <= GeminiLiveSession.FAILURE_DETAIL_MAX)
        assertTrue("a truncated message must read as truncated", summary.endsWith("…"))
    }

    @Test
    fun `newlines in a body are collapsed so the sentence stays one line`() {
        val summary = checkNotNull(
            GeminiLiveSession.summariseGeminiError("{\n  \"error\": {\n    \"message\": \"out\\nof\\nquota\"\n  }\n}"),
        )
        assertFalse(summary.contains("\n"))
    }

    @Test
    fun `an empty body summarises to nothing at all`() {
        assertNull(GeminiLiveSession.summariseGeminiError(null))
        assertNull(GeminiLiveSession.summariseGeminiError(""))
        assertNull(GeminiLiveSession.summariseGeminiError("\n \t "))
    }

    @Test
    fun `a body whose error object says nothing falls back to the raw text`() {
        // `{"error":{}}` parses but carries no message and no status. Reporting the raw body is
        // still better than reporting nothing - it at least shows what the server sent.
        val summary = checkNotNull(GeminiLiveSession.summariseGeminiError("""{"error":{}}"""))
        assertTrue(summary.contains("error"))
    }
}
