package com.kevin.legion.ui.home

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * HOME's calendar (one-home ticket 11, Kevin picked prototype A on 2026-10-09). Pure state and the
 * pure functions over it, so `HomeCalendarViewModelTest` needs no Context, no Room and no Compose.
 *
 * **Three kinds, three words, never merged.** An [AgendaKind.SUGGESTION] is something the user
 * could do, not a plan (`EventReadsAreKindFilteredTest`, the 2026-10-09 suggestions ruling): it has
 * its own marker, its own label in words, sorts after every plan, and is never added into
 * [HomeCalendarUiState.openTodoCount] or any plan count.
 */
enum class AgendaKind { EVENT, TODO, SUGGESTION }

/** How many of each kind fall on one day. Counts, not flags, so a test can see a suggestion was not
 * folded into the others; the grid draws at most one mark per kind. */
data class DayMarkers(val events: Int = 0, val todos: Int = 0, val suggestions: Int = 0) {
    val isEmpty: Boolean get() = events == 0 && todos == 0 && suggestions == 0
}

/** One month's markers. [unreadable] names the kinds whose read FAILED ("to-dos"), so a grid with
 * missing marks is never mistaken for a month where nothing is due (CLAUDE.md sec 1: unreadable
 * and empty are different sentences). */
data class MonthMarkers(val byDay: Map<LocalDate, DayMarkers>, val unreadable: List<String> = emptyList())

/** One grid cell. A day outside the displayed month is drawn dim, carries no markers and is not
 * selectable (the prototype's rule). */
data class CalendarCellUi(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val markers: DayMarkers,
)

/**
 * One agenda row. [reminderId] is set only for a reminder (`ListItem`) to-do, the one kind with an
 * existing detail dialog; every other row opens that day's day view instead (the only detail the
 * app has for an event, a task or a suggestion).
 */
data class AgendaRowUi(
    val key: String,
    val kind: AgendaKind,
    val whenLabel: String,
    val title: String,
    val typeLabel: String,
    val reminderId: Long? = null,
    val done: Boolean = false,
)

/** What one day's read returned. [unreadable] is as in [MonthMarkers]. */
data class DayRead(val rows: List<AgendaRowUi>, val unreadable: List<String> = emptyList())

/** Which table a to-do lives in, because they tick through different controller calls. */
sealed interface TodoRef {
    data class Reminder(val id: Long) : TodoRef

    data class Task(val id: Long) : TodoRef
}

data class TodoRowUi(val key: String, val title: String, val sub: String, val done: Boolean, val ref: TodoRef)

data class ListRowUi(val id: Long, val title: String, val sub: String)

/** [outcome] is the sentence the last action on this idea produced, "queued" included. */
data class IdeaRowUi(val id: Long, val title: String, val sub: String, val outcome: String? = null)

enum class PanelSheet { TODOS, LISTS, IDEAS }

data class HomeCalendarUiState(
    val today: LocalDate,
    val month: YearMonth,
    val selectedDay: LocalDate,
    val cells: List<CalendarCellUi> = emptyList(),
    val dayRows: List<AgendaRowUi> = emptyList(),
    /** Set when the grid or the day could not be read in full; shown in words under the title. */
    val readNote: String? = null,
    val openTodoCount: Int = 0,
    val todos: List<TodoRowUi> = emptyList(),
    val todosNote: String? = null,
    val lists: List<ListRowUi> = emptyList(),
    val listsNote: String? = null,
    val ideas: List<IdeaRowUi> = emptyList(),
    val ideasNote: String? = null,
    val sheet: PanelSheet? = null,
    val loading: Boolean = true,
) {
    /** Plans on the selected day: events and to-dos. A suggestion is not one. */
    val planCount: Int get() = dayRows.count { it.kind != AgendaKind.SUGGESTION }
    val suggestionCount: Int get() = dayRows.count { it.kind == AgendaKind.SUGGESTION }
}

/** Every tap HOME's calendar can make. One bag so the stateful [HomeScreen] and the stateless
 * [HomeCalendarArea] (Roborazzi-renderable with fakes) share one shape. */
data class HomeCalendarCallbacks(
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onSelectDay: (LocalDate) -> Unit = {},
    /** A row was tapped: a reminder opens its edit dialog, anything else opens its day view. */
    val onOpenRow: (day: LocalDate, row: AgendaRowUi) -> Unit = { _, _ -> },
    /** "+N more": that day's existing day view. */
    val onOpenDay: (LocalDate) -> Unit = {},
    val onOpenSheet: (PanelSheet) -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onToggleTodo: (TodoRowUi, Boolean) -> Unit = { _, _ -> },
    val onOpenList: (ListRowUi) -> Unit = {},
    val onAddIdea: (IdeaRowUi) -> Unit = {},
    val onDropIdea: (IdeaRowUi) -> Unit = {},
)

const val GRID_WEEKS = 6
const val DAYS_IN_WEEK = 7

/**
 * The 6 x 7 = 42 cells of [month], starting on [firstDayOfWeek] (the locale's, which is what
 * `buildMonthCells` and the calendar drill-down's weekday header already use - the app has no
 * week-start setting). Six weeks always, so the grid never changes height between months.
 */
fun buildHomeCells(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    markers: Map<LocalDate, DayMarkers>,
    firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
): List<CalendarCellUi> {
    val first = month.atDay(1)
    val gridStart = first.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    return (0 until GRID_WEEKS * DAYS_IN_WEEK).map { i ->
        val date = gridStart.plusDays(i.toLong())
        val inMonth = YearMonth.from(date) == month
        CalendarCellUi(
            date = date,
            inMonth = inMonth,
            isToday = date == today,
            isSelected = inMonth && date == selected,
            markers = if (inMonth) markers[date] ?: DayMarkers() else DayMarkers(),
        )
    }
}

/** Plans first, suggestions last, each group by time (an all-day row [Long.MIN_VALUE] first). A short
 * panel therefore drops suggestions behind "+N more" before it ever drops a plan. */
fun orderAgenda(rows: List<Pair<AgendaRowUi, Long?>>): List<AgendaRowUi> =
    rows.sortedWith(
        compareBy<Pair<AgendaRowUi, Long?>> { if (it.first.kind == AgendaKind.SUGGESTION) 1 else 0 }
            .thenBy { it.second ?: Long.MIN_VALUE },
    ).map { it.first }

/** What the agenda panel shows: [shown] rows, then "+[more] more" when [more] is above zero. */
data class AgendaFit(val shown: Int, val more: Int)

/**
 * How many of [total] rows fit. [rowCapacity] is how many whole rows the panel holds when nothing
 * overflows; [rowCapacityWithMore] is how many it holds once the "+N more" line takes its own space
 * (the line is shorter than a row, so this is usually the same number or one fewer). When everything
 * fits the line is not needed and is not drawn, so the line itself never pushes a row off the bottom.
 */
fun fitAgenda(total: Int, rowCapacity: Int, rowCapacityWithMore: Int = rowCapacity - 1): AgendaFit {
    if (total <= rowCapacity) return AgendaFit(total, 0)
    val shown = rowCapacityWithMore.coerceIn(0, total)
    return AgendaFit(shown, total - shown)
}

/** "This weekend" for Ideas: Friday through Sunday, starting today when today is already inside it. */
fun weekendWindow(today: LocalDate): Pair<LocalDate, LocalDate> {
    val start = when (today.dayOfWeek) {
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> today
        else -> today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
    }
    return start to start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
}
