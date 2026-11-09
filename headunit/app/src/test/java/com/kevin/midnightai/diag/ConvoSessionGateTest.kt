package com.kevin.midnightai.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A Live session nobody spoke in writes nothing to the conversation spool (2026-11-09, the 182
 * empty head-unit records), and a session someone did speak in keeps every line, in order, with
 * the time each was produced.
 */
class ConvoSessionGateTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Line(val tag: String, val line: String, val atMs: Long)

    private val out = mutableListOf<Line>()
    private var clock = 1_000L
    private val gate = ConvoSessionGate(sink = { t, l, at -> out += Line(t, l, at) }, now = { clock })
    private val audit = ConvoAudit(sink = { gate.write(ConvoAudit.TAG, it) })
    private val latency = ConvoLatency(sink = { gate.write(ConvoLatency.TAG, it) })

    /** The prod pattern: connect, nobody speaks, the server closes it for inactivity. */
    private fun emptySession(reason: String = "Session closed due to client inactivity.") {
        audit.sessionStart("gemini-3.8-live"); latency.start(clock)
        clock += 900; latency.setupComplete(clock)
        clock += 120_000; audit.sessionEnd(reason)
    }

    @Test
    fun `a session with no user and no assistant line writes nothing`() {
        emptySession()
        assertTrue(out.toString(), out.isEmpty())
    }

    @Test
    fun `an offline session that never connected writes nothing`() {
        audit.sessionStart("gemini-3.8-live"); latency.start(clock)
        audit.sessionEnd("Unable to resolve host \"generativelanguage.googleapis.com\"")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a mic opened with nothing heard is still an empty session`() {
        audit.sessionStart("gemini-3.8-live")
        audit.micOpen("first_open")
        audit.activityStart()
        audit.activityEnd()
        audit.micClosed("park")
        audit.sessionEnd("idle")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `the first user line lets the held lines through with their own times, then everything passes`() {
        audit.sessionStart("gemini-3.8-live"); latency.start(clock)
        clock = 1_900; latency.setupComplete(clock)
        clock = 2_000; audit.micOpen("first_open")
        clock = 5_000; audit.user("any codes?", final = true)
        clock = 6_000; audit.assistant("None stored.")
        clock = 7_000; audit.turnComplete()
        clock = 9_000; audit.sessionEnd("idle")
        assertEquals(
            listOf(
                Line(ConvoAudit.TAG, "session_start model=gemini-3.8-live async_set=none", 1_000),
                Line(ConvoLatency.TAG, "cold_start_ms=900", 1_900),
                Line(ConvoAudit.TAG, "mic_open reason=first_open", 2_000),
                Line(ConvoAudit.TAG, "user final=true text=\"any codes?\"", 5_000),
                Line(ConvoAudit.TAG, "assistant text=\"None stored.\"", 6_000),
                Line(ConvoAudit.TAG, "turn_complete", 7_000),
                Line(ConvoAudit.TAG, "session_end reason=\"idle\"", 9_000),
            ),
            out,
        )
    }

    @Test
    fun `an assistant line alone (a proactive line) is a spoken session`() {
        audit.sessionStart("gemini-3.8-live")
        audit.assistant("Coolant is running warm.")
        audit.turnComplete()
        audit.sessionEnd("idle")
        assertEquals(
            listOf(
                "session_start model=gemini-3.8-live async_set=none",
                "assistant text=\"Coolant is running warm.\"",
                "turn_complete",
                "session_end reason=\"idle\"",
            ),
            out.map { it.line },
        )
    }

    @Test
    fun `an empty session then a spoken one keeps only the spoken one`() {
        emptySession()
        latency.toolCall("late", "get_codes", clock)
        latency.toolResponse("late", sent = true, nowMs = clock) // nothing trails out of an empty session
        audit.sessionStart("gemini-3.8-live")
        audit.user("hello", final = true)
        audit.sessionEnd("stopped")
        assertEquals(
            listOf(
                "session_start model=gemini-3.8-live async_set=none",
                "user final=true text=\"hello\"",
                "session_end reason=\"stopped\"",
            ),
            out.map { it.line },
        )
    }

    @Test
    fun `a full hold is flushed rather than kept or lost`() {
        val small = ConvoSessionGate(sink = { t, l, at -> out += Line(t, l, at) }, now = { clock }, maxHeld = 3)
        val a = ConvoAudit(sink = { small.write(ConvoAudit.TAG, it) })
        a.sessionStart("m")
        a.activityStart()
        a.activityEnd() // third held line: the hold is full and flushes
        a.activityStart() // passes straight through from now on
        assertEquals(
            listOf("session_start model=m async_set=none", "activity_start", "activity_end", "activity_start"),
            out.map { it.line },
        )
    }

    @Test
    fun `lines with no session_start before them pass through`() {
        audit.toolCancelled("c1")
        assertEquals(listOf("tool_cancelled id=c1 name=unknown"), out.map { it.line })
    }

    @Test
    fun `an empty session leaves the real spool with nothing to seal, so nothing uploads`() {
        val store = DriveLogStore(File(tmp.root, "convolog"))
        val spool = ConvoSessionGate(sink = { t, l, _ ->
            store.append(listOf(DriveLog.threadtime("11-09 08:07:22.929", 1, 1, t, l)))
        })
        val a = ConvoAudit(sink = { spool.write(ConvoAudit.TAG, it) })
        val l = ConvoLatency(sink = { spool.write(ConvoLatency.TAG, it) })
        a.sessionStart("gemini-3.8-live"); l.start(1); l.setupComplete(900)
        a.sessionEnd("Session closed due to client inactivity.")
        assertNull(store.seal())
        assertTrue(store.pending().isEmpty())
    }
}
