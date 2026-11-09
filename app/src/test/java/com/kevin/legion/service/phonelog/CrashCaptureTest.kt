package com.kevin.legion.service.phonelog

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The crash spool's lines (2026-11-09): an uncaught exception, and Android's exit records. */
class CrashCaptureTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val at = 1_794_254_400_000L // 2026-11-09 20:00:00 UTC
    private val threadtime = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} \d+ \d+ [VDIWEF] \S+: .*""")

    @Test fun `an uncaught exception becomes threadtime AndroidRuntime lines, stack included`() {
        val boom = IllegalStateException("engine said no to bob@example.com")
        val lines = CrashCapture.fatalLines(boom, "main", 4242, 4242, "run-1", at, utc)
        assertTrue(lines.all { threadtime.matches(it) })
        val expected = "11-09 20:00:00.000 4242 4242 E AndroidRuntime: FATAL EXCEPTION: main (run run-1"
        assertTrue(lines.first(), lines.first().startsWith(expected))
        assertTrue(lines[1].endsWith("java.lang.IllegalStateException: engine said no to [email]"))
        assertTrue(lines.any { it.contains("at com.kevin.legion.service.phonelog.CrashCaptureTest") })
        assertFalse(lines.any { it.contains('\t') })
    }

    @Test fun `a cause is kept`() {
        val boom = RuntimeException("outer", java.io.IOException("inner"))
        val lines = CrashCapture.fatalLines(boom, "worker-1", 1, 2, "r", at, utc)
        assertTrue(lines.any { it.contains("Caused by: java.io.IOException: inner") })
    }

    @Test fun `an ANR exit record carries its reason, its numbers and its trace`() {
        val exit = CrashCapture.Exit(
            timestampMs = at, pid = 4242, processName = "com.kevin.legion", reason = CrashCapture.REASON_ANR,
            status = 0, importance = 100, pssKb = 210_000, rssKb = 300_000,
            description = "Input dispatching timed out",
            trace = listOf("\"main\" prio=5 tid=1 Blocked", "  at x.y(Z.java:1)"),
        )
        val lines = CrashCapture.exitLines(exit, previousRunId = "run-0", zone = utc)
        assertEquals(
            "11-09 20:00:00.000 4242 4242 E ExitInfo: exit reason=ANR status=0 process=com.kevin.legion " +
                "importance=100 pss_kb=210000 rss_kb=300000 run=run-0 description=\"Input dispatching timed out\"",
            lines[0],
        )
        assertEquals("11-09 20:00:00.000 4242 4242 E ExitInfo: trace: \"main\" prio=5 tid=1 Blocked", lines[1])
        assertEquals(3, lines.size)
    }

    @Test fun `a user swipe is an info line and an unknown run says so`() {
        val exit = CrashCapture.Exit(at, 7, "com.kevin.legion", 10, 0, 400, 0, 0, null)
        val line = CrashCapture.exitLines(exit, previousRunId = null, zone = utc).single()
        assertTrue(line, line.contains(" I ExitInfo: exit reason=USER_REQUESTED"))
        assertTrue(line.contains("run=unknown"))
    }

    @Test fun `reason names cover the platform's values and fall back to the number`() {
        assertEquals("LOW_MEMORY", CrashCapture.reasonName(3))
        assertEquals("CRASH_NATIVE", CrashCapture.reasonName(5))
        assertEquals("REASON_99", CrashCapture.reasonName(99))
        assertTrue(CrashCapture.isFailure(3) && CrashCapture.isFailure(6))
        assertFalse(CrashCapture.isFailure(10))
    }
}
