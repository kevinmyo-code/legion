package com.kevin.midnightai.diag

import java.io.File
import java.util.UUID

/** One sealed, ready-to-upload batch. [startMs]/[endMs] are the DEVICE clock when the first and
 * last line were appended (the head unit's clock has been seen resetting, so the engine treats
 * them as untrusted labels). [id] is the idempotency key the engine dedupes a retry on. */
data class PendingBatch(val file: File, val id: String, val startMs: Long, val endMs: Long)

/**
 * The bounded on-disk log behind the drive-log upload (2026-10-15). Plain `java.io`, no Android
 * types, so the unit test needs no Robolectric.
 *
 * Layout under [dir]:
 * - `current.log` - lines appended since the last seal.
 * - `current.start` - device-clock ms of the first append into `current.log`.
 * - `pending/<startMs>_<endMs>_<uuid>.log` - sealed batches, oldest first by name.
 * - `*.state` - small text files the caller keeps (the logcat anchor, own pids).
 *
 * **Bounded.** Total size of `current.log` plus every pending batch is held under
 * [maxTotalBytes] (~2 MB): the oldest PENDING batch is dropped first, and only when none is left
 * is the head of `current.log` trimmed. A single batch is sealed at [sealAtBytes], well under the
 * engine's 5 MB cap, so one upload can never be refused for size and wedge the queue behind it
 * (a batch is deleted only on a 2xx, so a permanently refused one would otherwise block forever).
 *
 * Every public method is `@Synchronized`: appends come from the logging thread, seals and reads
 * from the uploader, and a half-renamed file is the failure this prevents.
 */
class DriveLogStore(
    private val dir: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val sealAtBytes: Long = DEFAULT_SEAL_AT_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val current = File(dir, "current.log")
    private val currentStart = File(dir, "current.start")
    private val pendingDir = File(dir, "pending")

    init {
        pendingDir.mkdirs()
    }

    @Synchronized
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        if (!current.exists() || current.length() == 0L) currentStart.writeText(now().toString())
        current.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
        if (current.length() >= sealAtBytes) seal()
        enforceCap()
    }

    /** Move `current.log` into `pending/`. Returns the new batch, or null when nothing was
     * waiting. */
    @Synchronized
    fun seal(): PendingBatch? {
        if (!current.exists() || current.length() == 0L) return null
        val startMs = currentStart.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: now()
        val endMs = now()
        val id = UUID.randomUUID().toString()
        val target = File(pendingDir, "${startMs}_${endMs}_$id.log")
        if (!current.renameTo(target)) return null
        currentStart.delete()
        enforceCap()
        return PendingBatch(target, id, startMs, endMs)
    }

    /** Sealed batches, oldest first. */
    @Synchronized
    fun pending(): List<PendingBatch> =
        (pendingDir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: emptyArray())
            .mapNotNull { parse(it) }
            .sortedBy { it.startMs }

    @Synchronized
    fun delete(batch: PendingBatch) {
        batch.file.delete()
    }

    @Synchronized
    fun totalBytes(): Long = (if (current.exists()) current.length() else 0L) +
        (pendingDir.listFiles()?.sumOf { it.length() } ?: 0L)

    /** True when anything is waiting to go: an unsealed `current.log` or any pending batch. */
    @Synchronized
    fun hasContent(): Boolean =
        (current.exists() && current.length() > 0L) || pending().isNotEmpty()

    @Synchronized
    fun readState(name: String): String? =
        File(dir, "$name.state").takeIf { it.exists() }?.readText()

    @Synchronized
    fun writeState(name: String, text: String) {
        File(dir, "$name.state").writeText(text)
    }

    private fun enforceCap() {
        while (totalBytes() > maxTotalBytes) {
            val oldest = pending().firstOrNull()
            if (oldest != null) {
                oldest.file.delete()
                continue
            }
            // Nothing sealed left to drop: shave the head of the live file down to 75% of the cap.
            val keepFrom = (current.length() - maxTotalBytes * 3 / 4).coerceAtLeast(0L)
            val tail = current.readBytes().let { it.copyOfRange(keepFrom.toInt().coerceAtMost(it.size), it.size) }
            // Start at a line boundary so the file never opens on half a line.
            val firstNewline = tail.indexOf('\n'.code.toByte())
            current.writeBytes(if (firstNewline >= 0) tail.copyOfRange(firstNewline + 1, tail.size) else tail)
            break
        }
    }

    private fun parse(file: File): PendingBatch? {
        val parts = file.nameWithoutExtension.split('_', limit = 3)
        if (parts.size != 3) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = parts[1].toLongOrNull() ?: return null
        return PendingBatch(file, parts[2], start, end)
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024
        const val DEFAULT_SEAL_AT_BYTES = 512L * 1024
    }
}
