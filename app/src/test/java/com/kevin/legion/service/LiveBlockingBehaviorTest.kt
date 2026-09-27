package com.kevin.legion.service

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
 * [GeminiLiveSession.withBlockingBehavior] is the choke point that prevents it; these pin it.
 * Robolectric only because `org.json` on the plain JVM is Android's stub.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBlockingBehaviorTest {

    private fun decl(name: String) = JSONObject().put("name", name).put("description", "d")

    @Test
    fun `every declaration is stamped BLOCKING`() {
        val out = GeminiLiveSession.withBlockingBehavior(JSONArray().put(decl("a")).put(decl("b")))
        assertEquals(2, out.length())
        for (i in 0 until out.length()) assertEquals("BLOCKING", out.getJSONObject(i).getString("behavior"))
    }

    @Test
    fun `an explicit behavior is left alone`() {
        val input = JSONArray().put(decl("a").put("behavior", "NON_BLOCKING"))
        assertEquals("NON_BLOCKING", GeminiLiveSession.withBlockingBehavior(input).getJSONObject(0).getString("behavior"))
    }

    @Test
    fun `the caller's array is never mutated`() {
        val input = JSONArray().put(decl("a"))
        GeminiLiveSession.withBlockingBehavior(input)
        assertFalse(input.getJSONObject(0).has("behavior"))
    }
}
