package com.kevin.legion.service

import com.kevin.legion.service.turn.VoiceTurnSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LIVE_AUDIT lines (async-lookups ticket 05), pinned verbatim: `tools/voice_audit.py` parses
 * them, so a change here is a change there. Also pins CLAUDE.md sec 7 on this new sink: a mail turn's
 * assistant speech and the mail tool's result never reach logcat; the user's own words do.
 */
class LiveAuditTest {

    private val lines = mutableListOf<String>()
    private val audit = LiveAudit(sink = { lines += it }).apply { enabled = true }

    private fun result(id: String, name: String, response: String, async: Boolean = false) =
        LiveAudit.ToolResult(id, name, success = true, ms = 1042, async = async, sent = true, responseJson = response)

    @Test
    fun `every line format`() {
        audit.sessionStart("gemini-3.8-live", setOf("ask_goals", "ask_body"))
        audit.micOpen("first_open")
        audit.activityStart()
        audit.user("any trouble", final = false)
        audit.activityEnd()
        audit.user("any trouble codes?", final = true)
        audit.user("typed hi", final = true, typed = true)
        audit.toolCall("c1", "ask_fleet", async = true, argsJson = """{"question":"codes"}""")
        audit.assistant("One moment.", readThrough = false)
        audit.micClosed("half_duplex")
        audit.turnComplete()
        audit.toolResult(result("c1", "ask_fleet", """{"success":true,"answer":"none"}""", async = true))
        audit.toolCall("c2", "ask_body", async = false, argsJson = "{}")
        audit.toolCancelled("c2")
        audit.toolCancelled("c9")
        audit.interrupted("server")
        audit.sessionEnd("stopped")
        assertEquals(
            listOf(
                "session_start model=gemini-3.8-live async_set=ask_body,ask_goals",
                "mic_open reason=first_open",
                "activity_start",
                "user final=false text=\"any trouble\"",
                "activity_end",
                "user final=true text=\"any trouble codes?\"",
                "user final=true typed=true text=\"typed hi\"",
                "tool_call id=c1 name=ask_fleet async=true args={\"question\":\"codes\"}",
                "assistant text=\"One moment.\"",
                "mic_closed reason=half_duplex",
                "turn_complete",
                "tool_result id=c1 name=ask_fleet success=true ms=1042 scheduling=WHEN_IDLE sent=true " +
                    "response={\"success\":true,\"answer\":\"none\"}",
                "tool_call id=c2 name=ask_body async=false args={}",
                "tool_cancelled id=c2 name=ask_body",
                "tool_cancelled id=c9 name=unknown",
                "interrupted source=server",
                "session_end reason=\"stopped\"",
            ),
            lines,
        )
    }

    @Test
    fun `no async tools says none`() {
        audit.sessionStart("gemini-3.8-live", emptySet())
        assertEquals("session_start model=gemini-3.8-live async_set=none", lines.single())
    }

    @Test
    fun `a mail turn redacts what the assistant says and what mail returned, never what the user said`() {
        audit.user("read my latest email", final = true)
        audit.assistant("Let me look.", readThrough = false)
        audit.toolCall("m1", "read_mail", async = false, argsJson = """{"index":0}""")
        audit.toolResult(result("m1", "read_mail", """{"success":true,"from":"Bob","subject":"Secret plans"}"""))
        audit.toolCall("f1", "ask_fleet", async = false, argsJson = "{}")
        audit.toolResult(result("f1", "ask_fleet", """{"success":true,"answer":"no codes"}"""))
        audit.assistant("Bob wrote about ", readThrough = true)
        audit.assistant("secret plans.", readThrough = true)
        audit.turnComplete()
        audit.assistant("Anything else?", readThrough = false)

        val joined = lines.joinToString("\n")
        assertFalse(joined, joined.contains("Bob"))
        assertFalse(joined, joined.contains("Secret", ignoreCase = true))
        assertEquals(
            listOf(
                "user final=true text=\"read my latest email\"",
                // Spoken before the mail tool was asked for: no mail content exists yet.
                "assistant text=\"Let me look.\"",
                "tool_call id=m1 name=read_mail async=false args={\"index\":0}",
                "tool_result id=m1 name=read_mail success=true ms=1042 scheduling=none sent=true " +
                    "response=\"[redacted: third-party content]\"",
                "tool_call id=f1 name=ask_fleet async=false args={}",
                // Another tool's own result in the same turn is not mail.
                "tool_result id=f1 name=ask_fleet success=true ms=1042 scheduling=none sent=true " +
                    "response={\"success\":true,\"answer\":\"no codes\"}",
                // Once per turn, not once per chunk.
                "assistant text=\"[redacted: third-party content]\"",
                "turn_complete",
                // The next turn starts clear.
                "assistant text=\"Anything else?\"",
            ),
            lines,
        )
    }

    @Test
    fun `the audit redacts from the mail tool call alone, before the session flag is read`() {
        audit.toolCall("m1", "search_mail", async = false, argsJson = "{}")
        audit.assistant("Your bank says hi.", readThrough = false)
        assertEquals("assistant text=\"[redacted: third-party content]\"", lines.last())
    }

    @Test
    fun `a turn the session marked read-through redacts with no tool`() {
        audit.assistant("Overnight, the school emailed.", readThrough = true)
        assertEquals(listOf("assistant text=\"[redacted: third-party content]\""), lines)
    }

    @Test
    fun `every episodic-excluded tool's result is redacted by the production membership test`() {
        LiveToolbox.EPISODIC_EXCLUDED_TOOLS.forEach { name ->
            lines.clear()
            audit.toolResult(result("x", name, """{"success":true,"body":"third party words"}"""))
            assertTrue(lines.single(), lines.single().endsWith("response=\"[redacted: third-party content]\""))
            assertFalse(lines.single().contains("third party words"))
        }
    }

    @Test
    fun `off writes nothing at all`() {
        val quiet = mutableListOf<String>()
        val off = LiveAudit(sink = { quiet += it })
        off.sessionStart("gemini-3.8-live", setOf("ask_fleet"))
        off.user("hello", final = true)
        off.assistant("hi", readThrough = false)
        off.toolCall("1", "ask_fleet", async = true, argsJson = "{}")
        off.toolResult(result("1", "ask_fleet", "{}"))
        off.turnComplete()
        off.sessionEnd("stopped")
        assertTrue(quiet.isEmpty())
    }

    @Test
    fun `text is truncated at 400 with a marker and quoted onto one line`() {
        audit.user("a".repeat(450), final = false)
        assertEquals("user final=false text=\"" + "a".repeat(400) + "...\"", lines.single())
        lines.clear()
        audit.assistant("He said \"no\"\nthen\tleft \\ ok", readThrough = false)
        assertEquals("assistant text=\"He said \\\"no\\\"\\nthen\\tleft \\\\ ok\"", lines.single())
    }

    @Test
    fun `args and response are truncated at 400 and never break the line`() {
        audit.toolCall("1", "ask_goals", async = false, argsJson = "{\"q\":\"" + "b".repeat(500) + "\"}")
        val args = lines.single().substringAfter("args=")
        assertEquals(403, args.length)
        assertTrue(args.endsWith("..."))
        lines.clear()
        audit.toolResult(result("1", "ask_goals", "{\"a\":\"x\ny\"}"))
        assertFalse(lines.single().contains('\n'))
    }

    @Test
    fun `credentials never reach the log`() {
        val key = "AIza" + "S".repeat(35)
        audit.user("my key is $key", final = true)
        audit.toolCall("1", "ask_engine", async = false, argsJson = """{"token":"abc123","api_key":"zzz"}""")
        audit.toolResult(result("1", "ask_engine", """{"Authorization":"Bearer eyJhbGciOi.x.y"}"""))
        audit.sessionEnd("failed wss://host/ws?key=$key Bearer eyJabc.def")
        val joined = lines.joinToString("\n")
        listOf(key, "abc123", "zzz", "eyJhbGciOi", "eyJabc").forEach {
            assertFalse("$it leaked: $joined", joined.contains(it))
        }
        assertTrue(joined, joined.contains("\"token\":\"[redacted]\""))
    }

    @Test
    fun `ids and reasons with spaces stay one token`() {
        audit.micClosed("half duplex")
        audit.toolCancelled("")
        assertEquals(listOf("mic_closed reason=half_duplex", "tool_cancelled id=none name=unknown"), lines)
    }

    @Test
    fun `success falls back to the absence of an error`() {
        assertTrue(LiveAudit.successOf(hasSuccess = true, success = true, hasError = true))
        assertFalse(LiveAudit.successOf(hasSuccess = true, success = false, hasError = false))
        assertTrue(LiveAudit.successOf(hasSuccess = false, success = false, hasError = false))
        assertFalse(LiveAudit.successOf(hasSuccess = false, success = false, hasError = true))
    }

    @Test
    fun `the setting defaults on`() {
        assertTrue(VoiceTurnSettings.AUDIT_LOG_DEFAULT)
    }
}
