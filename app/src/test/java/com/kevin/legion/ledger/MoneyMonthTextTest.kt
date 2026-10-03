package com.kevin.legion.ledger

import com.kevin.legion.ui.money.currentPeriodLine
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The quiet current-period line: all, some or none of an account's spend is UNRECONCILED. */
class MoneyMonthTextTest {

    private fun spend(name: String, total: Long, unverified: Long) = AccountMonthSpend(
        name = name, accountIds = setOf(name), entity = LedgerEntity.US, month = YearMonth.of(2026, 10),
        totalCents = total, unverifiedTotalCents = unverified, categories = emptyList(),
        uncategorized = null, disclosures = emptyList(),
    )

    @Test
    fun `all spend unverified`() =
        assertEquals("Current period, settles when BofA closes it.", currentPeriodLine(spend("BofA card", 5_000, 5_000)))

    @Test
    fun `some spend unverified names the amount`() = assertEquals(
        "USD 62.00 is from the current period and settles when BofA closes it.",
        currentPeriodLine(spend("BofA checking", 18_772, 6_200)),
    )

    @Test
    fun `none unverified says nothing`() = assertNull(currentPeriodLine(spend("BofA card", 5_000, 0)))

    @Test
    fun `an account not named BofA says the bank`() = assertEquals(
        "Current period, settles when the bank closes it.",
        currentPeriodLine(spend("DBS Multiplier", 100, 100)),
    )
}
