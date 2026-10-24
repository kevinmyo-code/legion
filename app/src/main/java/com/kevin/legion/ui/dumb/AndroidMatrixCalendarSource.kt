package com.kevin.legion.ui.dumb

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.notes.NotesController
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MatrixCalendarSource] over Room and the controllers the Soft calendar uses: the day queries are
 * `activeByKindInLocalWindow` (which knows an all-day row is UTC midnight), events are written through
 * [EventsAppointmentWriter] and [NotesController]'s appointment funnels, and a tick is
 * [NotesController.tick] for a reminder or [NotesController.tickAppointment] for a task.
 */
internal class AndroidMatrixCalendarSource(private val context: Context) : MatrixCalendarSource {
    private val db get() = CarDatabase.getDatabase(context)

    override suspend fun agenda(from: LocalDate, toExclusive: LocalDate, zone: ZoneId): List<MatrixAgendaRow> {
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val now = System.currentTimeMillis()
        val events = db.eventDao().activeByKindInLocalWindow(EventKind.EVENT, fromMs, toMs, zone)
            .map { it.toRow(MatrixAgendaKind.EVENT, now) }
        val tasks = db.eventDao().activeByKindInLocalWindow(EventKind.TASK, fromMs, toMs, zone)
            .map { it.toRow(MatrixAgendaKind.TASK, now) }
        val reminders = NotesController.allItems(context)
            .filter { item -> item.startsAt?.let { it in fromMs..toMs } == true }
            .map { item ->
                MatrixAgendaRow(
                    id = item.id,
                    kind = MatrixAgendaKind.REMINDER,
                    title = item.text,
                    startMs = item.startsAt,
                    endMs = item.endsAt,
                    allDay = item.allDay,
                    done = item.done,
                    tickable = item.repeatKind == null,
                    overdue = item.repeatKind == null && !item.done && (item.startsAt ?: now) < now,
                    repeats = item.repeatKind != null,
                )
            }
        return MatrixCalendarText.ordered(events + tasks + reminders, zone)
    }

    private fun Event.toRow(kind: MatrixAgendaKind, now: Long) = MatrixAgendaRow(
        id = id,
        kind = kind,
        title = title,
        startMs = startsAt,
        endMs = endsAt,
        allDay = allDay,
        done = done,
        tickable = kind == MatrixAgendaKind.TASK,
        overdue = kind == MatrixAgendaKind.TASK && !done && (startsAt ?: now) < now,
    )

    @Suppress("TooGenericExceptionCaught") // a write that threw is "not saved", said in words by the caller
    override suspend fun saveEvent(id: Long?, title: String, times: MatrixEventTimes): Boolean =
        try {
            if (id == null) {
                EventsAppointmentWriter.addEvent(
                    context = context,
                    title = title,
                    startsAtMs = times.startMs,
                    endsAtMs = times.endMs,
                    allDay = times.allDay,
                    source = DatesAspectSeeder.SOURCE_LEGION,
                    kind = EventKind.EVENT,
                )
                true
            } else {
                NotesController.updateAppointment(context, id, title, times.startMs, times.endMs, times.allDay)
            }
        } catch (e: Exception) {
            android.util.Log.w("MatrixCalendar", "saveEvent failed: ${e.message}")
            false
        }

    override suspend fun deleteEvent(id: Long): Boolean = NotesController.removeAppointment(context, id)

    override suspend fun setDone(row: MatrixAgendaRow, done: Boolean): Boolean = when (row.kind) {
        MatrixAgendaKind.REMINDER -> NotesController.itemById(context, row.id)?.let {
            if (done) NotesController.tick(context, it) else NotesController.untick(context, it)
        } ?: false
        MatrixAgendaKind.TASK -> db.eventDao().getById(row.id)?.let {
            if (done) NotesController.tickAppointment(context, it) else NotesController.untickAppointment(context, it)
        } ?: false
        MatrixAgendaKind.EVENT -> false
    }
}
