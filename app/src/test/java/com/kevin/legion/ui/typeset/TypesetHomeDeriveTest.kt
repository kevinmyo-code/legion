package com.kevin.legion.ui.typeset

import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.calendar.DueEntry
import com.kevin.legion.calendar.DueInput
import com.kevin.legion.calendar.DueSource
import com.kevin.legion.calendar.DueSummary
import com.kevin.legion.finance.BudgetTotalWire
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.ui.home.AgendaKind
import com.kevin.legion.ui.home.AgendaRowUi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The Typeset home's three figures (ADR 0081): what each says, and words (never a zero) when it has none. */
class TypesetHomeDeriveTest {

    private val now = LocalTime.of(14, 20)

    private fun usd(cents: Long) = PhoneCards.money(cents, "usd")

    private fun budget(daysElapsed: Int, spent: Long) = BudgetsWire(
        month = "2026-11",
        daysInMonth = 30,
        daysElapsed = daysElapsed,
        total = BudgetTotalWire(spentCents = spent, capCents = 200_000),
    )

    private fun row(kind: AgendaKind, at: String, title: String) = AgendaRowUi("k$title", kind, at, title, "")

    @Test
    fun `next is the first timed event still ahead, in 24-hour time`() {
        val rows = listOf(
            row(AgendaKind.EVENT, "09:00", "Standup"),
            row(AgendaKind.TODO, "Due 15:00", "Essay"),
            row(AgendaKind.EVENT, "All day", "Holiday"),
            row(AgendaKind.EVENT, "16:00", "Dentist"),
            row(AgendaKind.EVENT, "3:30 PM", "Study group"),
        )
        val figure = TypesetHomeDerive.next(rows, now)
        assertEquals(TypesetFigure("15:30", null, "Study group", listOf("in 1 h 10 min")), figure)
        assertEquals("14:20", TypesetHomeDerive.time(now))
    }

    @Test
    fun `with nothing ahead today the figure says so in words`() {
        val figure = TypesetHomeDerive.next(listOf(row(AgendaKind.EVENT, "09:00", "Standup")), now)
        assertEquals("None", figure.number)
        assertEquals("Nothing else today", figure.label)
    }

    @Test
    fun `budget left is cap minus spent with the days left`() {
        val b = budget(daysElapsed = 9, spent = 76_000)
        val f = TypesetHomeDerive.left(FinanceRead.Fresh(b), "November")
        val lines = listOf("of ${usd(200_000)}, 21 days left")
        val expected = TypesetFigure(usd(124_000), null, "left for November", lines)
        assertEquals(expected, f)
    }

    @Test
    fun `over budget says over, never a negative left`() {
        val f = TypesetHomeDerive.left(FinanceRead.Fresh(budget(daysElapsed = 29, spent = 210_000)), "November")
        assertEquals("-" + usd(10_000), f.number)
        assertEquals("over for November", f.label)
    }

    @Test
    fun `no budget data says why in words, never zero`() {
        val f = TypesetHomeDerive.left(FinanceRead.Unavailable("The engine is out of reach."), "November")
        assertEquals("No figure", f.number)
        assertEquals(listOf("The engine is out of reach."), f.lines)
    }

    // 2026-11-15: "why do i have 44 due today on the home screen". The label says "due today", so the
    // number is what is due TODAY and not done; overdue work from earlier days is its own line.
    private val day = LocalDate.of(2026, 11, 15)

    private fun entry(title: String, date: LocalDate, overdue: Boolean = false) = DueEntry(
        DueInput(title, 0L, true, date, false, DueSource.TODO, whenLabel = "Nov ${date.dayOfMonth}"),
        overdue,
    )

    @Test
    fun `due today counts only what is due today, overdue on its own line`() {
        val s = DueSummary(
            from = day,
            to = day,
            open = listOf(
                entry("Old form", day.minusDays(5), overdue = true),
                entry("Old quiz", day.minusDays(26), overdue = true),
                entry("Module quiz", day),
                entry("Homework set", day),
                entry("Renew permit", day),
            ),
            doneCount = 1,
        )
        val f = TypesetHomeDerive.due(s, null)
        assertEquals("3", f.number)
        assertEquals("due today", f.label)
        assertEquals(listOf("Module quiz", "Homework set", "and 1 more", "2 overdue from earlier"), f.lines)
    }

    @Test
    fun `nothing due today is zero, and an unread figure says why, never zero`() {
        assertEquals("0", TypesetHomeDerive.due(DueSummary(day, day, emptyList(), 0), null).number)
        val unread = TypesetHomeDerive.due(null, "Couldn't read what is due.")
        assertEquals("No figure", unread.number)
        assertEquals(listOf("Couldn't read what is due."), unread.lines)
    }
}
