package com.kevin.legion.service

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 11 (`.scratch/wake-word/issues/11-let-him-say-never-mind.md`).
 *
 * Pins the `end_conversation` declaration the way `AriaBrainHonestyClauseTest` pins the honesty
 * clause: **this guards the tool's PRESENCE and the shape of its description, never the model's
 * obedience.** Nothing here can prove the live model declines to hang up on an ordinary "no" - only
 * a driven conversation can, and that is ticket 11's on-device step. What a test can do is stop the
 * guardrail wording being quietly deleted later, which is the failure that would make this tool
 * infuriating rather than useful.
 */
class LiveToolboxEndConversationTest {

    private fun declaration(): org.json.JSONObject {
        val all: JSONArray = LiveToolbox.declarations()
        for (i in 0 until all.length()) {
            val d = all.getJSONObject(i)
            if (d.optString("name") == "end_conversation") return d
        }
        throw AssertionError("end_conversation is not declared - a voice-opened turn cannot be closed by voice")
    }

    @Test
    fun `end_conversation is declared exactly once`() {
        val all = LiveToolbox.declarations()
        var count = 0
        for (i in 0 until all.length()) {
            if (all.getJSONObject(i).optString("name") == "end_conversation") count++
        }
        assertEquals("end_conversation must be declared exactly once", 1, count)
    }

    @Test
    fun `it takes no arguments`() {
        val d = declaration()
        val required = d.optJSONObject("parameters")?.optJSONArray("required")
        assertTrue(
            "end_conversation must not require arguments - dismissal carries no payload",
            required == null || required.length() == 0,
        )
    }

    @Test
    fun `the description tells the model to say a sign-off first`() {
        val text = declaration().optString("description").lowercase()
        assertTrue(
            "the description must ask for a sign-off, or the conversation ends in silence",
            "sign-off" in text,
        )
    }

    @Test
    fun `the description forbids firing on an ordinary no`() {
        val text = declaration().optString("description").lowercase()
        // The whole guardrail. Without it the model ends the chat when the driver declines a
        // suggestion, which reads as the app hanging up on him.
        assertTrue("the description must carry the do-NOT-call case", "do not call this" in text)
        assertTrue("the description must name the answering-no case", "answering 'no'" in text)
    }
}
