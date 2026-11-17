package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.activeByKindInLocalWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One calendar window as the phone holds it (2026-11-16): the rows `read_calendar` lists and the
 * `due` block it puts ahead of them. The ONE read both `day read_calendar`
 * ([com.kevin.legion.service.LiveToolbox]) and the voice agenda block
 * ([com.kevin.legion.service.cascade.AgendaBlock], ADR 0093) use, so the block is what the tool
 * would return for today, as of [readAtMs]. Phone data only: no network.
 */
data class CalendarWindowRead(
    /** Calendar events and tasks in the window, by start time. */
    val events: List<Event>,
    /** Everything due in the window, plus what is overdue ([DueReader]). */
    val due: DueSummary,
    /** When it was read; also the clock "overdue" was judged against. */
    val readAtMs: Long,
) {
    companion object {
        /**
         * [startMs]..[endMs] (local days in [zone]). Both kinds: reading only events is how seven
         * assignments once went unseen (2026-09-11, see `read_calendar`). A failed calendar read
         * throws, as it always has for the tool; a failed due source is named in [DueSummary.unreadable].
         */
        suspend fun read(
            context: Context,
            startMs: Long,
            endMs: Long,
            zone: ZoneId,
            nowMs: Long = System.currentTimeMillis(),
        ): CalendarWindowRead {
            val dao = CarDatabase.getDatabase(context).eventDao()
            val events = (
                dao.activeByKindInLocalWindow(EventKind.EVENT, startMs, endMs, zone) +
                    dao.activeByKindInLocalWindow(EventKind.TASK, startMs, endMs, zone)
                ).sortedBy { it.startsAt ?: Long.MAX_VALUE }
            val due = DueReader.read(
                context,
                Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate(),
                zone,
                nowMs,
            )
            return CalendarWindowRead(events, due, nowMs)
        }

        /** Today (local midnight to the last millisecond of today), as `read_calendar` from today to today. */
        suspend fun today(
            context: Context,
            zone: ZoneId,
            nowMs: Long = System.currentTimeMillis(),
        ): CalendarWindowRead {
            val day: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return read(context, start, end, zone, nowMs)
        }
    }
}
