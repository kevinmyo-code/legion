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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.notes.CalendarNotLinkedRow
import com.kevin.legion.ui.notes.MonthCell
import com.kevin.legion.ui.notes.buildMonthCells
import com.kevin.legion.ui.notes.eventDotCount
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
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

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Prev/next month, same pattern as `ui/ledger/BudgetSection.kt`'s `< MONTH >` navigator -
        // this calendar has no natural min/max bound (there is no coverage concept the way ledger
        // has statements), so both arrows stay enabled always rather than growing an artificial one.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevMonth) {
                Text("<", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
            Text(monthGridLabel(month), style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleCollapsed) {
                    Text(if (collapsed) "MONTH" else "HIDE", style = LegionType.stamp, color = sem.faint)
                }
                TextButton(onClick = onNextMonth) {
                    Text(">", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (!collapsed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
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
            // Cell height 34dp (ticket 14) - six week-rows plus the two header rows above stay
            // well under ~260dp total, giving height back to the inbox list below.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
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

/**
 * One 34dp cell: the day number, and up to three [eventDotCount] dots beneath it (density only -
 * never source or importance, per that function's own doc comment). Today fills with
 * [MaterialTheme.colorScheme.primary]/`onPrimary`, the SAME inverted-amber treatment
 * `ui/common/DeckCharts.kt`'s `DeckRangeSelector` already uses for its own selected stencil chip -
 * a selected (but not today's) day instead gets a 1dp primary border, so the two states can never
 * be confused for each other. A blank slot ([MonthCell.dayOfMonth] null) renders nothing and is
 * not clickable - it belongs to the neighbouring month, not this one.
 *
 * Moved out of `ui/NotesScreen.kt` alongside [MonthCalendar] - see that function's doc comment.
 */
@Composable
fun MonthCellView(cell: MonthCell, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(34.dp)
            .let { if (isToday) it.background(MaterialTheme.colorScheme.primary) else it }
            .let { if (isSelected) it.border(1.dp, MaterialTheme.colorScheme.primary) else it }
            .let { if (cell.dayStart != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (cell.dayOfMonth != null) {
            val dotColor = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    cell.dayOfMonth.toString(),
                    style = LegionType.stamp,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                val dots = eventDotCount(cell.eventCount)
                if (dots > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(dots) {
                            Box(Modifier.size(3.dp).background(dotColor, CircleShape))
                        }
                    }
                }
            }
        }
    }
}

private val MONTH_GRID_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun monthGridLabel(month: YearMonth): String = month.format(MONTH_GRID_LABEL).uppercase()

/** The grid's weekday header letters, locale-ordered starting at [WeekFields.firstDayOfWeek] -
 * [buildMonthCells] lays its columns out in the SAME order, so the two must never diverge. */
private fun weekdayLetters(): List<String> {
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0 until 7).map { i -> firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.ENGLISH) }
}
