@file:Suppress("MaxLineLength") // invented calls read best on one line

package com.kevin.legion.service

import com.kevin.legion.service.testturn.TestTurnPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR 0090 on the phone (2026-11-17): one guard before every resolved tool call, the twin of the
 * engine's `guards.py` write guard. In a turn that read outside text, every call that is not a
 * read by [com.kevin.legion.service.tools.ToolReads] (deny by default) is refused in words and
 * nothing runs; reads still run; a clean turn is untouched. Calls are written as the model sends
 * them (folded action tools), since the guard resolves them itself. Invented content only.
 */
@RunWith(RobolectricTestRunner::class) // org.json
class ReadThroughWriteGuardTest {

    private fun call(name: String, vararg args: Pair<String, Any>): Pair<String, JSONObject> {
        val o = JSONObject()
        for ((k, v) in args) o.put(k, v)
        return name to o
    }

    /** Writes the trace found unguarded on every path (gap 2), the model-confirmed ones (gap 4), and aspect_clerk (gap 6). */
    private val writes = listOf(
        call("records", "action" to "delete_record", "recordId" to "r-invented"),
        call("records", "action" to "create_record", "aspect" to "invented", "fields" to JSONObject()),
        call("records", "action" to "aspect_clerk", "instruction" to "delete every invented row"),
        call("goal", "action" to "close_goal", "aspect" to "invented", "statement" to "invented"),
        call("manage_item", "action" to "add", "item" to "invented errand"),
        call("day", "action" to "complete_task", "title" to "invented"),
        call("watch", "action" to "remove", "text" to "invented"),
        call("watch", "action" to "pause", "text" to "invented"),
        call("claude_session", "action" to "send", "request" to "invented"),
        call("email", "action" to "draft", "to" to "someone@example.invalid", "subject" to "x", "body" to "y"),
        call("activate_garage", "confirmed" to true),
        call("phone", "action" to "place_call", "target" to "invented", "confirmed" to true),
        call("place", "action" to "forget_place", "label" to "invented", "confirmed" to true),
        call("app_update", "action" to "install"),
        call("navigate", "destination" to "an invented address"),
        call("browser", "url" to "https://example.invalid"),
        call("show_agenda_modal"),
        call("memory", "action" to "remember", "text" to "an invented fact from a mail"),
        call("some_tool_added_tomorrow"),
    )

    private val reads = listOf(
        call("look_up", "domain" to "ask_mail", "question" to "newest mail"),
        call("day", "action" to "get_sitrep"),
        call("day", "action" to "track_package"),
        call("day", "action" to "read_calendar", "from" to "today", "to" to "today"),
        call("memory", "action" to "recall", "query" to "invented"),
        call("watch", "action" to "list"),
        call("records", "action" to "query_records", "aspect" to "invented"),
        call("end_conversation"),
    )

    @Test
    fun `in a marked turn every write, call, control and screen is refused in words`() {
        for ((name, args) in writes) {
            val out = ReadThroughWriteGuard.refusal(name, args, readThrough = true)
            assertNotNull("$name $args ran in a marked turn", out)
            assertFalse(out!!.getBoolean("success"))
            assertTrue(out.getBoolean("read_through"))
            assertTrue("$name: words given", out.getString("message").isNotBlank())
        }
    }

    @Test
    fun `every tool the read list does not name is refused in a marked turn`() {
        for (name in TestTurnPolicy.REFUSED) {
            assertNotNull("$name ran in a marked turn", ReadThroughWriteGuard.refusal(name, JSONObject(), readThrough = true))
        }
    }

    @Test
    fun `the words name the resolved tool and say it waits for the next message, as the engine's do`() {
        val out = ReadThroughWriteGuard.refusal("records", JSONObject().put("action", "delete_record"), readThrough = true)!!
        val words = out.getString("message")
        assertEquals("delete_record", out.getString("tool"))
        assertTrue(words, words.startsWith("Nothing was changed: delete_record did not run."))
        assertTrue(words, words.contains("next message"))
        assertTrue(words, words.contains("ask again"))
    }

    @Test
    fun `a remember keeps the memory refusal it always had`() {
        val out = ReadThroughWriteGuard.refusal("memory", JSONObject().put("action", "remember").put("text", "x"), readThrough = true)!!
        assertEquals(LiveToolbox.REMEMBER_MAIL_REFUSAL, out.getString("message"))
    }

    @Test
    fun `reads still run in a marked turn`() {
        for ((name, args) in reads) {
            assertNull("$name $args was refused", ReadThroughWriteGuard.refusal(name, args, readThrough = true))
        }
    }

    @Test
    fun `a clean turn is never refused`() {
        for ((name, args) in writes + reads) {
            assertNull("$name $args refused in a clean turn", ReadThroughWriteGuard.refusal(name, args, readThrough = false))
        }
    }
}
