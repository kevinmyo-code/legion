package com.kevin.legion.ui.ledger

import com.kevin.legion.ledger.LedgerEntity
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for [parseDollarsToCents]/[currentTargetSentence] - the quant-viz ticket 09
 * SET TARGET affordance's own doc comment names these exact cases as the verification for "unit
 * test the dollars-text -> cents parser (pure function, exact Longs)". No Android dependency, plain
 * JVM test, matching every other pure ledger-UI resolver test in this package.
 */
class LedgerSetTargetParserTest {

    @Test
    fun `whole dollars with cents parses to exact Long cents`() {
        assertEquals(29999L, parseDollarsToCents("299.99"))
    }

    @Test
    fun `whole dollars with no decimal point parses`() {
        assertEquals(30000L, parseDollarsToCents("300"))
    }

    @Test
    fun `a single-digit cents part is padded as tenths, not hundredths`() {
        // "5.5" means five dollars fifty cents, not five dollars five cents - the padEnd(2, '0')
        // rule the doc comment calls out by name.
        assertEquals(550L, parseDollarsToCents("5.5"))
    }

    @Test
    fun `more than two decimal places is rejected`() {
        assertNull(parseDollarsToCents("1.234"))
    }

    @Test
    fun `a negative amount is rejected`() {
        assertNull(parseDollarsToCents("-3"))
    }

    @Test
    fun `blank text is rejected`() {
        assertNull(parseDollarsToCents(""))
    }

    @Test
    fun `non-numeric text is rejected`() {
        assertNull(parseDollarsToCents("abc"))
    }

    @Test
    fun `whitespace-only text is rejected`() {
        assertNull(parseDollarsToCents("   "))
    }

    @Test
    fun `more than one decimal point is rejected`() {
        assertNull(parseDollarsToCents("1.2.3"))
    }

    @Test
    fun `a result over the 9,999,999_99 cent ceiling is rejected`() {
        assertNull(parseDollarsToCents("10000000"))
    }

    @Test
    fun `a result exactly at the ceiling is accepted`() {
        assertEquals(9_999_999_99L, parseDollarsToCents("9999999.99"))
    }

    @Test
    fun `an explicit zero is a valid parse, not a rejection`() {
        // The ticket's own D-shape: zero is a real, allowed write that silences the meter - it must
        // not be conflated with a rejected/unparseable amount.
        assertEquals(0L, parseDollarsToCents("0"))
    }

    @Test
    fun `no target ever set reads as its own distinct sentence`() {
        assertEquals("no target set", currentTargetSentence(null, LedgerEntity.US, YearMonth.of(2026, 8)))
    }

    @Test
    fun `an explicit zero target reads the zero-meter sentence, never 'no target set'`() {
        assertEquals(
            "target USD 0.00 - no meter is drawn at zero",
            currentTargetSentence(0L, LedgerEntity.US, YearMonth.of(2026, 8)),
        )
    }

    @Test
    fun `a positive target states the amount and the open month`() {
        assertEquals(
            "target USD 300.00 since August 2026",
            currentTargetSentence(30000L, LedgerEntity.US, YearMonth.of(2026, 8)),
        )
    }
}
