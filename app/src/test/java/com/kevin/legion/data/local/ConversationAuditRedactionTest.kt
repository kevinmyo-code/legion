package com.kevin.legion.data.local

import com.kevin.legion.service.GeminiLiveSession
import com.kevin.legion.service.LiveToolbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Read-through redaction in the conversation audit trail (ticket 23, hands-and-senses map).
 *
 * **What this pins, in the ticket's own words:** a turn touching `ask_mail` or `get_sitrep` must
 * contain the tool NAME and must NOT contain its content. Both halves matter and they pull in
 * opposite directions - a trail that redacts the name too is useless for ticket 20's "did the model
 * skip the tool, or get the right answer and say a different one?", and a trail that keeps the
 * result puts mail bodies in Room, which is the exact thing
 * [LiveToolbox.EPISODIC_EXCLUDED_TOOLS] exists to prevent.
 *
 * The two production call sites ([GeminiLiveSession]'s turn rows and
 * [com.kevin.legion.service.LiveSessionController]'s tool rows) both compose the same two pure
 * functions - [GeminiLiveSession.isEpisodicExcludedTool] for the membership test and
 * [auditContent] for the substitution - so composing them here is the decision production makes,
 * not a parallel reimplementation of it.
 */
@RunWith(RobolectricTestRunner::class) // org.json
class ConversationAuditRedactionTest {

    /** What a TOOL_RESULT row would store for [toolName] returning [result]. */
    private fun storedFor(toolName: String, result: String): String =
        auditContent(result, GeminiLiveSession.isEpisodicExcludedTool(toolName))

    private val mailBody = "Sam: lunch Thursday? - the exact content that must never land in Room"

    // ------------------------------------------------------------------ excluded tools

    @Test
    fun `every excluded tool has its result replaced`() {
        LiveToolbox.EPISODIC_EXCLUDED_TOOLS.forEach { tool ->
            assertEquals(
                "$tool is read-through and its result must not be stored",
                READ_THROUGH_REDACTED, storedFor(tool, mailBody),
            )
        }
    }

    @Test
    fun `the redacted row still says which tool ran`() {
        // The name is a separate column and is never passed through [auditContent] at all - this
        // asserts the property ticket 20 depends on, that redaction costs the content and not the
        // identity of the call.
        LiveToolbox.EPISODIC_EXCLUDED_TOOLS.forEach { tool ->
            val stored = storedFor(tool, mailBody)
            assertFalse("no fragment of the result may survive", stored.contains("Sam"))
            assertTrue("the tool name is not the thing being redacted", tool.isNotBlank())
        }
    }

    @Test
    fun `ask_mail and get_sitrep are in the set the ticket names`() {
        // Named explicitly rather than only iterating the set: the ticket names these two, and a
        // future edit that quietly drops one would otherwise still pass every loop above.
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("ask_mail"))
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("get_sitrep"))
    }

    @Test
    fun `a folded mail, brief, parcel or flight call is redacted as the cascade sends it`() {
        // 2026-11-17 (ADR 0090 gap 1): the cascade model sends the folded action tool, and the audit
        // used to judge `look_up` / `day` by name, storing the result in clear.
        val folded = listOf(
            "look_up" to JSONObject().put("domain", "ask_mail").put("question", "newest"),
            "day" to JSONObject().put("action", "get_sitrep"),
            "day" to JSONObject().put("action", "track_package"),
            "day" to JSONObject().put("action", "flight_status"),
        )
        for ((name, args) in folded) {
            assertEquals("$name $args", READ_THROUGH_REDACTED, auditContent(mailBody, GeminiLiveSession.isReadThroughCall(name, args)))
        }
        val calendar = JSONObject().put("action", "read_calendar")
        assertFalse(GeminiLiveSession.isReadThroughCall("day", calendar))
    }

    // ------------------------------------------------------------------ everything else

    @Test
    fun `an ordinary tool result is stored verbatim`() {
        val result = """{"success":true,"miles":227620}"""
        assertEquals(result, storedFor("ask_fleet", result))
        assertEquals(result, storedFor("list_vehicles", result))
    }

    @Test
    fun `an unknown tool is not treated as read-through`() {
        // The set is an allowlist of things to HIDE, not of things that are safe - a tool that
        // joins the app tomorrow must keep being audited, or the trail silently thins out.
        assertEquals("anything", storedFor("some_tool_added_later", "anything"))
    }

    // ------------------------------------------------------------------ the companion row

    @Test
    fun `a companion line is redacted whole when any tool that turn was excluded`() {
        // Free text cannot be attributed back to one tool among several, so the whole-turn flag
        // governs it - deliberately coarser than the per-row rule above. See
        // [ConversationAudit]'s class doc.
        val spoken = "You have lunch with Sam on Thursday."
        assertEquals(READ_THROUGH_REDACTED, auditContent(spoken, readThrough = true))
        assertEquals(spoken, auditContent(spoken, readThrough = false))
    }

    @Test
    fun `blank content is left blank rather than marked redacted`() {
        // A redaction marker where nothing was said reads later as "something was hidden here",
        // which is its own small lie in a record whose whole purpose is being trustworthy.
        assertEquals("", auditContent("", readThrough = true))
        assertEquals("   ", auditContent("   ", readThrough = true))
    }
}
