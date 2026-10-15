package com.kevin.midnightai.diag

/**
 * Turns the raw output of `logcat -d -v threadtime` into the lines worth keeping, and works out
 * which of them are NEW since the previous snapshot. Pure, so it is unit-tested without a device.
 *
 * **Why not `--pid=`.** One code path on every API level (24-29 here) with no per-version
 * behaviour to verify on a unit with no ADB: the lines are filtered on the PID column instead.
 * An app can read only its own uid's log entries (Android 4.1+ removed READ_LOGS for third
 * parties), so a stock logcat already returns just this app; the PID filter is the guard for a
 * unit whose vendor ROM hands out more.
 *
 * **Why a SET of pids.** After a crash the new process has a new pid, and the dying process's
 * last lines are exactly what an audit wants. [ownPids] therefore holds every pid this install
 * has run under, persisted by the caller.
 *
 * **Why an anchor line, not a timestamp.** The head unit's clock has been seen resetting itself
 * (`time/TrustedClock` exists for that), so `logcat -T <time>` could lose or repeat lines. The
 * anchor is the last full line of the previous snapshot: new output is everything after its
 * LAST occurrence. If the anchor is gone the ring buffer wrapped and everything is new.
 */
object LogcatSnapshot {
    // "10-15 08:00:00.123  1234  1250 I Tag: message"
    private val header = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+(\d+)\s+\d+\s+[VDIWEFA]\s""")

    /** Lines of [raw] that carry a threadtime header from one of [ownPids], in order. */
    fun ours(raw: List<String>, ownPids: Set<String>): List<String> =
        raw.filter { line ->
            val pid = header.find(line)?.groupValues?.get(1)
            pid != null && pid in ownPids
        }

    /** The lines of [ours] after [anchor]'s last occurrence, or all of them when it is absent. */
    fun newSince(ours: List<String>, anchor: String?): List<String> {
        if (anchor == null) return ours
        val at = ours.lastIndexOf(anchor)
        return if (at >= 0) ours.drop(at + 1) else ours
    }
}
