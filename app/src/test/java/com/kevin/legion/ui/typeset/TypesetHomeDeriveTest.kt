package com.kevin.legion.ui.typeset

import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.finance.BudgetTotalWire
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.ui.home.AgendaKind
import com.kevin.legion.ui.home.AgendaRowUi
import org.junit.Assert.assertEquals
import org.junit.Test
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
    fun `next is the first timed event still ahead, in the phone's clock`() {
        val rows = listOf(
            row(AgendaKind.EVENT, "09:00", "Standup"),
            row(AgendaKind.TODO, "Due 15:00", "Essay"),
            row(AgendaKind.EVENT, "All day", "Holiday"),
            row(AgendaKind.EVENT, "16:00", "Dentist"),
            row(AgendaKind.EVENT, "15:30", "Study group"),
        )
        val twelve = TypesetHomeDerive.next(rows, now, twentyFourHour = false)
        assertEquals(TypesetFigure("3:30", "PM", "Study group", listOf("in 1 h 10 min")), twelve)
        val twentyFour = TypesetHomeDerive.next(rows, now, twentyFourHour = true)
        assertEquals("15:30", twentyFour.number)
        assertEquals(null, twentyFour.unit)
    }

    @Test
    fun `with nothing ahead today the figure says so in words`() {
        val figure = TypesetHomeDerive.next(listOf(row(AgendaKind.EVENT, "09:00", "Standup")), now, false)
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
}
