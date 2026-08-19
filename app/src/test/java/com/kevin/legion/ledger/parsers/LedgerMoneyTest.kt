package com.kevin.legion.ledger.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Mirrors Project Andromeda's `tests/bronze/test_money.py` exactly - same
 * cases, same expectations, proving the Kotlin port matches the Python
 * original's behavior.
 */
class LedgerMoneyTest {
    @Test
    fun `accepts well-formed amounts`() {
        assertEquals(10000L, parseMoneyCents("100.00"))
        assertEquals(123456L, parseMoneyCents("1,234.56"))
        assertEquals(-123456L, parseMoneyCents("-1,234.56"))
        assertEquals(123456L, parseMoneyCents("$1,234.56"))
        assertEquals(-1798L, parseMoneyCents("-$17.98"))
        assertEquals(0L, parseMoneyCents("0.00"))
    }

    @Test
    fun `rejects malformed amounts`() {
        val malformed = listOf(
            "1,23.45", // wrong thousands grouping
            "123.4", // missing a decimal place
            "123.456", // too many decimal places
            "123", // no decimals at all
            "(123.45)", // parenthesized negative, not a supported convention
            "123.45 CR", // trailing marker glued on
            "",
            "abc",
        )
        for (token in malformed) {
            assertThrows("expected rejection of '$token'", GenericStatementParseException::class.java) {
                parseMoneyCents(token)
            }
        }
    }

    /**
     * A leading `+` is positive, not a rejection. Regression guard for the
     * device finding of 2026-08-02: a statement printing `+1,025.00` as its
     * own total quarantined forever, because the LLM path echoes the
     * document's formatting into `statedTotal` and the parser refused it.
     */
    @Test
    fun `accepts an explicit plus sign as positive`() {
        assertEquals(102500L, parseMoneyCents("+1,025.00"))
        assertEquals(320000L, parseMoneyCents("+3,200.00"))
        assertEquals(4500L, parseMoneyCents("+45.00"))
        assertEquals(102500L, parseMoneyCents("+$1,025.00"))
        // Unchanged: plain and negative forms still mean what they did.
        assertEquals(102500L, parseMoneyCents("1,025.00"))
        assertEquals(-102500L, parseMoneyCents("-1,025.00"))
    }

    @Test
    fun `still rejects a doubled or trailing sign`() {
        for (token in listOf("++1.00", "+-1.00", "-+1.00", "1.00+")) {
            assertThrows("expected rejection of '$token'", GenericStatementParseException::class.java) {
                parseMoneyCents(token)
            }
        }
    }

    /**
     * `MONEY_TOKEN_RE` is deliberately NOT widened to `[-+]?` - a leading plus
     * falls outside the token and the remainder parses to the same value, so
     * the deterministic parsers see no change in token boundaries.
     */
    @Test
    fun `a plus-prefixed amount in free text yields the unsigned token`() {
        assertEquals(listOf("3,200.00"), findMoneyTokens("Credit +3,200.00"))
        assertEquals(320000L, parseMoneyCents(findMoneyTokens("Credit +3,200.00").single()))
    }

    @Test
    fun `extracts candidates from free text`() {
        val text = "Total Balance Carried Forward: 15,424.58 5,350.44 4,640.88"
        assertEquals(listOf("15,424.58", "5,350.44", "4,640.88"), findMoneyTokens(text))
    }
}
