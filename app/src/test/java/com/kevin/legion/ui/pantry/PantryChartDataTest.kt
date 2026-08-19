package com.kevin.legion.ui.pantry

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryReceiptSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for [PantryChartData.kt]'s pure layer - quant-viz ticket 07's own verification
 * checkbox ("pure month-grouping function unit-tested: gap month -> null, currency selection by
 * receipt count, Long-exact sums"). Plain JUnit, no Robolectric, matching
 * [com.kevin.legion.ui.common.DeckChartDataTest]'s posture for the same reason.
 */
class PantryChartDataTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun receipt(year: Int, month: Int, day: Int, cents: Long, currency: LedgerCurrency = LedgerCurrency.USD) =
        PantryReceiptSummary(
            purchaseDate = ZonedDateTime.of(year, month, day, 12, 0, 0, 0, zone).toInstant().toEpochMilli(),
            totalCents = cents,
            currency = currency,
        )

    private fun monthMs(year: Int, month: Int, day: Int) =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------ gap month -> null

    @Test
    fun `a month with no receipts buckets to null, never to a zero`() {
        val receipts = listOf(
            receipt(2026, 6, 1, 1000),
            // July: nothing ingested.
            receipt(2026, 8, 1, 2000),
        )
        val bars = bucketMonthlySumCents(receipts, LedgerCurrency.USD, nowMs = monthMs(2026, 8, 15), zone = zone)

        assertEquals(3, bars.size)
        assertEquals(YearMonth.of(2026, 6), bars[0].month)
        assertEquals(1000L, bars[0].totalCents)
        assertEquals(YearMonth.of(2026, 7), bars[1].month)
        assertNull(bars[1].totalCents)
        assertEquals(YearMonth.of(2026, 8), bars[2].month)
        assertEquals(2000L, bars[2].totalCents)
    }

    @Test
    fun `the current month with no receipts yet is still a gap slot, not dropped`() {
        val receipts = listOf(receipt(2026, 5, 10, 500))
        val bars = bucketMonthlySumCents(receipts, LedgerCurrency.USD, nowMs = monthMs(2026, 6, 1), zone = zone)

        assertEquals(2, bars.size)
        assertEquals(500L, bars[0].totalCents)
        assertEquals(YearMonth.of(2026, 6), bars[1].month)
        assertNull(bars[1].totalCents)
    }

    @Test
    fun `a currency with no receipts at all returns an empty list, not a crash`() {
        val receipts = listOf(receipt(2026, 6, 1, 1000, LedgerCurrency.USD))
        val bars = bucketMonthlySumCents(receipts, LedgerCurrency.SGD, nowMs = monthMs(2026, 6, 15), zone = zone)
        assertTrue(bars.isEmpty())
    }

    // ------------------------------------------------------- currency selection by receipt count

    @Test
    fun `chartCurrency picks the currency with the most receipts`() {
        val receipts = listOf(
            receipt(2026, 6, 1, 1000, LedgerCurrency.USD),
            receipt(2026, 6, 2, 2000, LedgerCurrency.USD),
            receipt(2026, 6, 3, 3000, LedgerCurrency.SGD),
        )
        assertEquals(LedgerCurrency.USD, chartCurrency(receipts))
    }

    @Test
    fun `chartCurrency breaks an exact tie deterministically by currency name`() {
        val receipts = listOf(
            receipt(2026, 6, 1, 1000, LedgerCurrency.USD),
            receipt(2026, 6, 2, 2000, LedgerCurrency.SGD),
        )
        // SGD sorts before USD alphabetically - pinning this so the tie-break can never silently
        // flip between runs without a test noticing.
        assertEquals(LedgerCurrency.SGD, chartCurrency(receipts))
    }

    @Test
    fun `chartCurrency on no receipts is null, not a crash or a guessed default`() {
        assertNull(chartCurrency(emptyList()))
    }

    // -------------------------------------------------------------------- Long-exact sums

    @Test
    fun `two receipts in the same month sum exactly in Long cents`() {
        val receipts = listOf(
            receipt(2026, 6, 1, 1_234_567L),
            receipt(2026, 6, 15, 1L),
        )
        val bars = bucketMonthlySumCents(receipts, LedgerCurrency.USD, nowMs = monthMs(2026, 6, 20), zone = zone)
        assertEquals(1, bars.size)
        assertEquals(1_234_568L, bars[0].totalCents)
    }

    // ------------------------------------------------------------------ default zone is UTC
    // Regression for the 2026-08-13 review's BLOCKING finding: purchaseDate is stamped
    // atStartOfDay(ZoneOffset.UTC) by PantryReceiptAgent, so bucketing by the DEVICE zone puts a
    // receipt dated the 1st into the PREVIOUS month on any device west of UTC. The default zone
    // parameter must therefore be UTC - this test calls WITHOUT a zone argument, so it fails if
    // the default ever regresses to ZoneId.systemDefault() (whenever the build machine's zone is
    // not UTC itself, which the CI-less repo cannot guarantee - hence also pinning equality with
    // the explicit-UTC call, which holds in every machine zone).

    @Test
    fun `default zone buckets a first-of-month UTC-midnight receipt into its own month`() {
        val receipts = listOf(
            PantryReceiptSummary(
                purchaseDate = java.time.LocalDate.of(2026, 7, 1)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                totalCents = 1000L,
                currency = LedgerCurrency.USD,
            ),
        )
        val nowMs = monthMs(2026, 7, 15)
        val defaultZoneBars = bucketMonthlySumCents(receipts, LedgerCurrency.USD, nowMs)
        val utcBars = bucketMonthlySumCents(receipts, LedgerCurrency.USD, nowMs, zone = zone)

        assertEquals(1, defaultZoneBars.size)
        assertEquals(YearMonth.of(2026, 7), defaultZoneBars[0].month)
        assertEquals(1000L, defaultZoneBars[0].totalCents)
        assertEquals(utcBars, defaultZoneBars)
    }

    @Test
    fun `a receipt in a currency the caller did not ask for never bleeds into the sum`() {
        val receipts = listOf(
            receipt(2026, 6, 1, 1000, LedgerCurrency.USD),
            receipt(2026, 6, 1, 999_999, LedgerCurrency.SGD),
        )
        val bars = bucketMonthlySumCents(receipts, LedgerCurrency.USD, nowMs = monthMs(2026, 6, 1), zone = zone)
        assertEquals(1, bars.size)
        assertEquals(1000L, bars[0].totalCents)
    }
}
