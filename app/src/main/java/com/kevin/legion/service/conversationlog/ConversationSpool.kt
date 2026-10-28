package com.kevin.legion.service.conversationlog

import java.io.File
import java.util.UUID

/**
 * One sealed, ready-to-upload batch (conversation-records ticket 03). [startMs]/[endMs] are the
 * phone's wall clock when the first and the last line of the batch were written; the engine keeps
 * them as untrusted labels (`window_start_ms`/`window_end_ms`). [id] is the idempotency key the
 * engine dedupes a retry on (`batch_id`).
 */
data class SpooledBatch(val file: File, val id: String, val startMs: Long, val endMs: Long) {
    /** Lines in the batch. Every line the spool writes ends in a newline. */
    fun lineCount(): Int = file.readBytes().count { it == '\n'.code.toByte() }
}

/**
 * The bounded on-disk conversation spool (conversation-records ticket 03, 2026-10-28, Kevin: "all
 * logs on engine. both headunit and phone"). The phone's sibling of the head unit's
 * `DriveLogStore`, same layout and same rules, so one reading of either holds for both. Plain
 * `java.io`, no Android types: the unit test needs no Robolectric.
 *
 * Layout under [dir]:
 * - `current.log` - lines appended since the last seal.
 * - `current.start` / `current.end` - wall-clock ms of the first and the latest line in it.
 * - `pending/<startMs>_<endMs>_<uuid>.log` - sealed batches, oldest first by start.
 *
 * **Bounded.** `current.log` plus every pending batch is held under [maxTotalBytes] (about 4 MB):
 * the oldest PENDING batch is dropped first, and only when none is left is the head of
 * `current.log` trimmed, at a line boundary. A batch is sealed at [sealAtBytes], far under the
 * engine's 5 MB cap (`devicelogs.MAX_UPLOAD_BYTES`), so no batch can be refused for size and wedge
 * the queue behind it (a batch is deleted only on a 2xx).
 *
 * **Lines arrive already redacted.** `LiveAudit` replaces third-party content before a line exists
 * (CLAUDE.md section 7); the spool adds nothing and changes nothing but the newline.
 *
 * Every public method is `@Synchronized`: appends come from the spool's writer thread, seals and
 * reads from the uploader.
 */
class ConversationSpool(
    private val dir: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val sealAtBytes: Long = DEFAULT_SEAL_AT_BYTES,
) {
    private val current = File(dir, "current.log")
    private val currentStart = File(dir, "current.start")
    private val currentEnd = File(dir, "current.end")
    private val pendingDir = File(dir, "pending")

    init {
        pendingDir.mkdirs()
    }

    /** Append one line written at [atMs] (wall clock). A newline inside [line] becomes a space. */
    @Synchronized
    fun append(line: String, atMs: Long) {
        pendingDir.mkdirs()
        if (!current.exists() || current.length() == 0L) currentStart.writeText(atMs.toString())
        current.appendText(line.replace('\r', ' ').replace('\n', ' ') + "\n")
        currentEnd.writeText(atMs.toString())
        if (current.length() >= sealAtBytes) seal()
        enforceCap()
    }

    /** Move `current.log` into `pending/`. Returns the new batch, or null when nothing was waiting. */
    @Synchronized
    fun seal(): SpooledBatch? {
        if (!current.exists() || current.length() == 0L) return null
        val startMs = readMs(currentStart) ?: current.lastModified()
        val endMs = (readMs(currentEnd) ?: current.lastModified()).coerceAtLeast(startMs)
        val id = UUID.randomUUID().toString()
        val target = File(pendingDir, "${startMs}_${endMs}_$id.log")
        val moved = current.renameTo(target)
        if (moved) {
            currentStart.delete()
            currentEnd.delete()
            enforceCap()
        }
        return if (moved) SpooledBatch(target, id, startMs, endMs) else null
    }

    /** Sealed batches, oldest first. */
    @Synchronized
    fun pending(): List<SpooledBatch> =
        (pendingDir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: emptyArray())
            .mapNotNull { parse(it) }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))

    @Synchronized
    fun delete(batch: SpooledBatch) {
        batch.file.delete()
    }

    @Synchronized
    fun totalBytes(): Long = (if (current.exists()) current.length() else 0L) +
        (pendingDir.listFiles()?.sumOf { it.length() } ?: 0L)

    /** True when anything is waiting to go: an unsealed `current.log` or any pending batch. */
    @Synchronized
    fun hasContent(): Boolean = (current.exists() && current.length() > 0L) || pending().isNotEmpty()

    private fun enforceCap() {
        var oldest = pending().firstOrNull()
        while (totalBytes() > maxTotalBytes && oldest != null) {
            oldest.file.delete()
            oldest = pending().firstOrNull()
        }
        if (totalBytes() <= maxTotalBytes) return
        // Nothing sealed left to drop: shave the head of the live file down to 75% of the cap,
        // starting at a line boundary so the file never opens on half a line.
        val bytes = current.readBytes()
        val keep = maxTotalBytes * TRIM_TO_PERCENT / PERCENT
        val keepFrom = (bytes.size - keep).coerceIn(0L, bytes.size.toLong()).toInt()
        val tail = bytes.copyOfRange(keepFrom, bytes.size)
        val firstNewline = if (keepFrom == 0) -1 else tail.indexOf('\n'.code.toByte())
        current.writeBytes(if (firstNewline >= 0) tail.copyOfRange(firstNewline + 1, tail.size) else tail)
    }

    private fun readMs(file: File): Long? = file.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()

    /** `<startMs>_<endMs>_<uuid>.log`, or null for anything else in the folder. */
    private fun parse(file: File): SpooledBatch? {
        val parts = file.nameWithoutExtension.split('_', limit = NAME_PARTS)
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        val id = parts.getOrNull(2)
        return if (start != null && end != null && id != null) SpooledBatch(file, id, start, end) else null
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES = 4L * 1024 * 1024
        const val DEFAULT_SEAL_AT_BYTES = 512L * 1024
        private const val NAME_PARTS = 3
        private const val TRIM_TO_PERCENT = 75
        private const val PERCENT = 100

        /** The four tags `tools/voice_audit.py` reads; nothing else enters the spool. */
        val TAGS: Set<String> = setOf("LIVE_AUDIT", "LIVE_LATENCY", "LIVE_FOLLOWUP", "LIVE_TOOL_CANCEL")

        /**
         * One `logcat -v threadtime`-shaped line, `MM-dd HH:mm:ss.SSS pid tid I TAG: message`, so
         * `tools/voice_audit.py` and the engine's `device_logs --render` parse the spool as they
         * parse a capture. [stamp] is already formatted (`MM-dd HH:mm:ss.SSS`).
         */
        fun threadtime(stamp: String, pid: Int, tid: Int, tag: String, message: String): String =
            "$stamp $pid $tid I $tag: ${message.replace('\r', ' ').replace('\n', ' ')}"
    }
}
