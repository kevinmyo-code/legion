package com.kevin.legion.ui.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.CategorySetResult
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [recategorizePreviewSentence]/[recategorizeResultMessage] -
 * `LedgerCategoryDrilldown.kt`'s hand-recategorise panel, no Robolectric needed since neither
 * function touches Compose or Room.
 */
class LedgerCategoryDrilldownTest {

    @Test
    fun `too-short key says so, regardless of a stale preview count`() {
        assertEquals(
            "Too short - needs at least 4 characters",
            recategorizePreviewSentence("PET", longEnough = false, bankNoise = false, previewCount = 7),
        )
    }

    @Test
    fun `a long-enough key with no preview yet reads 'Checking' (in progress)`() {
        assertEquals(
            "Checking...",
            recategorizePreviewSentence("PETCO", longEnough = true, bankNoise = false, previewCount = null),
        )
    }

    @Test
    fun `singular transaction count is grammatical`() {
        assertEquals(
            "Will move 1 transaction matching \"PETCO\", and remember it for future imports",
            recategorizePreviewSentence("petco", longEnough = true, bankNoise = false, previewCount = 1),
        )
    }

    @Test
    fun `plural transaction count, and the Petco concrete case reaches both rows via one key`() {
        assertEquals(
            "Will move 2 transactions matching \"PETCO\", and remember it for future imports",
            recategorizePreviewSentence("petco", longEnough = true, bankNoise = false, previewCount = 2),
        )
    }

    @Test
    fun `zero-count preview still reads as a plural sentence, not an error - that's the result message's job`() {
        assertEquals(
            "Will move 0 transactions matching \"PETCO\", and remember it for future imports",
            recategorizePreviewSentence("petco", longEnough = true, bankNoise = false, previewCount = 0),
        )
    }

    @Test
    fun `a bank-noise key is refused with its own sentence, even though it's long enough and has a count`() {
        assertEquals(
            "That's a transaction type the bank prints, not a merchant - it would match nearly every card purchase",
            recategorizePreviewSentence("CHECKCARD", longEnough = true, bankNoise = true, previewCount = 61),
        )
    }

    @Test
    fun `result message distinguishes keyTooShort from a long-enough key that matched nothing`() {
        assertEquals(
            "Key too short - nothing changed.",
            recategorizeResultMessage(CategorySetResult(rowsTouched = 0, merchantsTouched = 0, keyTooShort = true), "PET"),
        )
        assertEquals(
            "No transactions matched \"ZZZZ\".",
            recategorizeResultMessage(CategorySetResult(rowsTouched = 0, merchantsTouched = 0, keyTooShort = false), "zzzz"),
        )
    }

    @Test
    fun `result message distinguishes isNoiseKey from both keyTooShort and a plain zero-match`() {
        assertEquals(
            "That's a bank transaction type, not a merchant - nothing changed.",
            recategorizeResultMessage(CategorySetResult(rowsTouched = 0, merchantsTouched = 0, isNoiseKey = true), "CHECKCARD"),
        )
    }

    @Test
    fun `result message is grammatical for one row versus many`() {
        assertEquals(
            "Moved 1 transaction.",
            recategorizeResultMessage(CategorySetResult(rowsTouched = 1, merchantsTouched = 1), "PETCO"),
        )
        assertEquals(
            "Moved 2 transactions.",
            recategorizeResultMessage(CategorySetResult(rowsTouched = 2, merchantsTouched = 1), "PETCO"),
        )
    }

    // ---- ticket 03 (quant-viz): categoryDailySpendBars ---------------------------------------

    private val month = YearMonth.of(2026, 7)
    private fun day(dayOfMonth: Int): Long = month.atDay(dayOfMonth).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun txn(dayOfMonth: Int, amountCents: Long, accountId: String = "checking") = LedgerTransaction(
        id = dayOfMonth.toLong(), sourceFile = "s", accountId = accountId, currency = LedgerCurrency.USD,
        txnDate = day(dayOfMonth), description = "ROW", amountCents = amountCents, lineRef = "1",
        ingestMethod = IngestMethod.DETERMINISTIC,
    )
    private fun fullCoverage(accountId: String = "checking") = listOf(
        AccountCoverage(accountId, coversWholeMonth = true, coveredFromMs = day(1), coveredToMs = day(month.lengthOfMonth())),
    )

    @Test
    fun `the chart's summed cents equal the drilldown's own total for the month`() {
        val rows = listOf(txn(1, -1200), txn(1, -300), txn(15, -4500))
        val bars = categoryDailySpendBars(rows, month, fullCoverage())
        val summed = bars.filterNotNull().sumOf { it.value.toLong() }
        assertEquals(rows.sumOf { kotlin.math.abs(it.amountCents) }, summed)
    }

    @Test
    fun `a covered day with no rows is a genuine zero bar, never a gap`() {
        val bars = categoryDailySpendBars(listOf(txn(1, -1000)), month, fullCoverage())
        assertEquals(0f, bars[1]!!.value) // day 2, index 1
    }

    @Test
    fun `a day outside every covered range is a null gap slot`() {
        val partial = listOf(
            AccountCoverage("checking", coversWholeMonth = false, coveredFromMs = day(1), coveredToMs = day(10)),
        )
        val bars = categoryDailySpendBars(emptyList(), month, partial)
        assertNull(bars[20]) // day 21, well past the covered window
    }

    @Test
    fun `a real row on an uncovered day still sums in, proving the day existed`() {
        val partial = listOf(
            AccountCoverage("checking", coversWholeMonth = false, coveredFromMs = day(1), coveredToMs = day(10)),
        )
        val bars = categoryDailySpendBars(listOf(txn(20, -750)), month, partial)
        assertEquals(750f, bars[19]!!.value) // day 20, index 19
    }

    @Test
    fun `only the single max-spend day gets a value label`() {
        val rows = listOf(txn(1, -1000), txn(5, -9999), txn(10, -500))
        val bars = categoryDailySpendBars(rows, month, fullCoverage())
        val labelled = bars.filterNotNull().filter { it.valueLabel != null }
        assertEquals(1, labelled.size)
        assertEquals("99.99", labelled.single().valueLabel)
    }

    @Test
    fun `an account coverage entry with a null bound contributes no covered range`() {
        val nullBound = listOf(AccountCoverage("checking", coversWholeMonth = false, coveredFromMs = null, coveredToMs = null))
        val bars = categoryDailySpendBars(emptyList(), month, nullBound)
        assertTrue(bars.all { it == null })
    }
}
