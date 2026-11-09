package com.kevin.legion.service.conversationlog

import java.io.File
import java.util.TimeZone

/**
 * How many compressed bytes one log may send per local day (2026-11-09, the phone's own logcat).
 * Over the cap, [ConversationUploader] stops and the rest waits on the phone, where the spool's own
 * size cap drops the oldest batch first. So a runaway logger costs at most [capBytes] a day on the
 * engine and the newest lines are the ones kept.
 *
 * One small file, `<day> <bytes>`, so a process restart does not reset the day. Plain `java.io`, no
 * Android types: the unit test needs no Robolectric. [zone] is read per call so a phone that changes
 * timezone rolls over on its new midnight.
 */
class DailyBudget(
    private val file: File,
    private val capBytes: Long,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
) {
    /** True when [bytes] more may be sent today. The first batch of a day always goes, however big. */
    @Synchronized
    fun allows(bytes: Long, nowMs: Long): Boolean {
        val spentToday = spentOn(day(nowMs))
        return spentToday == 0L || spentToday + bytes <= capBytes
    }

    @Synchronized
    fun spent(bytes: Long, nowMs: Long) {
        val today = day(nowMs)
        val total = spentOn(today) + bytes
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("$today $total")
        }
    }

    /** Bytes sent on the day [nowMs] falls in. */
    @Synchronized
    fun spentToday(nowMs: Long): Long = spentOn(day(nowMs))

    private fun spentOn(day: Long): Long {
        val parts = runCatching { file.readText().trim().split(' ') }.getOrNull() ?: return 0L
        return if (parts.size == 2 && parts[0].toLongOrNull() == day) parts[1].toLongOrNull() ?: 0L else 0L
    }

    /** Days since the epoch on the phone's local calendar. */
    private fun day(nowMs: Long): Long = Math.floorDiv(nowMs + zone().getOffset(nowMs), DAY_MS)

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
