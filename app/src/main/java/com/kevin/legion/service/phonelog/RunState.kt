package com.kevin.legion.service.phonelog

import java.io.File
import java.util.Properties

/**
 * What the next process needs to know about this one, on disk (`files/phonelog/run.properties`):
 * its pid and run id (so the next start can recover the lines this run left in logcat after the
 * reader stopped, a crash's last words above all), the last line the reader captured (the anchor
 * that recovery reads past), and the newest Android exit record already sent.
 *
 * Plain `java.io`, no Android types. Every write is the whole file; a torn write reads back as
 * "nothing known", which costs a recovery, never a crash.
 */
class RunState(private val file: File) {
    data class Snapshot(
        val pid: String?,
        val runId: String?,
        val lastLine: String?,
        val lastLineAtMs: Long?,
        val lastExitSeenMs: Long,
    )

    @Synchronized
    fun read(): Snapshot {
        val p = load()
        return Snapshot(
            pid = p.getProperty(PID),
            runId = p.getProperty(RUN_ID),
            lastLine = p.getProperty(LAST_LINE),
            lastLineAtMs = p.getProperty(LAST_LINE_AT)?.toLongOrNull(),
            lastExitSeenMs = p.getProperty(LAST_EXIT)?.toLongOrNull() ?: 0L,
        )
    }

    /** This process starts: its pid and run id replace the last run's, the anchor is cleared. */
    @Synchronized
    fun startRun(pid: Int, runId: String) = update {
        setProperty(PID, pid.toString())
        setProperty(RUN_ID, runId)
        remove(LAST_LINE)
        remove(LAST_LINE_AT)
    }

    @Synchronized
    fun captured(line: String, atMs: Long) = update {
        setProperty(LAST_LINE, line)
        setProperty(LAST_LINE_AT, atMs.toString())
    }

    @Synchronized
    fun exitSeen(timestampMs: Long) = update { setProperty(LAST_EXIT, timestampMs.toString()) }

    private fun load(): Properties = Properties().apply {
        runCatching { file.inputStream().use { load(it) } }
    }

    private fun update(block: Properties.() -> Unit) {
        val p = load()
        p.block()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val PID = "pid"
        const val RUN_ID = "run_id"
        const val LAST_LINE = "last_line"
        const val LAST_LINE_AT = "last_line_at_ms"
        const val LAST_EXIT = "last_exit_seen_ms"
    }
}
