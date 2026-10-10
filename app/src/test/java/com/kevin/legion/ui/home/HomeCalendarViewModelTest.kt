package com.kevin.legion.ui.home

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-home ticket 11: the calendar's state, with a fake [HomeCalendarSource] (no Context, no Room,
 * no Compose). The scope is Unconfined and the fake never suspends, so every launch runs to the end
 * before the call returns - the assertions read the state straight after.
 */
class HomeCalendarViewModelTest {

    private val today = LocalDate.of(2026, 10, 9) // a Friday
    private val zone = ZoneId.of("UTC")

    private fun row(key: String, kind: AgendaKind, title: String = key, reminderId: Long? = null) = AgendaRowUi(
        key = key, kind = kind, whenLabel = "All day", title = title,
        typeLabel = when (kind) {
            AgendaKind.EVENT -> "Event"
            AgendaKind.TODO -> "To-do"
            AgendaKind.SUGGESTION -> "Suggestion · not a plan"
        },
        reminderId = reminderId,
    )

    private class FakeSource : HomeCalendarSource {
        var markers = mutableMapOf<LocalDate, DayMarkers>()
        var markerGaps = listOf<String>()
        var days = mutableMapOf<LocalDate, DayRead>()
        var todos = listOf<TodoRowUi>()
        var todosThrow = false
        var lists = listOf<ListRowUi>()
        var ideas = listOf<IdeaRowUi>()
        var tickResult = true
        val ticks = mutableListOf<Pair<TodoRef, Boolean>>()
        val monthsRead = mutableListOf<YearMonth>()

        override suspend fun monthMarkers(month: YearMonth, zone: ZoneId): MonthMarkers {
            monthsRead += month
            return MonthMarkers(markers, markerGaps)
        }

        override suspend fun dayRows(day: LocalDate, zone: ZoneId) = days[day] ?: DayRead(emptyList())

        override suspend fun openTodos(zone: ZoneId): List<TodoRowUi> {
            if (todosThrow) error("db gone")
            return todos
        }

        override suspend fun setTodoDone(ref: TodoRef, done: Boolean): Boolean {
            ticks += ref to done
            return tickResult
        }

        override suspend fun lists() = lists

        override suspend fun ideas(from: LocalDate, to: LocalDate, zone: ZoneId) = ideas

        override suspend fun addIdeaToPlans(id: Long) = "Added to your plans."

        override suspend fun dropIdea(id: Long) = "Removed."
    }

    private fun vm(source: FakeSource) =
        HomeCalendarViewModel(source, today = { today }, zone = zone, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `refresh builds six weeks with today marked and the markers of each kind`() {
        val src = FakeSource()
        src.markers[LocalDate.of(2026, 10, 10)] = DayMarkers(events = 1, todos = 2, suggestions = 3)
        val model = vm(src).also { it.refresh() }
        val s = model.state.value
        assertEquals(42, s.cells.size)
        assertEquals(today, s.cells.single { it.isToday }.date)
        val sat = s.cells.single { it.date == LocalDate.of(2026, 10, 10) }
        assertEquals(DayMarkers(1, 2, 3), sat.markers)
        assertFalse(s.loading)
    }

    @Test
    fun `selecting a day moves the selection and loads that day's rows`() {
        val src = FakeSource()
        val tenth = LocalDate.of(2026, 10, 10)
        src.days[tenth] = DayRead(listOf(row("e1", AgendaKind.EVENT), row("s1", AgendaKind.SUGGESTION)))
        val model = vm(src).also { it.refresh() }
        model.selectDay(tenth)
        val s = model.state.value
        assertEquals(tenth, s.selectedDay)
        assertEquals(listOf("e1", "s1"), s.dayRows.map { it.key })
        assertTrue(s.cells.single { it.date == tenth }.isSelected)
        assertEquals(1, s.cells.count { it.isSelected })
    }

    @Test
    fun `a day outside the displayed month cannot be selected`() {
        val model = vm(FakeSource()).also { it.refresh() }
        model.selectDay(LocalDate.of(2026, 11, 2)) // a trailing cell of the October grid
        assertEquals(today, model.state.value.selectedDay)
    }

    @Test
    fun `a suggestion is shown but never counted as a plan`() {
        val src = FakeSource()
        src.days[today] = DayRead(
            listOf(
                row("e", AgendaKind.EVENT),
                row("t", AgendaKind.TODO),
                row("s", AgendaKind.SUGGESTION),
                row("s2", AgendaKind.SUGGESTION),
            ),
        )
        src.todos = listOf(TodoRowUi("a", "Quiz", "Due today", done = false, ref = TodoRef.Reminder(1)))
        val s = vm(src).also { it.refresh() }.state.value
        assertEquals(2, s.planCount)
        assertEquals(2, s.suggestionCount)
        // The to-do count is the to-do list's own, not the day's rows.
        assertEquals(1, s.openTodoCount)
        assertEquals("2 plans, 2 suggestions", countLabel(s))
    }

    @Test
    fun `suggestions sort after every plan so a short panel drops them first`() {
        val ordered = orderAgenda(
            listOf(
                row("s", AgendaKind.SUGGESTION) to 1L,
                row("t", AgendaKind.TODO) to 900L,
                row("e", AgendaKind.EVENT) to null,
            ),
        )
        assertEquals(listOf("e", "t", "s"), ordered.map { it.key })
    }

    @Test
    fun `fitAgenda shows everything that fits, otherwise the rows that fit beside a plus N more line`() {
        assertEquals(AgendaFit(3, 0), fitAgenda(total = 3, rowCapacity = 3, rowCapacityWithMore = 3))
        assertEquals(AgendaFit(0, 0), fitAgenda(total = 0, rowCapacity = 3, rowCapacityWithMore = 2))
        // Five rows, room for three, and the more line leaves room for two: show two, say +3.
        assertEquals(AgendaFit(2, 3), fitAgenda(total = 5, rowCapacity = 3, rowCapacityWithMore = 2))
        // The more line is shorter than a row, so three rows can still fit beside it.
        assertEquals(AgendaFit(3, 2), fitAgenda(total = 5, rowCapacity = 4, rowCapacityWithMore = 3))
        // Never a negative count when there is no room at all.
        assertEquals(AgendaFit(0, 5), fitAgenda(total = 5, rowCapacity = 0, rowCapacityWithMore = -1))
        // The default assumes the line costs one whole row.
        assertEquals(AgendaFit(2, 3), fitAgenda(total = 5, rowCapacity = 3))
    }

    @Test
    fun `planAgenda - 56dp of row space still shows a row, and compact rows show two`() {
        // The real phone left ~56dp: full rows beside a +N more line fit none, so the plan must not stay full.
        val plan = planAgenda(total = 9, room = 56f, fullRow = 40f, compactRow = 28f, moreLine = 28f)
        assertTrue(plan.compact)
        assertTrue("shown ${plan.fit.shown}", plan.fit.shown >= 2)
        assertEquals(9 - plan.fit.shown, plan.fit.more)
        // No height for a line of its own, so the link rides the title row rather than vanishing.
        assertTrue(plan.moreInHeader)
        // Even when the room is only just over one full row, at least one row shows.
        assertTrue(planAgenda(9, 56f, 40f, 40f, 28f).fit.shown >= 1)
    }

    @Test
    fun `planAgenda - roomy panels keep full rows, and a day that fits needs no link`() {
        val roomy = planAgenda(total = 9, room = 140f, fullRow = 40f, compactRow = 28f, moreLine = 28f)
        assertFalse(roomy.compact)
        assertEquals(AgendaFit(2, 7), roomy.fit)
        assertEquals(
            AgendaPlan(AgendaFit(1, 0), compact = false, moreInHeader = false),
            planAgenda(1, 60f, 40f, 28f, 28f),
        )
        assertEquals(0, planAgenda(0, 10f, 40f, 28f, 28f).fit.shown)
        // Never zero rows with a +N more when items exist and a compact row fits.
        for (room in 28..200) {
            val p = planAgenda(9, room.toFloat(), 40f, 28f, 28f)
            assertTrue("room $room shown ${p.fit.shown}", p.fit.shown >= 1)
        }
    }

    @Test
    fun `next month selects the 1st and returning to this month selects today`() {
        val src = FakeSource()
        val model = vm(src).also { it.refresh() }
        model.nextMonth()
        assertEquals(YearMonth.of(2026, 11), model.state.value.month)
        assertEquals(LocalDate.of(2026, 11, 1), model.state.value.selectedDay)
        model.previousMonth()
        assertEquals(today, model.state.value.selectedDay)
        assertEquals(YearMonth.of(2026, 11), src.monthsRead[1])
    }

    @Test
    fun `an unreadable kind is said in words and never reads as an empty day`() {
        val src = FakeSource()
        src.markerGaps = listOf("to-dos")
        src.days[today] = DayRead(emptyList(), unreadable = listOf("suggestions"))
        val s = vm(src).also { it.refresh() }.state.value
        assertEquals("Couldn't read to-dos, suggestions. What is shown may be incomplete.", s.readNote)
    }

    @Test
    fun `a clean read leaves no note`() {
        assertNull(vm(FakeSource()).also { it.refresh() }.state.value.readNote)
    }

    @Test
    fun `a failing to-do read is worded on the panel and does not blank the rest`() {
        val src = FakeSource()
        src.todosThrow = true
        src.lists = listOf(ListRowUi(1, "Groceries", "6 items"))
        val s = vm(src).also { it.refresh() }.state.value
        assertEquals("Couldn't read your to-dos.", s.todosNote)
        assertEquals(1, s.lists.size)
        assertNull(s.listsNote)
    }

    @Test
    fun `ticking a to-do goes through the source and keeps the row, done, in the open sheet`() {
        val src = FakeSource()
        val todo = TodoRowUi("LOCAL-1", "Quiz", "Due today", done = false, ref = TodoRef.Reminder(1))
        src.todos = listOf(todo)
        val model = vm(src).also { it.refresh() }
        model.openSheet(PanelSheet.TODOS)
        // The source now reports it ticked, so a fresh read no longer lists it.
        src.todos = emptyList()
        model.setTodoDone(todo, true)
        assertEquals(listOf(TodoRef.Reminder(1) to true), src.ticks)
        val s = model.state.value
        assertEquals(listOf(true), s.todos.map { it.done })
        assertEquals(0, s.openTodoCount)
        // Unticking it again from the same place.
        model.setTodoDone(s.todos.single(), false)
        assertEquals(TodoRef.Reminder(1) to false, src.ticks.last())
    }

    @Test
    fun `a refused tick is said in words and changes nothing on screen`() {
        val src = FakeSource()
        val todo = TodoRowUi("LOCAL-1", "Quiz", "Due today", done = false, ref = TodoRef.Reminder(1))
        src.todos = listOf(todo)
        src.tickResult = false
        val model = vm(src).also { it.refresh() }
        model.setTodoDone(todo, true)
        val s = model.state.value
        assertEquals("Couldn't tick Quiz. Nothing was changed.", s.todosNote)
        assertFalse(s.todos.single().done)
    }

    @Test
    fun `an idea action shows the sentence the existing suggestion action returned`() {
        val src = FakeSource()
        val idea = IdeaRowUi(5, "Turkeyfest", "Fri · Cuero")
        src.ideas = listOf(idea)
        val model = vm(src).also { it.refresh() }
        model.addIdeaToPlans(idea)
        assertEquals("Added to your plans.", model.state.value.ideas.single().outcome)
    }

    @Test
    fun `the sheet opens and closes`() {
        val model = vm(FakeSource()).also { it.refresh() }
        model.openSheet(PanelSheet.IDEAS)
        assertEquals(PanelSheet.IDEAS, model.state.value.sheet)
        model.closeSheet()
        assertNull(model.state.value.sheet)
    }

    // ------------------------------------------------------------------------ pure functions

    @Test
    fun `October 2026 starts on a Thursday so day one sits in column five of a Sunday-first grid`() {
        val cells = buildHomeCells(
            YearMonth.of(2026, 10), today, today, emptyMap(), firstDayOfWeek = DayOfWeek.SUNDAY,
        )
        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2026, 9, 27), cells.first().date)
        assertEquals(4, cells.indexOfFirst { it.date == LocalDate.of(2026, 10, 1) })
        assertFalse(cells.first().inMonth)
        assertEquals(31, cells.count { it.inMonth })
    }

    @Test
    fun `a Monday-first grid starts on the Monday on or before the 1st`() {
        val cells = buildHomeCells(YearMonth.of(2026, 10), today, today, emptyMap(), firstDayOfWeek = DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 9, 28), cells.first().date)
    }

    @Test
    fun `cells outside the month carry no markers and are never selected`() {
        val spill = LocalDate.of(2026, 11, 1)
        val cells = buildHomeCells(
            YearMonth.of(2026, 10), today, spill,
            mapOf(spill to DayMarkers(events = 1)), firstDayOfWeek = DayOfWeek.SUNDAY,
        )
        val c = cells.single { it.date == spill }
        assertTrue(c.markers.isEmpty)
        assertFalse(c.isSelected)
    }

    @Test
    fun `this weekend is Friday to Sunday and starts today once inside it`() {
        val sat = LocalDate.of(2026, 10, 10)
        val sun = LocalDate.of(2026, 10, 11)
        assertEquals(LocalDate.of(2026, 10, 9) to LocalDate.of(2026, 10, 11), weekendWindow(LocalDate.of(2026, 10, 7)))
        assertEquals(LocalDate.of(2026, 10, 9) to LocalDate.of(2026, 10, 11), weekendWindow(LocalDate.of(2026, 10, 9)))
        assertEquals(sat to sun, weekendWindow(sat))
        assertEquals(sun to sun, weekendWindow(sun))
        val nextFri = LocalDate.of(2026, 10, 16)
        assertEquals(nextFri to nextFri.plusDays(2), weekendWindow(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun `day and month titles read as the prototype's`() {
        assertEquals("Today · Fri, Oct 9", dayTitle(today, today))
        assertEquals("Sat, Oct 10", dayTitle(LocalDate.of(2026, 10, 10), today))
        assertEquals("October", monthTitle(YearMonth.of(2026, 10), today))
        assertEquals("January 2027", monthTitle(YearMonth.of(2027, 1), today))
    }

    @Test
    fun `agenda row height grows with the font scale and never drops under 40dp`() {
        assertEquals(40f, agendaRowHeight(1.0f).value, 0.01f)
        // 1.3 is the scale the ticket tests at: two text lines (32.5 * 1.3) need a little more than 40.
        assertEquals(42.25f, agendaRowHeight(1.3f).value, 0.01f)
        assertTrue(agendaRowHeight(2.0f).value > 44f)
        assertNotNull(agendaRowHeight(1.0f))
    }
}
