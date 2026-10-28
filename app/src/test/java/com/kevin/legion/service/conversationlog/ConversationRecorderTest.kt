package com.kevin.legion.service.conversationlog

import com.kevin.legion.service.LiveAudit
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The spool's write path (conversation-records ticket 03): only the four conversation tags, nothing
 * when the switch is off, and a spooled batch byte-identical to what [LiveAudit] emitted. The last
 * test renders a spooled batch through `tools/voice_audit.py` (skipped when python is absent).
 */
class ConversationRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }
    private var on = true
    private var clock = 0L

    private fun recorder(spool: ConversationSpool) =
        ConversationRecorder(spool, direct, enabled = { on }, now = { clock }, pid = { 4242 }, tid = { 77 })

    private fun stampOf(ms: Long) =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ms))

    @Test fun `switch off writes nothing at all`() {
        val spool = ConversationSpool(tmp.newFolder())
        on = false
        val rec = recorder(spool)
        rec.record("LIVE_AUDIT", "turn_complete")
        rec.record("LIVE_LATENCY", "reply_ms=500")
        assertFalse(spool.hasContent())
        assertEquals(0L, spool.totalBytes())
    }

    @Test fun `only the four conversation tags are spooled`() {
        val spool = ConversationSpool(tmp.newFolder())
        val rec = recorder(spool)
        val all = listOf("LIVE_AUDIT", "LIVE_LATENCY", "LIVE_FOLLOWUP", "LIVE_TOOL_CANCEL")
        (all + listOf("LIVE_TURN", "LIVE_BARGE", "Breadcrumb"))
            .forEach { rec.record(it, "m") }
        val tags = spool.seal()!!.file.readLines().map { it.substringAfter(" I ").substringBefore(":") }
        assertEquals(listOf("LIVE_AUDIT", "LIVE_LATENCY", "LIVE_FOLLOWUP", "LIVE_TOOL_CANCEL"), tags)
    }

    @Test fun `a spooled batch is byte-identical to the lines LiveAudit emitted`() {
        val spool = ConversationSpool(tmp.newFolder())
        val rec = recorder(spool)
        val emitted = mutableListOf<String>()
        val audit = LiveAudit(
            sink = { emitted += it; rec.record(LiveAudit.TAG, it) },
            isReadThroughTool = { it == "read_mail" },
            onSession = {},
        ).apply { enabled = true }
        clock = 1_761_668_400_000
        audit.sessionStart("gemini-3.8-live", setOf("ask_fleet"))
        audit.user("Any trouble codes?", final = true)
        audit.user("typed \"hello\"\nthere", final = true, typed = true)
        audit.toolCall("m1", "read_mail", async = false, argsJson = "{\"index\":0}")
        audit.assistant("Your bank wrote to say...", readThrough = false)
        audit.toolResult(LiveAudit.ToolResult("m1", "read_mail", true, 300, false, true, "{\"body\":\"secret\"}"))
        audit.turnComplete()
        audit.sessionEnd("stopped")

        val expected = emitted.joinToString("") { "${stampOf(clock)} 4242 77 I LIVE_AUDIT: $it\n" }
        val batch = spool.seal()!!
        assertEquals(expected, batch.file.readText())
        assertFalse(batch.file.readText().contains("secret"))
        assertFalse(batch.file.readText().contains("Your bank"))
    }

    @Test fun `a spooled batch renders through tools voice_audit py`() {
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/voice_audit.py") }.firstOrNull { it.isFile }
        assumeTrue("tools/voice_audit.py not found", script != null)
        val python = listOf("python", "python3").firstOrNull { runs(it) }
        assumeTrue("python is not installed", python != null)

        val spool = ConversationSpool(tmp.newFolder())
        val rec = recorder(spool)
        val audit = LiveAudit(sink = { rec.record(LiveAudit.TAG, it) }, isReadThroughTool = { false }, onSession = {})
            .apply { enabled = true }
        clock = 1_761_668_400_000
        audit.sessionStart("gemini-3.8-live", setOf("ask_fleet"))
        clock += 1_000
        audit.user("Any trouble codes?", final = true)
        clock += 500
        rec.record("LIVE_LATENCY", "reply_ms=674")
        audit.assistant("No trouble codes stored.", readThrough = false)
        audit.turnComplete()
        audit.sessionEnd("stopped")
        val batch = spool.seal()!!

        val process = ProcessBuilder(python!!, script!!.path, batch.file.path)
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(out, 0, process.exitValue())
        assertTrue(out, out.contains("== session 1  model=gemini-3.8-live"))
        assertTrue(out, out.contains("USER: Any trouble codes?"))
        assertTrue(out, out.contains("reply 674ms"))
        assertTrue(out, out.contains("ASSISTANT: No trouble codes stored."))
        assertTrue(out, out.contains("session end (stopped)"))
    }

    private fun runs(cmd: String): Boolean = runCatching {
        val p = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}
