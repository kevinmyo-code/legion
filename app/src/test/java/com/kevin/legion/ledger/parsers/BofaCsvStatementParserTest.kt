package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Plain JVM, no Robolectric - this parser never touches PdfBox/AssetManager,
 * unlike [BofaStatementParserTest]/[DbsStatementParserTest].
 */
class BofaCsvStatementParserTest {
    private fun fixture(name: String) = File("src/test/resources/ledger_fixtures/$name")

    companion object {
        /** A folder-mapped account id, standing in for what [com.kevin.legion.ledger.LedgerAccountMappingPreferences] would resolve. */
        private const val MAPPED_ACCOUNT = "BOFA-CHECKING"
    }

    @Test
    fun `parses the happy-path CSV export, skipping the beginning-balance row`() {
        val fixture = fixture("bofa_csv_happy_path.csv")
        val transactions = fixture.inputStream().use { BofaCsvStatementParser.parse(fixture.name, it, MAPPED_ACCOUNT) }

        assertEquals(7, transactions.size)
        assertTrue(transactions.all { it.ingestMethod == IngestMethod.DETERMINISTIC })
        assertTrue(transactions.all { it.currency == LedgerCurrency.USD })
        // Every row is remapped to the resolved accountHint at the very end
        // of parse() - see BofaCsvStatementParser's doc comment.
        assertTrue(transactions.all { it.accountId == MAPPED_ACCOUNT })

        // Not the beginning-balance repeat row (empty amount) - the real
        // first transaction, exactly as printed.
        assertEquals("Online Banking transfer from SAV 8267 Confirmation# 2245981037", transactions[0].description)
        assertEquals(3000L, transactions[0].amountCents)
        assertEquals(2369L, transactions[0].balanceCents)

        assertEquals(240000L, transactions[1].amountCents) // payroll credit, comma-grouped
        assertEquals(-899L, transactions[2].amountCents)
        assertEquals(-495L, transactions[3].amountCents)
        assertEquals(-4574L, transactions[4].amountCents)
        assertEquals(-12840L, transactions[5].amountCents)
        assertEquals(-1500L, transactions[6].amountCents)

        // Last row's running balance is the statement's own ending balance.
        assertEquals(222061L, transactions.last().balanceCents)
    }

    @Test
    fun `quarantines when the ending balance doesn't tie out, even with no accountHint supplied`() {
        // Deliberately no accountHint - proves the numeric reconciliation
        // anchors run BEFORE account resolution (UnmappedAccountException's
        // doc comment): a file that's both unmapped AND numerically broken
        // must report the numbers problem, not the mapping one.
        val fixture = fixture("bofa_csv_balance_mismatch.csv")
        val ex = assertThrows(BalanceContinuityException::class.java) {
            fixture.inputStream().use { BofaCsvStatementParser.parse(fixture.name, it) }
        }
        // Names the actual figures, not a generic "doesn't reconcile".
        assertTrue(ex.userMessage!!.contains("2,220.61"))
        assertTrue(ex.userMessage!!.contains("9,999.99"))
        assertTrue(ex.userMessage!!.contains("Nothing was imported."))
        assertTrue(ex.message!!.contains("222061")) // computed ending, in cents
        assertTrue(ex.message!!.contains("999999")) // stated (corrupted) ending, in cents
    }

    @Test
    fun `throws UnmappedAccountException when the numbers reconcile but no accountHint is supplied`() {
        // The gap-filler case this ticket exists for: a numerically clean
        // file whose folder has no mapping yet must never silently write a
        // placeholder account - CLAUDE.md §4's "never guess" applied to
        // account identity.
        val fixture = fixture("bofa_csv_happy_path.csv")
        val ex = assertThrows(UnmappedAccountException::class.java) {
            fixture.inputStream().use { BofaCsvStatementParser.parse(fixture.name, it, accountHint = null) }
        }
        assertTrue(ex.userMessage!!.contains("Map the folder"))
        assertTrue(ex.userMessage!!.contains("Nothing was imported."))
    }

    @Test
    fun `an unrecognized CSV throws UnrecognizedLayoutException`() {
        val notBofa = "just,some,other,csv\n1,2,3,4\n"
        assertThrows(UnrecognizedLayoutException::class.java) {
            BofaCsvStatementParser.parse("other.csv", ByteArrayInputStream(notBofa.toByteArray()))
        }
    }

    @Test
    fun `a description containing a comma inside quotes parses correctly`() {
        val csv = """
            Description,,Summary Amt.
            Beginning balance as of 07/01/2026,,"-6.31"
            Total credits,,"30.00"
            Total debits,,"0.00"
            Ending balance as of 07/02/2026,,"23.69"

            Date,Description,Amount,Running Bal.
            07/01/2026,Beginning balance as of 07/01/2026,,"-6.31"
            07/01/2026,"STORE, THE #123, CITY","30.00","23.69"
        """.trimIndent()
        val transactions = BofaCsvStatementParser.parse(
            "comma.csv", ByteArrayInputStream(csv.toByteArray()), MAPPED_ACCOUNT,
        )
        assertEquals(1, transactions.size)
        assertEquals("STORE, THE #123, CITY", transactions[0].description)
        assertEquals(3000L, transactions[0].amountCents)
    }

    @Test
    fun `PDF bytes fall through as unrecognized rather than crashing`() {
        // The exact hazard the dispatcher ordering comment documents: this
        // parser must decline non-CSV bytes cleanly, never throw a raw
        // decode exception.
        val pdfLikeBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, -1, -2, 0, 10)
        assertThrows(UnrecognizedLayoutException::class.java) {
            BofaCsvStatementParser.parse("fake.pdf", ByteArrayInputStream(pdfLikeBytes))
        }
    }
}
