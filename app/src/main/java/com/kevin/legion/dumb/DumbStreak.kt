package com.kevin.legion.dumb

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The streak and today's running total, both from the persisted on/off intervals. */
data class DumbStreak(val days: Int, val todayMs: Long)

/**
 * Dumb mode's streak (Kevin, 2026-10-22, ADR 0062). **It counts time AWAY from the phone**, which is
 * why CLAUDE.md section 7 allows it where it bans streaks that pull him back into an app.
 *
 * A day qualifies when dumb mode was on for at least [DAY_QUALIFY_MS] in total on that LOCAL day (so
 * three separate 20-minute runs count). A run that crosses midnight is split at the local midnight and
 * each part counts toward its own day. The streak is the number of consecutive qualifying days ending
 * today, or ending yesterday while today has not reached the hour yet: a day he is still on his way
 * to qualifying does not read as a broken streak. A missed day resets it to zero.
 */
object DumbStreaks {
    const val DAY_QUALIFY_MS = 60L * 60 * 1000

    fun compute(history: List<DumbInterval>, current: DumbModeState, nowMs: Long, zone: ZoneId): DumbStreak {
        val running = DumbInterval(current.startedAtMs, maxOf(nowMs, current.startedAtMs))
        val runs = if (current.on) history + running else history
        val perDay = HashMap<LocalDate, Long>()
        runs.forEach { addByDay(it, zone, perDay) }

        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun qualifies(day: LocalDate) = (perDay[day] ?: 0L) >= DAY_QUALIFY_MS

        var day = if (qualifies(today)) today else today.minusDays(1)
        var count = 0
        while (qualifies(day)) {
            count++
            day = day.minusDays(1)
        }
        return DumbStreak(days = count, todayMs = perDay[today] ?: 0L)
    }

    /** Adds [run]'s milliseconds to each local day it touches, splitting at local midnight. */
    internal fun addByDay(run: DumbInterval, zone: ZoneId, into: MutableMap<LocalDate, Long>) {
        var cursor = Instant.ofEpochMilli(run.startMs).atZone(zone)
        val end = Instant.ofEpochMilli(run.endMs).atZone(zone)
        while (cursor.toInstant().isBefore(end.toInstant())) {
            val midnight = cursor.toLocalDate().plusDays(1).atStartOfDay(zone)
            val sliceEnd = if (midnight.toInstant().isBefore(end.toInstant())) midnight else end
            val ms = sliceEnd.toInstant().toEpochMilli() - cursor.toInstant().toEpochMilli()
            into.merge(cursor.toLocalDate(), ms, Long::plus)
            cursor = sliceEnd
        }
    }
}
