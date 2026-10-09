package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildAccountMonthResults]: the Money page's per-account month. Plain JUnit, invented fixtures.
 * The point of most of these is that the page AGREES with the definitions it reuses, so each pins
 * one rule from the brief: transfers, refunds, rent month, uncategorised, unverified, merged ids.
 */
class MoneyMonthTest {

    private var nextId = 1L
    private val oct = YearMonth.of(2026, 10)

    private fun txn(
        date: LocalDate,
        cents: Long,
        account: String = CHECKING,
        category: String? = "Dining",
        description: String = "SOME MERCHANT",
        method: IngestMethod = IngestMethod.DETERMINISTIC,
    ) = LedgerTransaction(
        id = nextId++,
        sourceFile = "statement.pdf",
        accountId = account,
        currency = LedgerCurrency.USD,
        txnDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        description = description,
        amountCents = cents,
        lineRef = "line$nextId",
        ingestMethod = method,
        category = category,
    )

    private fun spendOf(results: List<AccountMonthResult>, name: String): AccountMonthSpend =
        (results.single { it.name == name } as AccountMonthResult.Spend).spend

    private fun d(day: Int) = LocalDate.of(2026, 10, day)

    @Test
    fun `one section per account ordered by name with totals and categories largest first`() {
        val rows = listOf(
            txn(d(1), -1_000, CHECKING, "Groceries"),
            txn(d(2), -5_000, CHECKING, "Utilities"),
            txn(d(2), -2_500, CARD, "Dining"),
            txn(d(1), -500, CARD, "Dining"),
        )
        val results = buildAccountMonthResults(rows, oct, emptySet())
        assertEquals(listOf(CARD, CHECKING), results.map { it.name })
        val checking = spendOf(results, CHECKING)
        assertEquals(6_000L, checking.totalCents)
        assertEquals(listOf("Utilities", "Groceries"), checking.categories.map { it.category })
        val card = spendOf(results, CARD)
        assertEquals(3_000L, card.totalCents)
        assertEquals(listOf(3_000L), card.categories.map { it.cents })
    }

    @Test
    fun `a card payment from checking is spending on neither side`() {
        val rows = listOf(
            txn(d(2), -50_000, CHECKING, "Payments", description = "PAYMENT TO CRD 7823"),
            txn(d(2), 50_000, CARD, "Payments", description = "PAYMENT FROM CHK 5042"),
            txn(d(2), -1_200, CARD, "Dining"),
            txn(d(1), -700, CHECKING, "Groceries"),
        )
        val results = buildAccountMonthResults(rows, oct, emptySet())
        assertEquals(700L, spendOf(results, CHECKING).totalCents)
        assertEquals(1_200L, spendOf(results, CARD).totalCents)
    }

    @Test
    fun `a refund is excluded from spend, never netted`() {
        val rows = listOf(
            txn(d(2), -4_000, CARD, "Shopping"),
            txn(d(3), 1_500, CARD, "Shopping", description = "REFUND"),
        )
        assertEquals(4_000L, spendOf(buildAccountMonthResults(rows, oct, emptySet()), CARD).totalCents)
    }

    @Test
    fun `uncategorised is its own row, not in the total, and is said in words`() {
        val rows = listOf(txn(d(2), -3_000, CARD, "Dining"), txn(d(3), -900, CARD, null))
        val card = spendOf(buildAccountMonthResults(rows, oct, emptySet()), CARD)
        assertEquals(3_000L, card.totalCents)
        assertEquals(900L, card.uncategorized?.cents)
        assertNull(card.uncategorized?.category)
        assertTrue(card.disclosures.any { "NOT counted in spend" in it })
    }

    @Test
    fun `an unverified row is stated inside the total and on its category`() {
        val rows = listOf(
            txn(d(2), -3_000, CARD, "Dining", method = IngestMethod.UNRECONCILED),
            txn(d(3), -2_000, CARD, "Dining"),
            txn(d(3), -800, CARD, "Travel"),
        )
        val card = spendOf(buildAccountMonthResults(rows, oct, emptySet()), CARD)
        assertEquals(5_800L, card.totalCents)
        assertEquals(3_000L, card.unverifiedTotalCents)
        assertEquals(3_000L, card.categories.single { it.category == "Dining" }.unverifiedCents)
        assertEquals(0L, card.categories.single { it.category == "Travel" }.unverifiedCents)
    }

    @Test
    fun `a bank feed row is plain fact, counted in the total and never unverified`() {
        val rows = listOf(
            txn(d(2), -3_000, CARD, "Dining", method = IngestMethod.BANK_API),
            txn(d(3), -2_000, CARD, "Dining"),
        )
        val card = spendOf(buildAccountMonthResults(rows, oct, emptySet()), CARD)
        assertEquals(5_000L, card.totalCents)
        assertEquals(0L, card.unverifiedTotalCents)
        assertEquals(0L, card.categories.single { it.category == "Dining" }.unverifiedCents)
    }

    @Test
    fun `rent paid on the 30th of last month counts in this month and says so`() {
        val rows = listOf(
            txn(LocalDate.of(2026, 9, 30), -180_000, CHECKING, "Housing", description = "RENT"),
            txn(d(2), -1_000, CHECKING, "Groceries"),
        )
        val checking = spendOf(buildAccountMonthResults(rows, oct, emptySet()), CHECKING)
        assertEquals(181_000L, checking.totalCents)
        assertTrue(checking.disclosures.any { "Housing" in it && "2026-09-30" in it })
    }

    @Test
    fun `rent paid on the 30th of this month does not count here`() {
        val rows = listOf(
            txn(LocalDate.of(2026, 10, 30), -180_000, CHECKING, "Housing", description = "RENT"),
            txn(d(2), -1_000, CHECKING, "Groceries"),
        )
        assertEquals(1_000L, spendOf(buildAccountMonthResults(rows, oct, emptySet()), CHECKING).totalCents)
    }

    @Test
    fun `a not-spending category is excluded and disclosed`() {
        val rows = listOf(
            txn(d(2), -20_000, CHECKING, "Transfers", description = "ZELLE TO SOMEONE"),
            txn(d(2), -1_000, CHECKING, "Groceries"),
        )
        val checking = spendOf(buildAccountMonthResults(rows, oct, setOf("Transfers")), CHECKING)
        assertEquals(1_000L, checking.totalCents)
        assertTrue(checking.disclosures.any { "Transfers" in it && "excluded from spend" in it })
    }

    @Test
    fun `a bare last-4 and a full number for one card are one section`() {
        val rows = listOf(
            txn(d(2), -1_000, "5555555555557823", "Dining"),
            txn(d(3), -500, "7823", "Dining"),
        )
        val results = buildAccountMonthResults(rows, oct, emptySet())
        assertEquals(1, results.size)
        assertEquals("****7823", results.single().name.takeLast(8))
        assertEquals(1_500L, spendOf(results, results.single().name).totalCents)
    }

    @Test
    fun `an account with rows only in other months is not shown, and no rows at all is empty`() {
        val rows = listOf(txn(LocalDate.of(2026, 8, 5), -1_000, CARD, "Dining"))
        assertTrue(buildAccountMonthResults(rows, oct, emptySet()).isEmpty())
        assertTrue(buildAccountMonthResults(emptyList(), oct, emptySet()).isEmpty())
    }

    @Test
    fun `an active account with only credits is shown as having no spend, not hidden`() {
        val rows = listOf(txn(d(2), 9_000, CHECKING, null, description = "PAYROLL"))
        val checking = spendOf(buildAccountMonthResults(rows, oct, emptySet()), CHECKING)
        assertTrue(checking.hasNoSpend)
    }

    private companion object {
        const val CHECKING = "BofA checking 5042"
        const val CARD = "BofA card 7823"
    }
}
