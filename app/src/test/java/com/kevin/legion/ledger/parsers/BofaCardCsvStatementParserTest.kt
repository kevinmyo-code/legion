package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 12: [BofaCardCsvStatementParser] stopped being a named rejection
 * and became a real parser producing [IngestMethod.UNRECONCILED] rows. Every
 * fixture here is invented (MEMORY.md standing rule) - no real money data.
 */
class BofaCardCsvStatementParserTest {
    private val header = "Posted Date,Reference Number,Payee,Address,Amount"

    /** Test 1: recognizes the exact header; 3 invented rows parse to 3 transactions. */
    @Test
    fun `recognizes the header and parses three invented rows into three transactions`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
            "07/03/2026,2499291500000000002,PAYMENT - THANK YOU,,300.00",
            "07/05/2026,2499291500000000003,GREENFIELD MARKET,55 OAK AVE PORTLAND OR,-12.75",
        ).joinToString("\n")

        val transactions = BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        assertEquals(3, transactions.size)
    }

    /** Test 2: wrong first line -> UnrecognizedLayoutException. */
    @Test
    fun `wrong first line falls through as unrecognized, not a hard failure`() {
        val csv = "Date,Description,Amount\n01/01/2026,Something,1.00\n"
        assertThrows(UnrecognizedLayoutException::class.java) {
            BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        }
    }

    /** Test 3: every row is IngestMethod.UNRECONCILED and balanceCents == null. */
    @Test
    fun `every row is UNRECONCILED with a null balanceCents`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
        ).joinToString("\n")

        val transactions = BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        assertTrue(transactions.all { it.ingestMethod == IngestMethod.UNRECONCILED })
        assertTrue(transactions.all { it.balanceCents == null })
    }

    /** Test 4: signs preserved - a negative charge stays negative, a positive refund stays positive. */
    @Test
    fun `signs are preserved, never negated`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
            "07/02/2026,2499291500000000002,MERCHANT REFUND,,12.50",
        ).joinToString("\n")

        val transactions = BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        assertEquals(-4520L, transactions[0].amountCents)
        assertEquals(1250L, transactions[1].amountCents)
    }

    /** Test 5: quoted Payee containing a comma parses as one field. */
    @Test
    fun `a quoted Payee containing a comma parses as a single field`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,\"NORTHWIND, OUTFITTERS\",123 MAIN ST SEATTLE WA,-45.20",
        ).joinToString("\n")

        val transactions = BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        assertEquals(1, transactions.size)
        assertEquals("NORTHWIND, OUTFITTERS", transactions[0].description)
    }

    /** Test 6: accountId == the filename's last-4. */
    @Test
    fun `accountId is the filename's last-4`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
        ).joinToString("\n")

        val transactions = BofaCardCsvStatementParser.parse("currentTransaction_9981.csv", csv.byteInputStream())
        assertTrue(transactions.all { it.accountId == "9981" })
    }

    /** Test 7: filename not matching currentTransaction_<4 digits>.csv -> StatementParseException. */
    @Test
    fun `a filename that doesn't match the expected shape fails rather than guessing an account`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
        ).joinToString("\n")

        assertThrows(StatementParseException::class.java) {
            BofaCardCsvStatementParser.parse("activity_export.csv", csv.byteInputStream())
        }
    }

    /** Test 8: a 4-field row -> StatementParseException, and nothing is returned (§4 rule 6). */
    @Test
    fun `a malformed row fails the whole file, never gets silently skipped`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
            "07/02/2026,2499291500000000002,MISSING ADDRESS FIELD,-12.00",
        ).joinToString("\n")

        assertThrows(StatementParseException::class.java) {
            BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        }
    }

    /** Test 9: header present, zero data rows -> StatementParseException. */
    @Test
    fun `header with no data rows fails rather than committing an empty import`() {
        assertThrows(StatementParseException::class.java) {
            BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", header.byteInputStream())
        }
    }

    /** Test 10: CRLF input parses identically to LF input. */
    @Test
    fun `CRLF input parses identically to LF input`() {
        val rows = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
            "07/03/2026,2499291500000000002,PAYMENT - THANK YOU,,300.00",
        )
        val lf = rows.joinToString("\n").byteInputStream()
        val crlf = rows.joinToString("\r\n").byteInputStream()

        val lfResult = BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", lf)
        val crlfResult = BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", crlf)

        assertEquals(lfResult.size, crlfResult.size)
        assertEquals(lfResult.map { it.description }, crlfResult.map { it.description })
        assertEquals(lfResult.map { it.amountCents }, crlfResult.map { it.amountCents })
        assertEquals(lfResult.map { it.txnDate }, crlfResult.map { it.txnDate })
    }

    /** A card CSV file whose account digits genuinely match the real 4146 fixture is still not claimed by another parser. */
    @Test
    fun `an unrelated CSV is not claimed by the card CSV parser`() {
        val notCardCsv = "Date,Description,Amount\n01/01/2026,Something,1.00\n"
        assertThrows(UnrecognizedLayoutException::class.java) {
            BofaCardCsvStatementParser.parse("other.csv", notCardCsv.byteInputStream())
        }
    }

    /**
     * Review finding 6/§4 rule 6: a blank `Payee` is a hard failure for the
     * whole file, never a silently-skipped row - the row still has 5 fields
     * (an empty one is still a field), so this exercises the
     * `description.isBlank()` branch at `BofaCardCsvStatementParser.kt:131`,
     * distinct from the "wrong field count" branch test 8 already covers.
     */
    @Test
    fun `a blank Payee fails the whole file`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
            "07/02/2026,2499291500000000002,,123 MAIN ST SEATTLE WA,-12.00",
        ).joinToString("\n")

        assertThrows(StatementParseException::class.java) {
            BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        }
    }

    /**
     * Review finding 6/§4 rule 6: same shape as the blank-Payee case, for the
     * `amountToken.isBlank()` branch at `BofaCardCsvStatementParser.kt:134`.
     * BofA's real export never actually prints a blank Amount, but a parser
     * that would silently accept one is exactly the "skip a row it doesn't
     * recognize" failure §4 rule 6 exists to close.
     */
    @Test
    fun `a blank Amount fails the whole file`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
            "07/02/2026,2499291500000000002,GREENFIELD MARKET,123 MAIN ST SEATTLE WA,",
        ).joinToString("\n")

        assertThrows(StatementParseException::class.java) {
            BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        }
    }

    /**
     * Review finding 6: test 10 (CRLF vs LF) joins with `joinToString`, which
     * never emits a trailing line terminator, so the `dropLast(1)` guard at
     * `BofaCardCsvStatementParser.kt:88-89` - the ONE place this parser is
     * allowed to discard a "row" - was never actually exercised by the suite.
     * This fixture ends in a real trailing CRLF, matching a file saved with a
     * final newline (Kevin's real export has none, per the ticket's own
     * table, but a parser that can only handle the exact byte shape of one
     * real file is fragile in a way §4 rule 6 also cares about - the guard
     * exists precisely so a trailing terminator doesn't get parsed as an
     * empty malformed row and fail the whole import).
     */
    @Test
    fun `a trailing CRLF blank line is dropped, not treated as a malformed row`() {
        val csv = listOf(
            header,
            "07/01/2026,2499291500000000001,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,-45.20",
            "07/03/2026,2499291500000000002,PAYMENT - THANK YOU,,300.00",
        ).joinToString("\r\n") + "\r\n"

        val transactions = BofaCardCsvStatementParser.parse("currentTransaction_7823.csv", csv.byteInputStream())
        assertEquals(2, transactions.size)
    }
}
