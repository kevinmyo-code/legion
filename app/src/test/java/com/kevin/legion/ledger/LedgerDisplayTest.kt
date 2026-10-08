package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerCurrency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests, no Robolectric needed - [displayDescription]/
 * [formatCents]/[formatMoney] never touch Android or PdfBox. Cases mirror
 * ticket 08 resolution §4's real-length BofA sample strings, the same ones
 * that exposed the truncation defect in the throwaway prototype.
 */
class LedgerDisplayTest {
    @Test
    fun `strips the CHECKCARD prefix and its posting-date digits`() {
        assertEquals(
            "TRADER JOES #452 SAN JOSE CA",
            displayDescription("CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA"),
        )
    }

    @Test
    fun `strips DES colon and INDN colon wherever they occur, not just as a leading prefix`() {
        assertEquals(
            "PAYROLL DIRECT DEP ID:9928471 K MYO",
            displayDescription("PAYROLL DES:DIRECT DEP ID:9928471 INDN:K MYO"),
        )
    }

    @Test
    fun `a description matching none of the three prefixes is returned unchanged`() {
        val desc = "ZELLE TRANSFER TO JR L 0708 CONF# a91x2"
        assertEquals(desc, displayDescription(desc))
    }

    @Test
    fun `CHECKCARD without exactly four digits is not stripped - it is not this bank's known shape`() {
        val desc = "CHECKCARD ABCD SOME MERCHANT"
        assertEquals(desc, displayDescription(desc))
    }

    @Test
    fun `never mutates its input - same string identity in, only the return value changes`() {
        val raw = "CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA"
        displayDescription(raw)
        // raw is a val String; the real guarantee this proves is that no
        // stateful/global rewrite happened - calling it twice on the same
        // input is idempotent and side-effect-free.
        assertEquals(
            "TRADER JOES #452 SAN JOSE CA",
            displayDescription(raw),
        )
    }

    @Test
    fun `formatCents groups thousands and always shows two decimal places`() {
        assertEquals("0.00", formatCents(0L))
        assertEquals("41.00", formatCents(4100L))
        assertEquals("-87.34", formatCents(-8734L))
        assertEquals("3,845.12", formatCents(384_512L))
        assertEquals("-1,200.00", formatCents(-120_000L))
    }

    @Test
    fun `formatMoney prefixes the currency code so a mixed-currency list is never ambiguous`() {
        assertEquals("USD -87.34", formatMoney(-8734L, LedgerCurrency.USD))
        assertEquals("SGD 2,165.82", formatMoney(216_582L, LedgerCurrency.SGD))
    }

    private fun alias(substring: String, name: String, createdAt: Long = 1L) =
        com.kevin.legion.data.local.MerchantAlias(substring = substring, displayName = name, createdAt = createdAt)

    @Test
    fun `an alias whose substring is in the raw text supplies the display name`() {
        assertEquals(
            "Walmart",
            displayDescription("JOHN NAUS MD PA COLLEYVILLE TX", listOf(alias("JOHN NAUS", "Walmart"))),
        )
    }

    @Test
    fun `alias matching ignores case`() {
        val raw = "JOHN NAUS MD PA COLLEYVILLE TX"
        assertEquals("Walmart", displayDescription(raw, listOf(alias("john naus", "Walmart"))))
    }

    @Test
    fun `the oldest alias wins when two overlap`() {
        val aliases = listOf(alias("NAUS", "First", createdAt = 1L), alias("JOHN NAUS", "Second", createdAt = 2L))
        assertEquals("First", displayDescription("JOHN NAUS MD", aliases))
    }

    @Test
    fun `no match falls back to the existing noise stripping`() {
        assertEquals(
            "TRADER JOES #452 SAN JOSE CA",
            displayDescription("CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA", listOf(alias("NAUS", "Walmart"))),
        )
    }

    @Test
    fun `a blank substring never matches`() {
        val blanks = listOf(alias("", "Everything"), alias("   ", "Also everything"))
        assertEquals("KROGER #115", displayDescription("KROGER #115", blanks))
    }

    @Test
    fun `an alias never changes the raw text it was resolved from`() {
        val raw = "JOHN NAUS MD PA COLLEYVILLE TX"
        displayDescription(raw, listOf(alias("JOHN NAUS", "Walmart")))
        assertEquals("JOHN NAUS MD PA COLLEYVILLE TX", raw)
    }
}
