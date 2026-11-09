package com.kevin.legion.service.phonelog

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pure half of the phone's logcat capture: which lines are this app's, which are new since the
 * last capture, and which are noise. Copied in spirit from the head unit's `diag/LogcatSnapshot`
 * (the two apps share nothing but the engine's API).
 *
 * **Why a SET of pids.** The reader asks logcat for its own pid, but the lines a crash leaves
 * behind belong to the process that died, so the start-of-run recovery reads the PREVIOUS run's pid
 * from disk and keeps its lines too.
 *
 * **Why an anchor line.** The recovery reads from the last captured line's timestamp, which
 * re-delivers every line of that same millisecond. Lines up to and including the anchor's last
 * occurrence are dropped; if the anchor is gone, the buffer wrapped and everything is new.
 */
object LogcatLines {
    // "11-09 14:03:22.123  1234  1250 I Tag: message"
    private val header =
        Regex("""^(?<stamp>\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(?<pid>\d+)\s+\d+\s+[VDIWEFA]\s+(?<tag>.*?)\s*: """)

    /**
     * Lines dropped before the spool, as (tag, message prefix). **Empty by default**: Kevin asked
     * for everything. Measured 2026-11-06 on the A25, Samsung's `I View: setRequestedFrameRate`
     * (one per Compose frame) was 90% of the app's raw lines and half its compressed bytes; adding
     * `"View" to "setRequestedFrameRate"` here halves the upload if that ever matters.
     */
    val NOISE: List<Pair<String, String>> = emptyList()

    /** The pid column of a threadtime line, or null for anything else. */
    fun pidOf(line: String): String? = header.find(line)?.groups?.get("pid")?.value

    /** The `MM-dd HH:mm:ss.SSS` stamp of a threadtime line, or null. */
    fun stampOf(line: String): String? = header.find(line)?.groups?.get("stamp")?.value

    fun tagOf(line: String): String? = header.find(line)?.groups?.get("tag")?.value

    /** Lines of [raw] that carry a threadtime header from one of [pids], in order. */
    fun ours(raw: List<String>, pids: Set<String>): List<String> = raw.filter { pidOf(it) in pids }

    /** The lines after [anchor]'s last occurrence, or all of them when it is absent. */
    fun newSince(lines: List<String>, anchor: String?): List<String> {
        if (anchor == null) return lines
        val at = lines.lastIndexOf(anchor)
        return if (at >= 0) lines.drop(at + 1) else lines
    }

    /** True for a line [noise] says to drop. */
    fun isNoise(line: String, noise: List<Pair<String, String>> = NOISE): Boolean {
        val m = if (noise.isEmpty()) null else header.find(line)
        val tag = m?.groups?.get("tag")?.value
        val message = m?.let { line.substring(it.range.last + 1) }
        return tag != null && message != null && noise.any { (t, prefix) -> t == tag && message.startsWith(prefix) }
    }

    /** `MM-dd HH:mm:ss.SSS` in the phone's local time, the form `logcat -T` takes on every API level. */
    fun logcatTime(epochMs: Long): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
}
