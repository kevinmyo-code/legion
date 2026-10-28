package com.kevin.midnightai.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The head unit's LIVE_AUDIT and LIVE_LATENCY lines, pinned verbatim. They are the phone's formats
 * (`app/.../service/LiveAuditTest.kt` pins the same strings), `tools/voice_audit.py` parses them,
 * and [FIXTURE] is byte-for-byte the batch `server/tests/test_device_logs_render.py` renders. A
 * change here is a change in all three.
 */
class ConvoAuditTest {

    private val lines = mutableListOf<String>()
    private val audit = ConvoAudit(sink = { lines += it })

    private fun result(id: String, name: String, response: String, sent: Boolean = true, ms: Long = 200) =
        ConvoAudit.ToolResult(id, name, success = true, ms = ms, sent = sent, responseJson = response)

    @Test
    fun `every line format matches the phone's`() {
        audit.sessionStart("gemini-3.1-flash-live-preview")
        audit.micOpen("first_open")
        audit.activityStart()
        audit.user("any trouble", final = false)
        audit.activityEnd()
        audit.user("any trouble codes?", final = true)
        audit.toolCall("c1", "get_codes", argsJson = """{"verbose":true}""")
        audit.assistant("One moment.")
        audit.micClosed("half_duplex")
        audit.turnComplete()
        audit.toolResult(result("c1", "get_codes", """{"success":true,"codes":[]}"""))
        audit.toolCall("c2", "ask_engine", argsJson = "{}")
        audit.toolCancelled("c2")
        audit.toolCancelled("c9")
        audit.interrupted("server")
        audit.interrupted("barge_in")
        audit.micClosed("park")
        audit.sessionEnd("stopped")
        assertEquals(
            listOf(
                "session_start model=gemini-3.1-flash-live-preview async_set=none",
                "mic_open reason=first_open",
                "activity_start",
                "user final=false text=\"any trouble\"",
                "activity_end",
                "user final=true text=\"any trouble codes?\"",
                "tool_call id=c1 name=get_codes async=false args={\"verbose\":true}",
                "assistant text=\"One moment.\"",
                "mic_closed reason=half_duplex",
                "turn_complete",
                "tool_result id=c1 name=get_codes success=true ms=200 scheduling=none sent=true " +
                    "response={\"success\":true,\"codes\":[]}",
                "tool_call id=c2 name=ask_engine async=false args={}",
                "tool_cancelled id=c2 name=ask_engine",
                "tool_cancelled id=c9 name=unknown",
                "interrupted source=server",
                "interrupted source=barge_in",
                "mic_closed reason=park",
                "session_end reason=\"stopped\"",
            ),
            lines,
        )
    }

    @Test
    fun `a response held back after a cancellation says sent=false`() {
        audit.toolResult(result("c3", "get_health", """{"success":true}""", sent = false, ms = 950))
        assertEquals(
            "tool_result id=c3 name=get_health success=true ms=950 scheduling=none sent=false " +
                "response={\"success\":true}",
            lines.single(),
        )
    }

    @Test
    fun `text is cut at 400 characters with the phone's marker, and stays one quoted line`() {
        audit.assistant("a".repeat(ConvoAudit.MAX_TEXT + 50))
        assertEquals("assistant text=\"" + "a".repeat(ConvoAudit.MAX_TEXT) + "...\"", lines.single())
        lines.clear()
        audit.user("He said \"no\"\nthen \\ left", final = true)
        assertEquals("user final=true text=\"He said \\\"no\\\"\\nthen \\\\ left\"", lines.single())
    }

    @Test
    fun `credentials are scrubbed before the cut`() {
        audit.toolCall("k1", "ask_engine", argsJson = """{"token":"abc123","q":"https://x?key=AIzaSyA1234567890123456789012"}""")
        val line = lines.single()
        assertFalse(line, line.contains("abc123"))
        assertFalse(line, line.contains("AIzaSy"))
        assertTrue(line, line.contains("\"token\":\"[redacted]\""))
    }

    @Test
    fun `nothing on the head unit is read-through today, so nothing is redacted`() {
        assertTrue(ConvoAudit.READ_THROUGH_TOOLS.isEmpty())
        audit.toolResult(result("e1", "ask_engine", """{"success":true,"answer":"milk, eggs"}"""))
        assertTrue(lines.single(), lines.single().endsWith("response={\"success\":true,\"answer\":\"milk, eggs\"}"))
    }

    @Test
    fun `a read-through tool redacts the assistant's speech and its own result, never the user's words`() {
        val gated = ConvoAudit(sink = { lines += it }, isReadThroughTool = { it == "read_mail" })
        gated.user("read my latest email", final = true)
        gated.assistant("Let me look.")
        gated.toolCall("m1", "read_mail", argsJson = """{"index":0}""")
        gated.toolResult(result("m1", "read_mail", """{"success":true,"from":"Bob"}"""))
        gated.assistant("Bob wrote ")
        gated.assistant("about plans.")
        gated.turnComplete()
        gated.assistant("Anything else?")
        val joined = lines.joinToString("\n")
        assertFalse(joined, joined.contains("Bob"))
        assertEquals(
            listOf(
                "user final=true text=\"read my latest email\"",
                "assistant text=\"Let me look.\"",
                "tool_call id=m1 name=read_mail async=false args={\"index\":0}",
                "tool_result id=m1 name=read_mail success=true ms=200 scheduling=none sent=true " +
                    "response=\"[redacted: third-party content]\"",
                "assistant text=\"[redacted: third-party content]\"",
                "turn_complete",
                "assistant text=\"Anything else?\"",
            ),
            lines,
        )
    }

    @Test
    fun `success is read from the response like the phone`() {
        assertTrue(ConvoAudit.successOf(hasSuccess = true, success = true, hasError = false))
        assertFalse(ConvoAudit.successOf(hasSuccess = true, success = false, hasError = false))
        assertFalse(ConvoAudit.successOf(hasSuccess = false, success = false, hasError = true))
        assertTrue(ConvoAudit.successOf(hasSuccess = false, success = false, hasError = false))
    }

    @Test
    fun `latency lines are the phone's keys`() {
        val out = mutableListOf<String>()
        val latency = ConvoLatency(sink = { out += it })
        latency.start(1_000)
        latency.setupComplete(1_900)
        latency.setupComplete(2_500) // once per start
        latency.toolCall("c1", "get_codes", 4_100)
        assertEquals(200L, latency.toolResponse("c1", sent = true, nowMs = 4_300))
        assertEquals(-1L, latency.toolResponse("never", sent = true, nowMs = 4_300))
        assertEquals(listOf("cold_start_ms=900", "tool_ms=200 tool=get_codes sent=true async=false"), out)
    }

    @Test
    fun `reply latency spans a tool round trip and is consumed once`() {
        val out = mutableListOf<String>()
        val latency = ConvoLatency(sink = { out += it })
        latency.inputTranscript(3_900)
        latency.turnComplete(hadAudio = false) // the silent tool-call turn keeps the mark
        latency.firstAudio(5_000)
        latency.firstAudio(5_100) // already consumed
        latency.turnComplete(hadAudio = true)
        latency.inputTranscript(6_000)
        latency.turnComplete(hadAudio = true) // a turn that played audio clears it
        latency.firstAudio(7_000)
        assertEquals(listOf("reply_ms=1100"), out)
    }

    @Test
    fun `the fixture batch is what the head unit writes, and the redactor leaves it alone`() {
        val written = mutableListOf<String>()
        var stamp = "10-28 16:20:00.000"
        var tid = 5678
        val a = ConvoAudit(sink = { written += DriveLog.threadtime(stamp, 1234, tid, ConvoAudit.TAG, it) })
        val l = ConvoLatency(sink = { written += DriveLog.threadtime(stamp, 1234, tid, ConvoLatency.TAG, it) })
        a.sessionStart("gemini-3.1-flash-live-preview"); l.start(1)
        stamp = "10-28 16:20:00.900"; l.setupComplete(901)
        stamp = "10-28 16:20:01.000"; a.micOpen("first_open")
        stamp = "10-28 16:20:03.900"; tid = 5679; a.user(" Any trouble codes?", final = false); l.inputTranscript(3_900)
        stamp = "10-28 16:20:04.100"; a.toolCall("c1", "get_codes", "{}"); l.toolCall("c1", "get_codes", 4_100)
        stamp = "10-28 16:20:04.300"; tid = 5680
        val ms = l.toolResponse("c1", sent = true, nowMs = 4_300)
        a.toolResult(ConvoAudit.ToolResult("c1", "get_codes", true, ms, sent = true, responseJson = """{"success":true,"codes":[]}"""))
        stamp = "10-28 16:20:04.301"; tid = 5679; a.user("Any trouble codes?", final = true)
        stamp = "10-28 16:20:04.302"; a.turnComplete(); l.turnComplete(hadAudio = false)
        stamp = "10-28 16:20:05.000"; l.firstAudio(5_000); a.micClosed("half_duplex")
        stamp = "10-28 16:20:05.050"; a.assistant("No codes stored.")
        stamp = "10-28 16:20:06.000"; a.turnComplete(); l.turnComplete(hadAudio = true)
        stamp = "10-28 16:20:20.000"; a.sessionEnd("idle")
        assertEquals(FIXTURE.trimEnd().lines(), written)
        written.forEach { assertEquals(it, LogRedactor.redact(it)) }
    }

    @Test
    fun `upload reasons are the engine's wire strings`() {
        assertEquals("periodic", UploadReason.PERIODIC.wire)
        assertEquals("conversation", UploadReason.CONVERSATION.wire)
    }

    companion object {
        /** Same text as `FIXTURE` in `server/tests/test_device_logs_render.py`. */
        val FIXTURE = """
10-28 16:20:00.000 1234 5678 I LIVE_AUDIT: session_start model=gemini-3.1-flash-live-preview async_set=none
10-28 16:20:00.900 1234 5678 I LIVE_LATENCY: cold_start_ms=900
10-28 16:20:01.000 1234 5678 I LIVE_AUDIT: mic_open reason=first_open
10-28 16:20:03.900 1234 5679 I LIVE_AUDIT: user final=false text=" Any trouble codes?"
10-28 16:20:04.100 1234 5679 I LIVE_AUDIT: tool_call id=c1 name=get_codes async=false args={}
10-28 16:20:04.300 1234 5680 I LIVE_LATENCY: tool_ms=200 tool=get_codes sent=true async=false
10-28 16:20:04.300 1234 5680 I LIVE_AUDIT: tool_result id=c1 name=get_codes success=true ms=200 scheduling=none sent=true response={"success":true,"codes":[]}
10-28 16:20:04.301 1234 5679 I LIVE_AUDIT: user final=true text="Any trouble codes?"
10-28 16:20:04.302 1234 5679 I LIVE_AUDIT: turn_complete
10-28 16:20:05.000 1234 5679 I LIVE_LATENCY: reply_ms=1100
10-28 16:20:05.000 1234 5679 I LIVE_AUDIT: mic_closed reason=half_duplex
10-28 16:20:05.050 1234 5679 I LIVE_AUDIT: assistant text="No codes stored."
10-28 16:20:06.000 1234 5679 I LIVE_AUDIT: turn_complete
10-28 16:20:20.000 1234 5679 I LIVE_AUDIT: session_end reason="idle"
""".trimStart()
    }
}
