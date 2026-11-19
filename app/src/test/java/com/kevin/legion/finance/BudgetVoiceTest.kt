package com.kevin.legion.finance

import com.kevin.legion.ui.typeset.TypesetHomeDerive
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Alfred says for budget left, one category's spend and the top categories (2026-11-19). Every
 * figure is the engine's budgets body, the read HOME's "left for <month>" makes. Invented household:
 * made-up categories and amounts only.
 */
class BudgetVoiceTest {
    private val november = YearMonth.of(2026, 11)
    private val october = YearMonth.of(2026, 10)
    private val now = Instant.parse("2026-11-19T18:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private fun line(name: String, spent: Long, cap: Long? = null, pending: Long = 0, unverified: Boolean = false) =
        BudgetLineWire(
            category = name,
            spentCents = spent,
            capCents = cap,
            pendingCents = pending,
            pendingCount = if (pending > 0) 1 else 0,
            unverified = unverified,
        )

    private fun body(
        month: YearMonth = november,
        lines: List<BudgetLineWire> = listOf(
            line("Groceries", 41_207, cap = 60_000, pending = 1_250),
            line("Dining", 18_950, cap = 25_000),
            line("Fuel", 9_340, cap = 15_000),
            line("Hobbies", 4_400),
        ),
        fixed: List<FixedWire> = listOf(FixedWire("Rent", paid = true, paidCents = 150_000, capCents = 150_000)),
        uncategorised: Long = 0,
    ) = BudgetsWire(
        month = month.toString(),
        daysInMonth = month.lengthOfMonth(),
        daysElapsed = if (month == november) 19 else month.lengthOfMonth(),
        categories = lines,
        // The engine's total: capped, non-fixed lines only.
        total = BudgetTotalWire(
            spentCents = lines.filter { (it.capCents ?: 0) > 0 }.sumOf { it.spentCents },
            capCents = lines.sumOf { it.capCents ?: 0 },
        ),
        uncategorisedCents = uncategorised,
        fixed = fixed,
    )

    private val at = BudgetVoice.At(month = november, current = november, nowMs = now)
    private val known = listOf("Dining", "Fuel", "Groceries", "Hobbies", "Rent", "Transfers", "Travel")

    // --- budget left -------------------------------------------------------------------------

    @Test
    fun `budget left is the same figure HOME's front page shows, from the same body`() {
        val read = FinanceRead.Fresh(body())
        val voice = BudgetVoice.left(read, at)
        val screen = TypesetHomeDerive.left(read, "November")
        // 1000.00 cap - 694.97 spent = 305.03, on the screen and in his ear.
        assertEquals(30_503L, voice.leftCents)
        assertEquals(screen.number, com.kevin.legion.assistant.cards.PhoneCards.money(voice.leftCents!!, "usd"))
        assertEquals("305.03", voice.amount)
        assertEquals("USD", voice.currency)
        assertTrue(voice.speech!!, voice.speech!!.contains(SpokenMoney.amount(30_503)))
        assertTrue(voice.speech!!, voice.speech!!.contains("left of the " + SpokenMoney.amount(100_000) + " budget for November"))
        assertTrue(voice.speech!!, voice.speech!!.contains("11 days to go"))
    }

    @Test
    fun `budget left says pending charges in it and that fixed bills are not`() {
        val voice = BudgetVoice.left(FinanceRead.Fresh(body()), at)
        assertTrue(voice.speech!!, voice.speech!!.contains("includes " + SpokenMoney.amount(1_250) + " of pending charges"))
        assertTrue(voice.speech!!, voice.speech!!.contains("Fixed bills are not in it"))
    }

    @Test
    fun `over budget says over, never a negative left`() {
        val over = body(lines = listOf(line("Dining", 30_000, cap = 25_000)), fixed = emptyList())
        val voice = BudgetVoice.left(FinanceRead.Fresh(over), at)
        assertEquals(-5_000L, voice.leftCents)
        assertTrue(voice.speech!!, voice.speech!!.contains(SpokenMoney.amount(5_000) + " over the"))
        assertFalse(voice.speech!!, voice.speech!!.contains("minus"))
    }

    @Test
    fun `a past month says how it ended`() {
        val voice = BudgetVoice.left(FinanceRead.Fresh(body(month = october)), at.copy(month = october))
        assertTrue(voice.speech!!, voice.speech!!.startsWith("October ended " + SpokenMoney.amount(30_503) + " under"))
        assertEquals("2026-10-01", voice.periodStart)
        assertEquals("2026-10-31", voice.periodEnd)
    }

    @Test
    fun `no budget set says so and never says zero`() {
        val none = body(lines = listOf(line("Groceries", 41_207)), fixed = emptyList())
        val voice = BudgetVoice.left(FinanceRead.Fresh(none), at)
        assertNull(voice.leftCents)
        assertNull(voice.amount)
        assertEquals("No budget is set for November, so there is no amount left to say.", voice.speech)
        assertFalse(voice.speech!!.contains("zero"))
    }

    @Test
    fun `no read says why in words, never zero`() {
        val voice = BudgetVoice.left(FinanceRead.Unavailable("The engine could not be reached."), at)
        assertFalse(voice.ok)
        assertNull(voice.leftCents)
        assertEquals("There is no budget figure for November: The engine could not be reached.", voice.speech)
    }

    // --- staleness ---------------------------------------------------------------------------

    @Test
    fun `a fresh read is as of now and not stale`() {
        val voice = BudgetVoice.left(FinanceRead.Fresh(body()), at)
        assertEquals(Instant.ofEpochMilli(now).toString(), voice.asOf)
        assertFalse(voice.stale)
        assertFalse(voice.speech!!.contains("saved"))
    }

    @Test
    fun `a saved copy under a day old says it is the saved figure`() {
        val read = FinanceRead.Saved(body(), savedAtMs = now - 3 * hour, note = "Saved 3 hours ago. x")
        val voice = BudgetVoice.left(read, at)
        assertFalse(voice.stale)
        assertEquals(Instant.ofEpochMilli(now - 3 * hour).toString(), voice.asOf)
        assertTrue(voice.speech!!, voice.speech!!.endsWith("That is the last saved figure, from 3 hours ago."))
    }

    @Test
    fun `a saved copy over a day old says so, by HOME's one-day rule`() {
        val read = FinanceRead.Saved(body(), savedAtMs = now - 25 * hour, note = "Saved 1 days ago. x")
        val voice = BudgetVoice.left(read, at)
        assertTrue(voice.stale)
        assertTrue(voice.speech!!, voice.speech!!.contains(NetCashVoice.STALE_SENTENCE))
    }

    // --- one category ------------------------------------------------------------------------

    @Test
    fun `a category is matched by exact or case and space folded name`() {
        assertEquals(BudgetVoice.Match.One("Groceries"), BudgetVoice.match("Groceries", known))
        assertEquals(BudgetVoice.Match.One("Groceries"), BudgetVoice.match("  groceries ", known))
        assertEquals(BudgetVoice.Match.One("Dining Out"), BudgetVoice.match("diningout", listOf("Dining Out", "Fuel")))
    }

    @Test
    fun `no fuzzy guessing - a near name is no match and the valid names come back`() {
        assertEquals(BudgetVoice.Match.None, BudgetVoice.match("grocery", known))
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "boat repairs", known, emptySet(), at)
        assertFalse(voice.ok)
        assertNull(voice.speech)
        assertTrue(voice.validCategories.containsAll(known))
        assertTrue(voice.note!!, voice.note!!.contains("no \"boat repairs\" category"))
    }

    @Test
    fun `two categories that fold the same are ambiguous and both come back`() {
        val names = listOf("Home Goods", "HomeGoods", "Fuel")
        val match = BudgetVoice.match("home goods", names)
        assertEquals(BudgetVoice.Match.One("Home Goods"), BudgetVoice.match("Home Goods", names))
        assertEquals(BudgetVoice.Match.Several(listOf("Home Goods", "HomeGoods")), match)
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "home goods", names, emptySet(), at)
        assertNull(voice.speech)
        assertEquals(listOf("Home Goods", "HomeGoods"), voice.matches)
    }

    @Test
    fun `a category's spend this month, with its cap and pending`() {
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "groceries", known, emptySet(), at)
        assertEquals("Groceries", voice.category)
        assertEquals(41_207L, voice.amountCents)
        assertEquals("412.07", voice.amount)
        assertEquals(18_793L, voice.leftCents)
        val s = voice.speech!!
        assertTrue(s, s.startsWith("Groceries so far in November: " + SpokenMoney.amount(41_207) + "."))
        assertTrue(s, s.contains(SpokenMoney.amount(18_793) + " left of its " + SpokenMoney.amount(60_000) + " budget"))
        assertTrue(s, s.contains("includes " + SpokenMoney.amount(1_250) + " pending"))
    }

    @Test
    fun `last month's category says the month and no days to go`() {
        val voice = BudgetVoice.category(FinanceRead.Fresh(body(month = october)), "Dining", known, emptySet(), at.copy(month = october))
        assertTrue(voice.speech!!, voice.speech!!.startsWith("Dining in October: " + SpokenMoney.amount(18_950) + "."))
    }

    @Test
    fun `a category with no cap has no left`() {
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "hobbies", known, emptySet(), at)
        assertEquals(4_400L, voice.amountCents)
        assertNull(voice.leftCents)
        assertEquals("Hobbies so far in November: " + SpokenMoney.amount(4_400) + ". It has no budget set.", voice.speech)
    }

    @Test
    fun `a real category with nothing on file says so, not zero dollars`() {
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "travel", known, emptySet(), at)
        assertTrue(voice.ok)
        assertNull(voice.amountCents)
        assertEquals("No Travel spending is on file for November.", voice.speech)
    }

    @Test
    fun `a fixed category says paid or not paid`() {
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "rent", known, emptySet(), at)
        assertEquals(150_000L, voice.amountCents)
        assertEquals("Rent for November: " + SpokenMoney.amount(150_000) + " paid.", voice.speech)
        val unpaid = body(fixed = listOf(FixedWire("Rent", paid = false)))
        assertEquals(
            "Rent for November: not paid yet.",
            BudgetVoice.category(FinanceRead.Fresh(unpaid), "Rent", known, emptySet(), at).speech,
        )
    }

    @Test
    fun `a transfer category is not spending, by the screen's own definition`() {
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "transfers", known, setOf("Transfers"), at)
        assertNull(voice.amountCents)
        assertEquals("Transfers is not counted as spending, so there is no spend figure for it.", voice.speech)
    }

    @Test
    fun `the valid names include the engine's own when the phone's list is empty`() {
        val voice = BudgetVoice.category(FinanceRead.Fresh(body()), "fuel", emptyList(), emptySet(), at)
        assertEquals(9_340L, voice.amountCents)
    }

    // --- top categories ----------------------------------------------------------------------

    @Test
    fun `top categories are the engine's day-to-day lines, highest first`() {
        val voice = BudgetVoice.top(FinanceRead.Fresh(body(uncategorised = 2_000)), at)
        assertEquals(listOf("Groceries", "Dining", "Fuel"), voice.top.map { it.category })
        assertEquals(listOf("412.07", "189.50", "93.40"), voice.top.map { it.amount })
        val s = voice.speech!!
        assertTrue(s, s.startsWith("Top day-to-day spending for November so far: Groceries, " + SpokenMoney.amount(41_207)))
        assertTrue(s, s.contains("and Fuel, " + SpokenMoney.amount(9_340) + "."))
        assertTrue(s, s.contains(SpokenMoney.amount(2_000) + " is not categorised yet"))
    }

    @Test
    fun `no spending at all says so`() {
        val voice = BudgetVoice.top(FinanceRead.Fresh(body(lines = emptyList(), fixed = emptyList())), at)
        assertTrue(voice.top.isEmpty())
        assertEquals("No day-to-day spending is on file for November.", voice.speech)
    }

    // --- month and time zone -----------------------------------------------------------------

    @Test
    fun `the current month is the phone's local month, not UTC's`() {
        // 03:30 UTC on Nov 1 is still the evening of Oct 31 in a UTC-5 zone.
        val utcNovember = Instant.parse("2026-11-01T03:30:00Z").toEpochMilli()
        assertEquals(october, BudgetVoice.currentMonth(utcNovember, ZoneId.of("America/Chicago")))
        assertEquals(november, BudgetVoice.currentMonth(utcNovember, ZoneId.of("UTC")))
    }
}
