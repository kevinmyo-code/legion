@file:Suppress("MaxLineLength") // invented calls read best on one line

package com.kevin.legion.service

import com.kevin.legion.service.ReadThroughWriteGuard.ALLOWED_AFTER_READ
import com.kevin.legion.service.ReadThroughWriteGuard.ALLOWED_AFTER_READ_ACTIONS
import com.kevin.legion.service.ReadThroughWriteGuard.REFUSED_AFTER_READ
import com.kevin.legion.service.tools.ToolReads
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
 * ADR 0090 on the phone, as Kevin ruled it on 2026-11-17 ("Block lasting + outbound"): one guard
 * before every resolved tool call. In a turn that read outside text, a call that lasts or leaves
 * the phone is refused in words and nothing runs; reads, navigation, timers, music, volume, screens
 * and reading more of a web page still run; a clean turn is untouched. Calls are written as the
 * model sends them (folded action tools), since the guard resolves them itself. Invented content only.
 */
@RunWith(RobolectricTestRunner::class) // org.json
class ReadThroughWriteGuardTest {

    private fun call(name: String, vararg args: Pair<String, Any>): Pair<String, JSONObject> {
        val o = JSONObject()
        for ((k, v) in args) o.put(k, v)
        return name to o
    }

    /** Lasting or outbound: refused after a read (gaps 2, 4 and 6). */
    private val lastingOrOutbound = listOf(
        call("records", "action" to "delete_record", "recordId" to "r-invented"),
        call("records", "action" to "create_record", "aspect" to "invented", "fields" to JSONObject()),
        call("records", "action" to "aspect_clerk", "instruction" to "delete every invented row"),
        call("goal", "action" to "close_goal", "aspect" to "invented", "statement" to "invented"),
        call("manage_item", "action" to "add", "item" to "invented errand"),
        call("manage_checklist", "action" to "add", "list" to "Todo", "text" to "invented"),
        call("day", "action" to "complete_task", "title" to "invented"),
        call("watch", "action" to "set", "kind" to "charge_over", "dollars" to 50),
        call("watch", "action" to "remove", "text" to "invented"),
        call("claude_session", "action" to "send", "request" to "invented"),
        call("email", "action" to "draft", "to" to "someone@example.invalid", "subject" to "x", "body" to "y"),
        call("activate_garage", "confirmed" to true),
        call("phone", "action" to "place_call", "target" to "invented", "confirmed" to true),
        call("place", "action" to "forget_place", "label" to "invented", "confirmed" to true),
        call("place", "action" to "tag_place", "label" to "invented"),
        call("app_update", "action" to "install"),
        call("ask_engine", "question" to "invented"),
        call("browser", "action" to "open", "text" to "https://example.invalid/?q=invented"),
        call("browser", "action" to "click", "number" to 3),
        call("browser", "action" to "type", "number" to 2, "text" to "invented", "submit" to true),
        call("memory", "action" to "remember", "text" to "an invented fact from a mail"),
        call("some_tool_added_tomorrow"),
    )

    /** Kevin's allowed list: navigation, timers, music, volume, screens, reading more of a page. */
    private val allowedAfterRead = listOf(
        call("navigate", "destination" to "an invented address"),
        call("trip", "action" to "end_trip"),
        call("clock", "action" to "timer_start", "seconds" to 600, "label" to "invented"),
        call("clock", "action" to "alarm_set", "time" to "07:00"),
        call("control_music", "action" to "pause"),
        call("play_music", "query" to "invented"),
        call("control_volume", "level" to 3),
        call("show_agenda_modal"),
        call("browser", "action" to "read"),
        call("browser", "action" to "scroll", "direction" to "down"),
        call("browser", "action" to "back"),
        call("browser", "action" to "close"),
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
    fun `in a marked turn every lasting or outbound call is refused in words`() {
        for ((name, args) in lastingOrOutbound) {
            val out = ReadThroughWriteGuard.refusal(name, args, readThrough = true, crisis = false)
            assertNotNull("$name $args ran in a marked turn", out)
            assertFalse(out!!.getBoolean("success"))
            assertTrue(out.getBoolean("read_through"))
            assertTrue("$name: words given", out.getString("message").isNotBlank())
        }
    }

    @Test
    fun `navigation, timers, music, volume, screens and reading more of a page still run`() {
        for ((name, args) in allowedAfterRead) {
            assertNull("$name $args was refused", ReadThroughWriteGuard.refusal(name, args, readThrough = true, crisis = false))
        }
    }

    @Test
    fun `every tool classified as refused after a read is refused`() {
        for (name in REFUSED_AFTER_READ - ToolReads.WHY) {
            assertNotNull("$name ran in a marked turn", ReadThroughWriteGuard.refusal(name, JSONObject(), readThrough = true, crisis = false))
        }
        val stop = JSONObject().put("stop", true)
        assertNotNull(ReadThroughWriteGuard.refusal(ToolReads.WHY, stop, readThrough = true, crisis = false))
    }

    /**
     * Deny by default, checked: every tool the model can reach is a read, allowed after a read, or
     * refused after a read. A new tool fails here until someone decides which.
     */
    @Test
    fun `every reachable tool is classified as read, allowed after a read, or refused after a read`() {
        val classified = ToolReads.READS + ToolReads.READ_ACTIONS.keys + ALLOWED_AFTER_READ +
            ALLOWED_AFTER_READ_ACTIONS.keys + REFUSED_AFTER_READ
        val unclassified = LiveToolbox.reachableNamesForTest() - classified
        assertTrue("classify in ReadThroughWriteGuard: ${unclassified.sorted()}", unclassified.isEmpty())
        assertTrue((ALLOWED_AFTER_READ intersect REFUSED_AFTER_READ).isEmpty())
        assertTrue((ALLOWED_AFTER_READ intersect ToolReads.READS).isEmpty())
        // A tool that only reads for some actions: its other actions need an explicit decision.
        val mixed = ToolReads.READ_ACTIONS.keys + ALLOWED_AFTER_READ_ACTIONS.keys
        val undecided = mixed - ALLOWED_AFTER_READ - REFUSED_AFTER_READ
        assertTrue("decide the other actions of: ${undecided.sorted()}", undecided.isEmpty())
    }

    @Test
    fun `the words name the resolved tool and say it waits for the next message, as the engine's do`() {
        val out = ReadThroughWriteGuard.refusal("records", JSONObject().put("action", "delete_record"), readThrough = true, crisis = false)!!
        val words = out.getString("message")
        assertEquals("delete_record", out.getString("tool"))
        assertTrue(words, words.startsWith("Nothing was done: delete_record did not run."))
        assertTrue(words, words.contains("next message"))
        assertTrue(words, words.contains("ask again"))
    }

    @Test
    fun `a remember keeps the memory refusal it always had`() {
        val out = ReadThroughWriteGuard.refusal("memory", JSONObject().put("action", "remember").put("text", "x"), readThrough = true, crisis = false)!!
        assertEquals(LiveToolbox.REMEMBER_MAIL_REFUSAL, out.getString("message"))
    }

    @Test
    fun `reads still run in a marked turn`() {
        for ((name, args) in reads) {
            assertNull("$name $args was refused", ReadThroughWriteGuard.refusal(name, args, readThrough = true, crisis = false))
        }
    }

    @Test
    fun `a clean turn is never refused`() {
        for ((name, args) in lastingOrOutbound + allowedAfterRead + reads) {
            assertNull("$name $args refused in a clean turn", ReadThroughWriteGuard.refusal(name, args, readThrough = false, crisis = false))
        }
    }
}
