package com.kevin.legion.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.notes.CalendarNotLinkedRow
import com.kevin.legion.ui.notes.MonthCell
import com.kevin.legion.ui.notes.buildMonthCells
import com.kevin.legion.ui.notes.eventDotCount
import com.kevin.legion.ui.notes.openTodoMarkCount
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.R
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Quant-viz ticket 14's Notes-tab month calendar, replacing the WEEK AHEAD strip - Kevin,
 * 2026-08-14: "i cant scroll down anymore. the visual obscures the scroll interface. lets make it
 * a calendar with events on it." [cells] is [buildMonthCells]'s own output, already padded to
 * whole weeks; this composable only lays them out and colours today/[selectedDayStart].
 *
 * **Calendar-not-linked keeps drawing the grid from LOCAL items** (unlike the strip it replaces,
 * which suppressed itself entirely) - [CalendarNotLinkedRow] renders directly beneath the grid so
 * the picture is never silently presented as complete when Google events are unread.
 *
 * [collapsed] hides everything below the month header row - Kevin's direct complaint answered:
 * the graphic can always be got out of the way without leaving the tab or losing the month/day
 * state underneath it.
 *
 * **Moved out of `ui/NotesScreen.kt` (originally `private`, so only that file could reach it - see
 * `ui/agenda/DayAgenda.kt`'s class doc for the same file-scoping gap on the query side) so any
 * screen built on top of the shared [com.kevin.legion.ui.agenda.buildMonthAgenda] builder can also
 * reuse this rendering, rather than a fourth restatement.** Parameter list and rendering are
 * unchanged by the move.
 */
@Composable
fun MonthCalendar(
    calendarLinked: Boolean,
    month: YearMonth,
    cells: List<MonthCell>,
    collapsed: Boolean,
    selectedDayStart: Long?,
    onToggleCollapsed: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDay: (Long) -> Unit,
    onGrantCalendar: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val zone = ZoneId.systemDefault()
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    // Soft restyle (ADR 0051, calendar drill-down): the whole month sits on ONE rounded card, the
    // header is sentence case with icon arrows, and there are no ruled lines. Only CalendarScreen
    // calls this, and CalendarScreen wraps itself in SoftTheme, so the soft tokens are read directly
    // rather than branching on LocalSoftActive.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(SoftColors.card, MaterialTheme.shapes.large)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        // This calendar has no natural min/max bound (there is no coverage concept the way ledger
        // has statements), so both arrows stay enabled always rather than growing an artificial one.
        MonthHeader(month, collapsed, onPrevMonth, onNextMonth, onToggleCollapsed)

        if (!collapsed) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                weekdayLetters().forEach { letter ->
                    Text(
                        letter,
                        style = LegionType.stamp,
                        color = sem.faint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        week.forEach { cell ->
                            MonthCellView(
                                cell = cell,
                                isToday = cell.dayStart != null && cell.dayStart == todayStart,
                                isSelected = cell.dayStart != null && cell.dayStart == selectedDayStart,
                                onClick = { cell.dayStart?.let(onSelectDay) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (!calendarLinked) {
                CalendarNotLinkedRow(
                    "Calendar not linked - grant access to see Google events on the calendar too.",
                    onGrant = onGrantCalendar,
                )
            }
        }
    }
}

/** Prev / title / hide-and-next row. Split out of [MonthCalendar] only to keep that function short. */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun MonthHeader(
    month: YearMonth,
    collapsed: Boolean,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToggleCollapsed: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevMonth) {
            MsIcon(R.drawable.ms_arrow_back, contentDescription = "Previous month", tint = SoftColors.text)
        }
        Text(monthGridLabel(month), style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleCollapsed) {
                Text(if (collapsed) "Show" else "Hide", style = LegionType.stamp, color = sem.faint)
            }
            IconButton(onClick = onNextMonth) {
                // Same glyph as Previous, turned half a revolution: no forward arrow is vendored.
                MsIcon(
                    R.drawable.ms_arrow_back,
                    contentDescription = "Next month",
                    tint = SoftColors.text,
                    modifier = Modifier.rotate(HALF_TURN_DEGREES),
                )
            }
        }
    }
}

private const val HALF_TURN_DEGREES = 180f

/**
 * One 40dp rounded cell: the day number, up to three [eventDotCount] ROUND dots for
 * [MonthCell.eventCount] (density only - never source or importance, per that function's own doc
 * comment), and - Kevin, 2026-09-05, "calendar has dots for events but not for todos... add
 * indicators" - up to three [openTodoMarkCount] SQUARE marks for [MonthCell.openTodoCount] beneath
 * them. **Square, not a second dot of another colour** - CLAUDE.md's "never colour-only" rule (the
 * same one an UNRECONCILED ledger row follows): a shape difference reads in grayscale and to anyone
 * who cannot distinguish the two colours. A day whose open todos are all ticked draws no square at
 * all - that absence IS the "all done" state.
 *
 * **Restyled for soft Material (ADR 0051): today and selected use the CALENDAR [AreaAccent] pair, no
 * ruled lines.** Today fills with the pair's `onContainer` (light blue) and draws its text and marks
 * in the `container` (dark blue); a selected (but not today's) day instead gets a `container` fill
 * with an `onContainer` outline, so the two states still cannot be confused. A day that is both
 * keeps the filled treatment plus the outline. A blank slot ([MonthCell.dayOfMonth] null) renders
 * nothing and is not clickable - it belongs to the neighbouring month, not this one.
 */
@Composable
fun MonthCellView(cell: MonthCell, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = AreaAccent.CALENDAR
    val shape = MaterialTheme.shapes.small
    Box(
        modifier
            .height(40.dp)
            .let {
                when {
                    isToday -> it.background(accent.onContainer, shape)
                    isSelected -> it.background(accent.container, shape)
                    else -> it
                }
            }
            .let { if (isSelected) it.border(1.5.dp, accent.onContainer, shape) else it }
            .let { if (cell.dayStart != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (cell.dayOfMonth != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    cell.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isToday) accent.container else SoftColors.text,
                )
                CellMarks(cell, isToday)
            }
        }
    }
}

/** The event dots and open-todo squares under a day number (see [MonthCellView]'s doc for why one is
 * round and the other square). */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun CellMarks(cell: MonthCell, isToday: Boolean) {
    val accent = AreaAccent.CALENDAR
    val dotColor = if (isToday) accent.container else accent.onContainer
    // The todo mark picks a DIFFERENT hue from the event dot when not on today's filled
    // background - shape alone (square vs circle) already carries the distinction per
    // [MonthCellView]'s doc comment, so the colour split is a legibility aid on top of that.
    val todoColor = if (isToday) accent.container else AreaAccent.LISTS.onContainer
    val dots = eventDotCount(cell.eventCount)
    if (dots > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(dots) {
                Box(Modifier.size(4.dp).background(dotColor, CircleShape))
            }
        }
    }
    val marks = openTodoMarkCount(cell.openTodoCount)
    if (marks > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 1.dp)) {
            repeat(marks) {
                // No [CircleShape] here - the default rectangular clip is the whole point (square vs
                // the event dot's circle above).
                Box(Modifier.size(4.dp).background(todoColor))
            }
        }
    }
}

// Sentence case ("October 2026"): the old `.uppercase()` was a mission-control stamp.
private val MONTH_GRID_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun monthGridLabel(month: YearMonth): String = month.format(MONTH_GRID_LABEL)

/** The grid's weekday header letters, locale-ordered starting at [WeekFields.firstDayOfWeek] -
 * [buildMonthCells] lays its columns out in the SAME order, so the two must never diverge. */
private fun weekdayLetters(): List<String> {
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0 until 7).map { i -> firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.ENGLISH) }
}
