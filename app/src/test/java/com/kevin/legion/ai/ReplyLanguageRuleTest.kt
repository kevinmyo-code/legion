package com.kevin.legion.ai

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reply-in-English rule (2026-11-05). On the A25 the input transcript showed the user's English
 * as another language, and the assistant answered in that language: once with no tool in the turn,
 * once over an English route result. Two levers, so both are pinned: the shared instruction (reaches
 * a turn with no tool) and the route-result frame (read beside the facts). Presence only; nothing
 * here can check that the model obeys.
 *
 * Robolectric for `org.json`, which throws in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class ReplyLanguageRuleTest {

    @Test
    fun `the shared instructions say to speak English unless asked in words`() {
        assertTrue(SHARED_INSTRUCTIONS.contains(ENGLISH_CLAUSE))
        val clause = ENGLISH_CLAUSE.lowercase()
        assertTrue(clause.contains("english"))
        assertTrue("the clause must name the transcript as the cause", clause.contains("transcript"))
        assertTrue("switching needs the user's words, not the transcript's language", clause.contains("in words"))
    }

    @Test
    fun `a route result tells the model to answer in English`() {
        val text = SubAgent.frameRouteResults(
            "when is the car due for service?",
            listOf("get_next_service" to JSONObject().put("result", "{\"success\":true}")),
        )
        val frame = text.substringBefore('\n').lowercase()
        assertTrue(frame, frame.contains("reply in english"))
        assertTrue(frame, frame.contains("in words"))
    }

    @Test
    fun `the rule names no assistant and no honorific`() {
        for (text in listOf(ENGLISH_CLAUSE, SubAgent.ROUTE_ENGLISH)) {
            val lower = text.lowercase()
            assertTrue(!Regex("\\bsir\\b").containsMatchIn(lower))
            assertTrue(!lower.contains("alfred"))
        }
    }
}
