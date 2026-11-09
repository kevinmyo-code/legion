package com.kevin.legion.service.phonelog

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How the app's process died, as lines for the `crash` spool (2026-11-09).
 *
 * - **An uncaught exception** ([fatalLines]): written by the dying process, synchronously, inside
 *   the default handler and before the previous handler (Android's own, which logs
 *   `FATAL EXCEPTION` and kills the process) runs. Nothing is uploaded from the dying process; the
 *   next start sends it.
 * - **Android's exit records** ([exitLines]): `ActivityManager.getHistoricalProcessExitReasons` (API
 *   30+), read at the next start, covers what no handler sees: an ANR (with the system's trace), a
 *   low-memory kill, a native crash, a kill for excessive resource use.
 *
 * Every line is threadtime-shaped (tag `AndroidRuntime` for the stack, `ExitInfo` for the records)
 * so `manage.py device_logs` filters and orders them like any logcat line, and every line goes
 * through [LogcatRedactor] (an exception message can carry a URL or an address). Pure: the Android
 * calls live in `PhoneLog`, this file only formats, so the unit test needs no Robolectric.
 */
object CrashCapture {
    const val FATAL_TAG = "AndroidRuntime"
    const val EXIT_TAG = "ExitInfo"
    private const val MAX_TRACE_LINES = 4_000

    /** `ApplicationExitInfo.REASON_*` names, indexed by value, so this file needs no API 30 class. */
    private val REASONS = listOf(
        "UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
        "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED",
        "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED",
    )

    /** Exits an audit should look at: a crash, an ANR, a kill the user did not ask for. */
    private val FAILURES = setOf(
        "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR", "INITIALIZATION_FAILURE",
        "EXCESSIVE_RESOURCE_USAGE", "DEPENDENCY_DIED",
    )

    /** `ApplicationExitInfo.REASON_ANR`. */
    const val REASON_ANR = 6

    fun reasonName(reason: Int): String = REASONS.getOrNull(reason) ?: "REASON_$reason"

    /** One exit record, the fields of `ApplicationExitInfo` this needs. */
    data class Exit(
        val timestampMs: Long,
        val pid: Int,
        val processName: String,
        val reason: Int,
        val status: Int,
        val importance: Int,
        val pssKb: Long,
        val rssKb: Long,
        val description: String?,
        val trace: List<String> = emptyList(),
    )

    /** The lines for an uncaught [throwable] on [threadName], stamped [atMs]. */
    fun fatalLines(
        throwable: Throwable,
        threadName: String,
        pid: Int,
        tid: Int,
        runId: String,
        atMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<String> {
        val stamp = stamp(atMs, zone)
        val head = "$stamp $pid $tid E $FATAL_TAG: "
        val body = listOf("FATAL EXCEPTION: $threadName (run $runId, caught by PhoneLog before the process died)") +
            throwable.stackTraceToString().lines().filter { it.isNotBlank() }.take(MAX_TRACE_LINES)
        return body.map { head + LogcatRedactor.redactMessage(FATAL_TAG, it.replace('\t', ' ')) }
    }

    /** The lines for one exit record. [previousRunId] names the run it most likely was. */
    fun exitLines(exit: Exit, previousRunId: String?, zone: TimeZone = TimeZone.getDefault()): List<String> {
        val stamp = stamp(exit.timestampMs, zone)
        val head = "$stamp ${exit.pid} ${exit.pid} ${if (isFailure(exit.reason)) "E" else "I"} $EXIT_TAG: "
        val summary = "exit reason=${reasonName(exit.reason)} status=${exit.status} " +
            "process=${exit.processName} importance=${exit.importance} pss_kb=${exit.pssKb} " +
            "rss_kb=${exit.rssKb} run=${previousRunId ?: "unknown"} " +
            "description=\"${(exit.description ?: "").replace('"', '\'')}\""
        val trace = exit.trace.take(MAX_TRACE_LINES).map { "trace: $it" }
        return (listOf(summary) + trace).map { head + LogcatRedactor.redactMessage(EXIT_TAG, it.replace('\t', ' ')) }
    }

    /** True for an exit an audit should look at: a crash, an ANR, a kill the user did not ask for. */
    fun isFailure(reason: Int): Boolean = reasonName(reason) in FAILURES

    private fun stamp(atMs: Long, zone: TimeZone): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }.format(Date(atMs))
}
