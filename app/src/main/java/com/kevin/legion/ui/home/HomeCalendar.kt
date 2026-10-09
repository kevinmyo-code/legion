@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
// FunctionNaming: @Composable convention is PascalCase, detekt's rule does not know it. MagicNumber: layout
// dp, the prototype's own figures and the font-scale arithmetic are named where they carry meaning (the
// constants above each use) and left bare where they are just a size. TooManyFunctions: one composable per
// element of the screen; splitting the file would only scatter them.

package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

// Sizes lifted from prototype A (home-protos/Main.dc.html), expressed against the font scale.
private val CELL_MAX = 40.dp
// 18dp: the 636dp content box (the A25 under its status line and talk bar) cannot hold a 6-week grid, three
// agenda rows, the buttons and the dock at anything taller. The digits are fixed-size (see [GRID_TEXT_DP]),
// so a cell this short still holds a digit and its marks at every font scale.
private val CELL_MIN = 18.dp

/** Grid digits, weekday letters and the legend are sized in dp, NOT sp: they are chrome around a fixed
 * 6 x 7 grid and growing with the font scale would push the agenda below three rows. The agenda, the
 * buttons, the month title and every sheet scale as usual. */
private const val GRID_TEXT_DP = 12
private const val LEGEND_TEXT_DP = 11

/** The agenda's day title: fixed for the same reason as the grid digits - it sits in the squeezed middle. */
private const val AGENDA_TITLE_DP = 15

/** A panel button's second line is the label colour at this opacity. */
private const val SUB_ALPHA = 0.8f
private val GRID_GAP = 2.dp
private val ROW_MIN = 40.dp

/** The "+N more" line: shorter than a row, full width to tap. */
private val MORE_LINE = 28.dp

/** The one-line "Couldn't read ..." note above a day's rows when a read failed but others worked. */
private val READ_NOTE_LINE = 18.dp
private val TOUCH = 44.dp
private const val MIN_AGENDA_ROWS = 3

/**
 * The area between HOME's top bar and its dock (one-home ticket 11, prototype A): the month header,
 * the 6-week grid, and the selected day's agenda. **It never scrolls.** The grid gives height back
 * first (down to [CELL_MIN]) so the agenda keeps [MIN_AGENDA_ROWS] rows, and the agenda then shows
 * as many rows as its height holds followed by "+N more".
 */
@Composable
fun HomeCalendarArea(
    state: HomeCalendarUiState,
    callbacks: HomeCalendarCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val rowH = agendaRowHeight(fontScale)
    val minCell = CELL_MIN
    // The card's own 8dp top and bottom padding, the title row (fixed-size, see [AGENDA_TITLE_DP]),
    // and 6dp above the rows.
    val agendaChrome = 16.dp + (AGENDA_TITLE_DP + 5).dp + 6.dp
    val agendaMin = agendaChrome + rowH * MIN_AGENDA_ROWS + MORE_LINE
    val legendHeight = (LEGEND_TEXT_DP * 1.4f + 4f).dp

    Layout(
        modifier = modifier,
        content = {
            MonthHeader(state, callbacks)
            WeekdayHeader()
            DayGrid(state, callbacks)
            AgendaPanel(state, callbacks, rowH)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val header = measurables[0].measure(loose)
        val weekdays = measurables[1].measure(loose)
        // Grid padding (6 + 8), plus the legend line and its 4dp gap under the last week.
        val gridPad = with(density) { (10.dp + legendHeight).roundToPx() }
        val gapPx = with(density) { GRID_GAP.roundToPx() }
        val minGrid = with(density) { minCell.roundToPx() } * GRID_WEEKS + gapPx * (GRID_WEEKS - 1) + gridPad
        val maxGrid = with(density) { CELL_MAX.roundToPx() } * GRID_WEEKS + gapPx * (GRID_WEEKS - 1) + gridPad
        val agendaMinPx = with(density) { agendaMin.roundToPx() }
        val left = (constraints.maxHeight - header.height - weekdays.height).coerceAtLeast(0)
        // Grid takes what the agenda's minimum leaves, but never more than the prototype's own size.
        val gridH = (left - agendaMinPx).coerceIn(minGrid, maxGrid).coerceAtMost(left)
        val agendaH = (left - gridH).coerceAtLeast(0)
        val grid = measurables[2].measure(Constraints.fixed(width, gridH))
        val agenda = measurables[3].measure(Constraints.fixed(width, agendaH))
        layout(width, constraints.maxHeight) {
            header.placeRelative(0, 0)
            weekdays.placeRelative(0, header.height)
            grid.placeRelative(0, header.height + weekdays.height)
            agenda.placeRelative(0, header.height + weekdays.height + gridH)
        }
    }
}

/** [dp] as a text size that ignores the font scale (see [GRID_TEXT_DP]). */
@Composable
private fun fixedSp(dp: Int): androidx.compose.ui.unit.TextUnit = with(LocalDensity.current) { dp.dp.toSp() }

/** One agenda row's height: 40dp (the prototype's 44 does not leave three rows in the 636dp box),
 * grown with the font scale so two text lines never clip. */
internal fun agendaRowHeight(fontScale: Float): Dp = max(ROW_MIN.value, (14f + 11f) * 1.3f * fontScale).dp

@Composable
private fun MonthHeader(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 0.dp, top = 0.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // No separate date line: the agenda's own title says "Today \u00b7 Fri, Oct 9", and the line
        // cost ~20dp the 636dp content box does not have.
        Text(
            monthTitle(state.month, state.today),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = SoftColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MonthButton("Previous month", leftArrow = true, onClick = callbacks.onPreviousMonth)
            MonthButton("Next month", leftArrow = false, onClick = callbacks.onNextMonth)
        }
    }
}

/** "October" for this year's months, "October 2027" for any other year. */
internal fun monthTitle(month: YearMonth, today: LocalDate): String {
    val name = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return if (month.year == today.year) name else "$name ${month.year}"
}

@Composable
private fun MonthButton(description: String, leftArrow: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(TOUCH)
            .clip(CircleShape)
            .background(SoftColors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Same glyph both ways, the second turned half a revolution: no forward arrow is vendored.
        MsIcon(
            R.drawable.ms_arrow_back,
            contentDescription = null,
            tint = SoftColors.text,
            modifier = if (leftArrow) Modifier else Modifier.rotate(180f),
        )
    }
}

@Composable
private fun WeekdayHeader() {
    // The grid's own column order (the locale's first day of week); see buildHomeCells.
    val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        for (i in 0 until DAYS_IN_WEEK) {
            val day: DayOfWeek = first.plus(i.toLong())
            Text(
                day.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = fixedSp(LEGEND_TEXT_DP),
                    lineHeight = fixedSp(LEGEND_TEXT_DP + 3),
                ),
                color = SoftColors.text3,
            )
        }
    }
}

@Composable
private fun DayGrid(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks) {
    Column(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        state.cells.chunked(DAYS_IN_WEEK).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                week.forEach { cell ->
                    DayCell(cell, Modifier.weight(1f).fillMaxHeight()) { callbacks.onSelectDay(cell.date) }
                }
            }
        }
        CalendarLegend(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DayCell(cell: CalendarCellUi, modifier: Modifier, onClick: () -> Unit) {
    val accent = AreaAccent.CALENDAR
    // 8dp, not the prototype's 14: a 20-24dp cell clipped to 14dp corners would cut its own marks.
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(shape)
            .let {
                if (cell.isSelected) {
                    it.background(accent.container, shape).border(1.5.dp, accent.onContainer, shape)
                } else {
                    it
                }
            }
            .let { if (cell.inMonth) it.clickable(role = Role.Button, onClick = onClick) else it }
            .semantics { contentDescription = cellDescription(cell) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Today is the number in a filled disc; the marks below it stay on the plain card so their
        // accents are readable. Selection is the tinted, outlined cell.
        Box(
            Modifier
                .let { if (cell.isToday) it.background(accent.onContainer, CircleShape) else it }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = fixedSp(GRID_TEXT_DP),
                    lineHeight = fixedSp(GRID_TEXT_DP + 3),
                    fontWeight = FontWeight.SemiBold,
                ),
                color = when {
                    cell.isToday -> accent.container
                    cell.inMonth -> SoftColors.text
                    else -> SoftColors.outline
                },
                maxLines = 1,
            )
        }
        Markers(cell.markers)
    }
}

/** Events a round CALENDAR dot, to-dos a round LISTS dot, suggestions a SQUARE NEWS dot - the shape
 * differs from the other two, and the legend says all three in words. */
@Composable
private fun Markers(markers: DayMarkers) {
    Row(
        Modifier.height(5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (markers.events > 0) Box(Modifier.size(5.dp).background(AreaAccent.CALENDAR.onContainer, CircleShape))
        if (markers.todos > 0) Box(Modifier.size(5.dp).background(AreaAccent.LISTS.onContainer, CircleShape))
        if (markers.suggestions > 0) {
            Box(Modifier.size(5.dp).background(AreaAccent.NEWS.onContainer, RoundedCornerShape(1.dp)))
        }
    }
}

private fun cellDescription(cell: CalendarCellUi): String {
    if (!cell.inMonth) return "Outside this month"
    val date = cell.date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH))
    val parts = buildList {
        if (cell.isToday) add("today")
        if (cell.markers.events > 0) add("${cell.markers.events} event" + if (cell.markers.events == 1) "" else "s")
        if (cell.markers.todos > 0) add("${cell.markers.todos} to-do" + if (cell.markers.todos == 1) "" else "s")
        if (cell.markers.suggestions > 0) {
            val n = cell.markers.suggestions
            add("$n suggestion${if (n == 1) "" else "s"}, not plans")
        }
    }
    return (listOf(date) + parts).joinToString(", ")
}

// ------------------------------------------------------------------------------------ agenda

@Composable
private fun AgendaPanel(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks, rowH: Dp) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp)
            .background(SoftColors.card, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                dayTitle(state.selectedDay, state.today),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = fixedSp(AGENDA_TITLE_DP),
                    lineHeight = fixedSp(AGENDA_TITLE_DP + 5),
                    fontWeight = FontWeight.Bold,
                ),
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                countLabel(state),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = fixedSp(LEGEND_TEXT_DP),
                    lineHeight = fixedSp(LEGEND_TEXT_DP + 3),
                ),
                color = SoftColors.text3,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        AgendaRows(state, callbacks, rowH, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp))
    }
}

/** The agenda's rows: as many as the measured height holds, then "+N more" (see [fitAgenda]). */
@Composable
private fun AgendaRows(
    state: HomeCalendarUiState,
    callbacks: HomeCalendarCallbacks,
    rowH: Dp,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        // A partial read is said above the rows it affects (an empty day says it in place of "nothing").
        val note = state.readNote.takeIf { state.dayRows.isNotEmpty() }
        val room = maxHeight - if (note != null) READ_NOTE_LINE else 0.dp
        val fit = fitAgenda(
            total = state.dayRows.size,
            rowCapacity = (room / rowH).toInt(),
            rowCapacityWithMore = ((room - MORE_LINE) / rowH).toInt(),
        )
        Column {
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = fixedSp(LEGEND_TEXT_DP),
                        lineHeight = fixedSp(LEGEND_TEXT_DP + 3),
                    ),
                    color = SoftColors.caution,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(READ_NOTE_LINE),
                )
            }
            if (state.dayRows.isEmpty()) {
                Text(
                    if (state.readNote != null) state.readNote else "Nothing on this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.readNote != null) SoftColors.caution else SoftColors.text2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            state.dayRows.take(fit.shown).forEach { row ->
                AgendaRow(row, rowH) { callbacks.onOpenRow(state.selectedDay, row) }
            }
            if (fit.more > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(MORE_LINE)
                        .clickable(role = Role.Button) { callbacks.onOpenDay(state.selectedDay) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "+${fit.more} more",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AreaAccent.CALENDAR.onContainer,
                    )
                }
            }
        }
    }
}

private val TITLE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

internal fun dayTitle(day: LocalDate, today: LocalDate): String =
    (if (day == today) "Today · " else "") + day.format(TITLE_DATE)

/** "2 plans, 1 suggestion": a suggestion is never added into the plan count. */
internal fun countLabel(state: HomeCalendarUiState): String {
    val plans = state.planCount
    val ideas = state.suggestionCount
    if (plans == 0 && ideas == 0) return ""
    val planText = if (plans == 1) "1 plan" else "$plans plans"
    val ideaText = if (ideas == 1) "1 suggestion" else "$ideas suggestions"
    return listOfNotNull(planText.takeIf { plans > 0 }, ideaText.takeIf { ideas > 0 }).joinToString(", ")
}

internal fun accentOf(kind: AgendaKind): AreaAccent = when (kind) {
    AgendaKind.EVENT -> AreaAccent.CALENDAR
    AgendaKind.TODO -> AreaAccent.LISTS
    // The accent the day view's suggestion rows already wear (SUGGESTION_ACCENT).
    AgendaKind.SUGGESTION -> AreaAccent.NEWS
}

@Composable
private fun AgendaRow(row: AgendaRowUi, rowH: Dp, onClick: () -> Unit) {
    val accent = accentOf(row.kind).onContainer
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowH)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.whenLabel,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelSmall,
            color = SoftColors.text2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.width(4.dp).fillMaxHeight(0.8f).background(accent, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (row.done) SoftColors.text3 else SoftColors.text,
                textDecoration = if (row.done) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------------------------ legend

/** The grid's legend, in words (never colour alone): what each mark means. */
@Composable
fun CalendarLegend(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("Events") { Box(Modifier.size(5.dp).background(AreaAccent.CALENDAR.onContainer, CircleShape)) }
        LegendItem("To-dos") { Box(Modifier.size(5.dp).background(AreaAccent.LISTS.onContainer, CircleShape)) }
        LegendItem("Suggestions, not plans") {
            Box(Modifier.size(5.dp).background(AreaAccent.NEWS.onContainer, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun LegendItem(label: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mark()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = fixedSp(LEGEND_TEXT_DP),
                lineHeight = fixedSp(LEGEND_TEXT_DP + 3),
            ),
            color = SoftColors.text3,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------------------------ buttons

/** The three panel buttons (To-dos, Lists, Ideas), each opening a bottom sheet. The Ask/mic button
 * lives in the shell's talk bar and is deliberately not repeated here. */
@Composable
fun HomePanelButtons(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton(
            "To-dos",
            if (state.todosNote != null) "can't read" else "${state.openTodoCount} open",
            AreaAccent.LISTS.container, AreaAccent.LISTS.onContainer, Modifier.weight(1f),
        ) { callbacks.onOpenSheet(PanelSheet.TODOS) }
        PanelButton(
            "Lists",
            when {
                state.listsNote != null -> "can't read"
                state.lists.size == 1 -> "1 list"
                else -> "${state.lists.size} lists"
            },
            SoftColors.cardHigh, SoftColors.text, Modifier.weight(1f),
        ) { callbacks.onOpenSheet(PanelSheet.LISTS) }
        PanelButton(
            "Ideas",
            when {
                state.ideasNote != null -> "can't read"
                state.ideas.isEmpty() -> "none this weekend"
                else -> "${state.ideas.size} this weekend"
            },
            AreaAccent.NEWS.container, AreaAccent.NEWS.onContainer, Modifier.weight(1f),
        ) { callbacks.onOpenSheet(PanelSheet.IDEAS) }
    }
}

@Composable
private fun PanelButton(
    label: String,
    sub: String,
    container: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
        )
        Text(
            sub,
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = SUB_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
