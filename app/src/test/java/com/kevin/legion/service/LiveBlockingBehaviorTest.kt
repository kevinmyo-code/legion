package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `gemini-3.8-live` made NON_BLOCKING function calls the default, which lets the model speak an
 * outcome before the tool result exists - CLAUDE.md sec 7's outcome-verb rule made unobeyable.
 * [GeminiLiveSession.withToolBehavior] is the choke point: everything blocks unless it is a named
 * read-only lookup (ADR 0065), and that lookup's result goes back `WHEN_IDLE`
 * ([GeminiLiveSession.toolResponseMessage]). Robolectric only because `org.json` on the plain JVM is
 * Android's stub.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBlockingBehaviorTest {

    private fun decl(name: String) = JSONObject().put("name", name).put("description", "d")

    private fun behaviors(out: JSONArray): Map<String, String> =
        (0 until out.length()).associate { out.getJSONObject(it).getString("name") to out.getJSONObject(it).getString("behavior") }

    @Test
    fun `every declaration is stamped BLOCKING when nothing is named non-blocking`() {
        val out = GeminiLiveSession.withToolBehavior(JSONArray().put(decl("a")).put(decl("b")), emptySet())
        assertEquals(mapOf("a" to "BLOCKING", "b" to "BLOCKING"), behaviors(out))
    }

    @Test
    fun `a named lookup is NON_BLOCKING and everything else still blocks`() {
        val out = GeminiLiveSession.withToolBehavior(
            JSONArray().put(decl("ask_body")).put(decl("log_meal")).put(decl("ask_engine")),
            setOf("ask_body"),
        )
        assertEquals(
            mapOf("ask_body" to "NON_BLOCKING", "log_meal" to "BLOCKING", "ask_engine" to "BLOCKING"),
            behaviors(out),
        )
    }

    @Test
    fun `an explicit behavior is left alone`() {
        val input = JSONArray().put(decl("a").put("behavior", "NON_BLOCKING")).put(decl("b").put("behavior", "BLOCKING"))
        assertEquals(
            mapOf("a" to "NON_BLOCKING", "b" to "BLOCKING"),
            behaviors(GeminiLiveSession.withToolBehavior(input, setOf("b"))),
        )
    }

    @Test
    fun `the caller's array is never mutated`() {
        val input = JSONArray().put(decl("a"))
        GeminiLiveSession.withToolBehavior(input, setOf("a"))
        assertFalse(input.getJSONObject(0).has("behavior"))
    }

    @Test
    fun `the setup message carries the stamp, and the off switch means all blocking`() {
        val session = GeminiLiveSession(ApplicationProvider.getApplicationContext()) {}
        fun stamped(nonBlocking: Set<String>): Map<String, String> {
            val setup = session.buildSetupJson(
                systemInstruction = "x",
                functionDeclarations = JSONArray().put(decl("ask_body")).put(decl("log_meal")),
                voiceName = "",
                vadMode = true,
                subtitles = false,
                resumeHandle = null,
                nonBlockingTools = nonBlocking,
            ).getJSONObject("setup")
            val tools = setup.getJSONArray("tools")
            val fns = (0 until tools.length()).map { tools.getJSONObject(it) }
                .first { it.has("functionDeclarations") }.getJSONArray("functionDeclarations")
            return behaviors(fns)
        }
        assertEquals(mapOf("ask_body" to "NON_BLOCKING", "log_meal" to "BLOCKING"), stamped(setOf("ask_body")))
        assertEquals(mapOf("ask_body" to "BLOCKING", "log_meal" to "BLOCKING"), stamped(emptySet()))
    }

    @Test
    fun `a non-blocking result goes back WHEN_IDLE inside response, beside the tool's own fields`() {
        val response = JSONObject().put("success", true).put("message", "two meals")
        val msg = GeminiLiveSession.toolResponseMessage("id1", "ask_body", response, async = true)
        val fr = msg.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("id1", fr.getString("id"))
        assertEquals("ask_body", fr.getString("name"))
        assertFalse("scheduling never sits beside id/name", fr.has("scheduling"))
        val body = fr.getJSONObject("response")
        assertEquals("WHEN_IDLE", body.getString("scheduling"))
        assertEquals("two meals", body.getString("message"))
        assertFalse("the tool's own result object is never mutated", response.has("scheduling"))
    }

    @Test
    fun `a blocking result carries no scheduling`() {
        val msg = GeminiLiveSession.toolResponseMessage("id2", "log_meal", JSONObject().put("success", true), async = false)
        val body = msg.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
            .getJSONObject("response")
        assertFalse(body.has("scheduling"))
    }
}
