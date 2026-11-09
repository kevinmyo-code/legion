package com.kevin.legion.service.phonelog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The phone's logcat reader against a scripted `logcat` (2026-11-09): what it asks logcat for, which
 * lines it keeps, the restart that skips what it already had, the previous run's tail, and the
 * "unreadable" line when logcat cannot start.
 */
class LogcatCaptureTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeProcess(text: String) : Process() {
        private val out = ByteArrayInputStream(text.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }

    private val commands = mutableListOf<List<String>>()
    private val delivered = mutableListOf<String>()
    private var clock = 1_762_718_400_000L

    private fun line(pid: Int, time: String, tag: String, msg: String) = "11-09 $time  $pid  $pid I $tag: $msg"

    private fun capture(
        outputs: ArrayDeque<String>,
        state: RunState = RunState(tmp.newFile("run.properties")),
        onSleep: (LogcatCapture) -> Unit = { it.stop() },
    ): LogcatCapture {
        lateinit var c: LogcatCapture
        c = LogcatCapture(
            myPid = 4242,
            fromMs = clock,
            state = state,
            sink = { lines, _ -> delivered += lines },
            startProcess = { cmd ->
                commands += cmd
                FakeProcess(outputs.removeFirstOrNull() ?: throw java.io.IOException("no logcat"))
            },
            now = { clock },
            sleep = { onSleep(c) },
        )
        return c
    }

    @Test fun `it streams its own pid from process start, threadtime`() {
        capture(ArrayDeque(listOf(""))).run()
        val cmd = commands.single()
        assertEquals(listOf("logcat", "-v", "threadtime", "--pid=4242", "-T"), cmd.take(5))
        assertTrue(cmd[5], Regex("""\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}""").matches(cmd[5]))
    }

    @Test fun `only this pid's threadtime lines are kept, redacted, in order`() {
        val text = listOf(
            "--------- beginning of main",
            line(4242, "14:00:00.000", "View", "setRequestedFrameRate frameRate=0.0"),
            line(9999, "14:00:00.001", "Other", "not ours"),
            line(4242, "14:00:00.002", "OkHttp", "Authorization: Bearer abcdefghijklmnopqrstuvwxyz"),
            line(4242, "14:00:00.003", "AndroidRuntime", "FATAL EXCEPTION: main"),
        ).joinToString("\n")
        capture(ArrayDeque(listOf(text))).run()
        assertEquals(
            listOf(
                line(4242, "14:00:00.000", "View", "setRequestedFrameRate frameRate=0.0"),
                line(4242, "14:00:00.002", "OkHttp", "Authorization: [redacted]"),
                line(4242, "14:00:00.003", "AndroidRuntime", "FATAL EXCEPTION: main"),
            ),
            delivered,
        )
    }

    @Test fun `a restart resumes at the last stamp and skips what it already had`() {
        val a = line(4242, "14:00:00.000", "A", "one")
        val b = line(4242, "14:00:01.000", "B", "two")
        val c = line(4242, "14:00:02.000", "C", "three")
        var sleeps = 0
        capture(
            ArrayDeque(listOf("$a\n$b", "$b\n$c")),
            onSleep = { if (++sleeps == 2) it.stop() },
        ).run()
        assertEquals(listOf(a, b, c), delivered)
        assertEquals("11-09 14:00:01.000", commands[1].last())
    }

    @Test fun `a logcat that cannot start is said once, in the log itself`() {
        var sleeps = 0
        capture(ArrayDeque(), onSleep = { if (++sleeps == 3) it.stop() }).run()
        assertEquals(1, delivered.size)
        val said = delivered.single()
        assertTrue(said, said.contains("I ${LogcatCapture.MARKER_TAG}: logcat could not be read"))
    }

    @Test fun `the previous run's tail is recovered past its anchor, for its pid only`() {
        val anchor = line(1111, "13:59:59.000", "Tag", "last captured")
        val dump = listOf(
            line(1111, "13:59:58.000", "Tag", "already had"),
            anchor,
            line(2222, "14:00:00.000", "Else", "another app's pid"),
            line(1111, "14:00:00.500", "AndroidRuntime", "FATAL EXCEPTION: main"),
            line(1111, "14:00:00.501", "AndroidRuntime", "java.lang.IllegalStateException: boom"),
        ).joinToString("\n")
        val c = capture(ArrayDeque(listOf(dump)))
        val previous = RunState.Snapshot(
            pid = "1111", runId = "run-0", lastLine = anchor, lastLineAtMs = clock - 1_000, lastExitSeenMs = 0,
        )
        assertEquals(2, c.recoverPreviousRun(previous))
        assertEquals(listOf("logcat", "-d", "-v", "threadtime", "-T"), commands.single().take(5))
        assertTrue(delivered.first().contains("recovered 2 lines"))
        assertEquals(
            listOf(
                line(1111, "14:00:00.500", "AndroidRuntime", "FATAL EXCEPTION: main"),
                line(1111, "14:00:00.501", "AndroidRuntime", "java.lang.IllegalStateException: boom"),
            ),
            delivered.drop(1),
        )
    }

    @Test fun `nothing to recover without a previous run`() {
        val c = capture(ArrayDeque())
        val none = RunState.Snapshot(pid = null, runId = null, lastLine = null, lastLineAtMs = null, lastExitSeenMs = 0)
        assertEquals(0, c.recoverPreviousRun(none))
        assertTrue(commands.isEmpty())
    }

    @Test fun `the anchor is persisted redacted so recovery can match it`() {
        val state = RunState(tmp.newFile("state.properties"))
        val text = line(4242, "14:00:00.000", "OkHttp", "Cookie: sid=abc")
        capture(ArrayDeque(listOf(text)), state = state).run()
        assertEquals(line(4242, "14:00:00.000", "OkHttp", "Cookie: [redacted]"), state.read().lastLine)
    }
}
