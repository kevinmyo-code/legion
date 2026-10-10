package com.kevin.legion.ui.home

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.calendar.EventSuggestions
import com.kevin.legion.calendar.SuggestionMeta
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.data.local.activeByKindsInLocalWindow
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.ui.notes.InboxRowView
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.toAppointmentEvent
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.documentDateCompact
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * Every read and write HOME's calendar makes, behind one seam so [HomeCalendarViewModel] can be
 * tested with a fake (CLAUDE.md sec 8: "interfaces only at seams that need a fake"). The one
 * implementation, [ContextHomeCalendarSource], adds no query of its own: it calls the readers the
 * calendar drill-down already uses.
 *
 * A function that cannot read says so by throwing, or by naming the kind in `unreadable`; it never
 * returns an empty list for a failed read.
 */
interface HomeCalendarSource {
    /** ONE read of [month]: events, tasks and suggestions in a single query, plus the reminders.
     * The month grid AND every day's agenda inside it are derived from this, off the main thread
     * (`HomeCalendarDerive.kt`). A kind that could not be read is named in `unreadable`. */
    suspend fun readMonth(month: YearMonth, zone: ZoneId): MonthRead

    suspend fun openTodos(zone: ZoneId): List<TodoRowUi>

    /** True when the tick landed. False is a refusal (a recurring reminder, a row that vanished). */
    suspend fun setTodoDone(ref: TodoRef, done: Boolean): Boolean

    suspend fun lists(): List<ListRowUi>

    suspend fun ideas(from: LocalDate, to: LocalDate, zone: ZoneId): List<IdeaRowUi>

    /** The sentence the action produced, "queued" included, exactly as the day view shows it. */
    suspend fun addIdeaToPlans(id: Long): String

    suspend fun dropIdea(id: Long): String
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val TODO_LOOKBACK_DAYS = 30L
private const val TODO_LOOKAHEAD_DAYS = 365L

// One small method per read or write the sheets and the agenda make; splitting them would only hide the list.
@Suppress("TooManyFunctions") // see the line above
class ContextHomeCalendarSource(context: Context) : HomeCalendarSource {
    private val appContext = context.applicationContext

    private fun db() = CarDatabase.getDatabase(appContext)

    private fun startMs(day: LocalDate, zone: ZoneId) = day.atStartOfDay(zone).toInstant().toEpochMilli()

    // ----------------------------------------------------------------------------- month

    override suspend fun readMonth(month: YearMonth, zone: ZoneId): MonthRead {
        val from = startMs(month.atDay(1), zone)
        val to = startMs(month.plusMonths(1).atDay(1), zone) - 1
        val unreadable = mutableListOf<String>()
        // Every kind is named: EVENT, TASK and SUGGESTION, split apart again by kind in
        // HomeCalendarDerive.kt (a suggestion is never a plan).
        val rows = readOrNull("events", unreadable) {
            db().eventDao().activeByKindsInLocalWindow(
                listOf(EventKind.EVENT, EventKind.TASK, EventKind.SUGGESTION), from, to, zone,
            )
        }
        // One failed query loses all three kinds, and each is said in words.
        if (rows == null) unreadable += listOf("to-dos", "suggestions")
        val reminders = readOrNull("to-dos", unreadable) { NotesController.allItems(appContext) }
        return MonthRead(month, rows.orEmpty(), reminders.orEmpty(), unreadable.distinct())
    }

    // ----------------------------------------------------------------------------- panels

    override suspend fun openTodos(zone: ZoneId): List<TodoRowUi> {
        val today = startMs(LocalDate.now(zone), zone)
        // Reminders (unwindowed, like the day view) plus tasks over a year ahead and 30 days back -
        // the SAME window HOME's overdue chip looks back over.
        val tasks = db().eventDao().activeByKindInLocalWindow(
            EventKind.TASK, today - TODO_LOOKBACK_DAYS * DAY_MS, today + TODO_LOOKAHEAD_DAYS * DAY_MS, zone,
        )
        return inboxRows(tasks)
            .filter { !it.done && it.tickable }
            .map { row ->
                TodoRowUi(
                    key = "${row.source}-${row.id}",
                    title = row.text,
                    sub = if (row.overdue) "Overdue · ${row.dateLabel}" else row.dateLabel ?: "No date",
                    done = false,
                    ref = if (row.source == AgendaSource.LOCAL) TodoRef.Reminder(row.id) else TodoRef.Task(row.id),
                )
            }
    }

    override suspend fun setTodoDone(ref: TodoRef, done: Boolean): Boolean = when (ref) {
        is TodoRef.Reminder -> {
            val item = NotesController.itemById(appContext, ref.id)
            when {
                item == null -> false
                done -> NotesController.tick(appContext, item)
                else -> NotesController.untick(appContext, item)
            }
        }
        is TodoRef.Task -> {
            val task = db().eventDao().getById(ref.id)?.takeIf { !it.deleted && it.kind == EventKind.TASK }
            when {
                task == null -> false
                done -> NotesController.tickAppointment(appContext, task)
                else -> NotesController.untickAppointment(appContext, task)
            }
        }
    }

    override suspend fun lists(): List<ListRowUi> =
        ChecklistController.allChecklists(appContext).map { checklist ->
            val count = ChecklistController.itemsFor(appContext, checklist.id).size
            ListRowUi(checklist.id, checklist.name, if (count == 1) "1 item" else "$count items")
        }

    override suspend fun ideas(from: LocalDate, to: LocalDate, zone: ZoneId): List<IdeaRowUi> =
        EventSuggestions.inLocalWindow(
            appContext, startMs(from, zone), startMs(to.plusDays(1), zone) - 1, zone,
        ).map { s ->
            val meta = SuggestionMeta.parse(s.structuredMeta)
            val where = s.location ?: listOfNotNull(meta?.venue, meta?.city).joinToString(", ").ifEmpty { null }
            val whenText = s.startsAt?.let { if (s.allDay) documentDateCompact(it) else clockTime(it) }
            IdeaRowUi(s.id, s.title, listOfNotNull(whenText, where).joinToString(" · "))
        }

    override suspend fun addIdeaToPlans(id: Long): String =
        withSuggestion(id) { EventSuggestions.addToPlans(appContext, it).sentence }

    override suspend fun dropIdea(id: Long): String =
        withSuggestion(id) { EventSuggestions.notInterested(appContext, it).sentence }

    private suspend fun withSuggestion(id: Long, act: suspend (Event) -> String): String {
        val row = db().eventDao().getById(id)?.takeIf { !it.deleted && it.kind == EventKind.SUGGESTION }
            ?: return "Nothing was changed: this suggestion is no longer on the calendar."
        return act(row)
    }

    // ----------------------------------------------------------------------------- helpers

    /** The reminders plus [tasks] as the day view's own merged rows (`buildInboxRows`), so HOME and the
     * calendar can never disagree about what is a to-do. */
    private suspend fun inboxRows(tasks: List<Event>): List<InboxRowView> =
        buildInboxRows(
            NotesController.allItems(appContext),
            System.currentTimeMillis(),
            tasks.map(Event::toAppointmentEvent),
        )

    // A failed read is recorded in words, never returned as an empty result (ADR 0050: HOME must
    // never crash, and "unreadable" and "empty" must not look alike).
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // the failure is recorded in [unreadable]
    private suspend fun <T> readOrNull(name: String, unreadable: MutableList<String>, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unreadable += name
            null
        }
}
