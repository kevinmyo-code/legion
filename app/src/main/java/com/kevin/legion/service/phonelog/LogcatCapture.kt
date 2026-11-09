package com.kevin.legion.service.phonelog

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads this app's own logcat as it is written and hands each burst of lines, redacted, to [sink]
 * (2026-11-09, Kevin: "Send everything from the phone to the engine so we can audit without adb").
 *
 * **Why logcat and not a `Log`/Timber tee.** A tee sees only the calls this app's own Kotlin makes.
 * The app's process also logs through the framework (`View`, `InputTransport`, `AudioTrack`),
 * through libraries (Mapbox, WorkManager, Supabase, OkHttp, the ONNX runtime's native
 * `ORTTelemetry`), and through `AndroidRuntime` when it dies; on the 2026-11-06 A25 capture those
 * were most of the app's lines, and the crash lines are the ones an audit needs most. Since
 * Android 4.1 an app may read its own uid's log entries with no permission (`READ_LOGS` is not
 * needed and not asked for), and `--pid` exists from API 24, the app's minSdk.
 *
 * **Why a stream and not a snapshot.** The head unit snapshots `logcat -d` every minute. The
 * phone's log buffer is shared by every app, and this app alone wrote about 250 KB a minute during
 * a Live session (Samsung's per-frame `View` line), so a buffer of a few hundred KB wraps between
 * snapshots. One long-running `logcat -v threadtime --pid=<me> -T <process start>` on its own
 * thread sees every line once, backfilled from the moment the process started.
 *
 * **Restarts.** If logcat exits (low memory can kill it), the reader waits [RESTART_BASE_MS],
 * doubling to [RESTART_MAX_MS], and resumes from the last line's stamp, skipping lines up to that
 * line. A logcat that cannot be started at all is said once, into the log itself, so a reader of the
 * batches knows silence is "unreadable", not "nothing happened".
 *
 * **The previous run** ([recoverPreviousRun]): the reader dies with its process, so a crash's last
 * lines (`AndroidRuntime: FATAL EXCEPTION`) are still in the system buffer but were never read. At
 * the next start, `logcat -d -T <last captured stamp>` is read once, kept to the previous run's pid,
 * and everything after the last captured line goes to [sink] before the live stream begins.
 *
 * Runs only on its own thread ([run] blocks); never on the main thread, never on the voice path.
 * [startProcess] is the seam a test fakes.
 */
class LogcatCapture(
    private val myPid: Int,
    private val fromMs: Long,
    private val state: RunState,
    private val sink: (lines: List<String>, atMs: Long) -> Unit,
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Volatile private var stopped = false
    @Volatile private var process: Process? = null
    private var lastLine: String? = null
    private var lastStamp: String? = null
    private var lastPersistMs = 0L
    private var saidUnreadable = false

    /** The previous run's tail. Returns how many lines it recovered. */
    fun recoverPreviousRun(previous: RunState.Snapshot): Int {
        val pid = previous.pid?.takeIf { it != myPid.toString() }
        val from = previous.lastLineAtMs
        val raw = if (pid != null && from != null) {
            runCatching { readAll(dumpCommand(LogcatLines.logcatTime(from))) }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val theirs = LogcatLines.ours(raw, setOfNotNull(pid)).filterNot { LogcatLines.isNoise(it) }
        val redacted = theirs.map(LogcatRedactor::redactLine)
        val fresh = LogcatLines.newSince(redacted, previous.lastLine)
        if (fresh.isNotEmpty()) {
            val note = marker("recovered ${fresh.size} lines the previous run (pid $pid) wrote after its last capture")
            sink(listOf(note) + fresh, now())
        }
        return fresh.size
    }

    /** Blocks, streaming until [stop]. */
    fun run() {
        var waitMs = RESTART_BASE_MS
        var first = true
        while (!stopped) {
            val since = if (first) LogcatLines.logcatTime(fromMs) else lastStamp ?: LogcatLines.logcatTime(fromMs)
            val skipThrough = if (first) null else lastLine
            first = false
            val startedAt = now()
            val streamed = runCatching { stream(streamCommand(since), skipThrough) }
            if (streamed.isFailure && !saidUnreadable) {
                saidUnreadable = true
                sink(listOf(marker("logcat could not be read: ${streamed.exceptionOrNull()?.message}")), now())
            }
            if (stopped) break
            // A stream that ran a while and then ended is a fresh failure, not the next in a run.
            if (now() - startedAt >= HEALTHY_MS) waitMs = RESTART_BASE_MS
            sleep(waitMs)
            waitMs = (waitMs * 2).coerceAtMost(RESTART_MAX_MS)
        }
    }

    fun stop() {
        stopped = true
        process?.destroy()
    }

    private fun stream(command: List<String>, skipThrough: String?) {
        val proc = startProcess(command)
        process = proc
        var skipping = skipThrough != null
        try {
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                val burst = ArrayList<String>(BURST)
                while (!stopped) {
                    val line = reader.readLine() ?: break
                    burst.add(line)
                    while (burst.size < BURST && reader.ready()) burst.add(reader.readLine() ?: break)
                    val kept = keep(burst, skipping, skipThrough)
                    if (skipping && kept.skipDone) skipping = false
                    burst.clear()
                    if (kept.lines.isNotEmpty()) deliver(kept.lines)
                }
            }
        } finally {
            proc.destroy()
            process = null
        }
    }

    private class Kept(val lines: List<String>, val skipDone: Boolean)

    private fun keep(burst: List<String>, skipping: Boolean, skipThrough: String?): Kept {
        var start = 0
        var done = !skipping
        if (skipping) {
            val at = burst.lastIndexOf(skipThrough)
            // The anchor is in this burst: everything up to it was captured before the restart.
            // Not in the first burst: the buffer moved on, so nothing can be skipped safely.
            start = if (at >= 0) at + 1 else 0
            done = true
        }
        val mine = myPid.toString()
        val out = ArrayList<String>(burst.size - start)
        for (i in start until burst.size) {
            val line = burst[i]
            if (LogcatLines.pidOf(line) == mine && !LogcatLines.isNoise(line)) out.add(line)
        }
        return Kept(out, done)
    }

    private fun deliver(lines: List<String>) {
        val at = now()
        val redacted = lines.map(LogcatRedactor::redactLine)
        sink(redacted, at)
        lastLine = lines.last()
        lastStamp = LogcatLines.stampOf(lines.last()) ?: lastStamp
        if (at - lastPersistMs >= PERSIST_EVERY_MS) {
            lastPersistMs = at
            // The anchor is stored redacted: recovery compares it against redacted lines.
            state.captured(redacted.last(), at)
        }
    }

    private fun readAll(command: List<String>): List<String> {
        val proc = startProcess(command)
        return try {
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                val out = ArrayList<String>()
                while (out.size < RECOVER_MAX_LINES) out.add(reader.readLine() ?: break)
                out
            }
        } finally {
            proc.destroy()
        }
    }

    private fun marker(text: String): String =
        "${LogcatLines.logcatTime(now())} $myPid $myPid I $MARKER_TAG: $text"

    private fun streamCommand(since: String) =
        listOf("logcat", "-v", "threadtime", "--pid=$myPid", "-T", since)

    private fun dumpCommand(since: String) = listOf("logcat", "-d", "-v", "threadtime", "-T", since)

    companion object {
        const val MARKER_TAG = "PHONE_LOG"
        const val RESTART_BASE_MS = 5_000L
        const val RESTART_MAX_MS = 5 * 60_000L
        private const val HEALTHY_MS = 60_000L
        private const val BURST = 400
        private const val PERSIST_EVERY_MS = 2_000L
        private const val RECOVER_MAX_LINES = 50_000
    }
}
