package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `docs/ledger-csv-import-format.md` is the format contract this parser implements; this file is
 * the test of that contract. Every fixture is invented (MEMORY.md standing rule) - no real money
 * data anywhere here.
 *
 * [org.json.JSONObject] needs Robolectric to shadow Android's stub jar, same reasoning as
 * `GateCorpusTest`'s own class doc comment - the round-trip test below reads the payload back with
 * it.
 */
@RunWith(RobolectricTestRunner::class)
class LegionCsvStatementParserTest {
    private val anchorHeader =
        "account_last4,account_nickname,currency,stated_total_cents,opening_balance_cents,closing_balance_cents"
    private val linesHeader = "txn_date,description,amount_cents"

    /** A well-formed three-line statement: sum(-450, 300000, -150000) = 149550 = stated total;
     * 649550 - 500000 = 149550. Both anchors hold. */
    private fun wellFormedCsv(
        last4: String = "1234",
        nickname: String = "Kevin Checking",
        currency: String = "USD",
        statedTotal: Long = 149550,
        opening: Long = 500000,
        closing: Long = 649550,
        lines: List<Triple<String, String, Long>> = listOf(
            Triple("2026-07-03", "COFFEE", -450L),
            Triple("2026-07-11", "SALARY", 300000L),
            Triple("2026-07-20", "RENT", -150000L),
        ),
    ): String = buildString {
        appendLine(anchorHeader)
        appendLine("$last4,$nickname,$currency,$statedTotal,$opening,$closing")
        appendLine()
        appendLine(linesHeader)
        lines.forEach { (date, desc, amount) -> appendLine("$date,$desc,$amount") }
    }

    @Test
    fun `a well-formed CSV produces the exact payload the RPC accepts`() {
        val statement = LegionCsvStatementParser.parseStatement("statement.csv", wellFormedCsv().byteInputStream())
        val payload = statement.toCommitPayload(
            contentSha256 = "abc123",
            sourceFileId = "drive-file-1",
            displayName = "statement.csv",
            sizeBytes = 512,
        )

        assertEquals("abc123", payload.getString("content_sha256"))
        assertEquals("drive-file-1", payload.getString("source_file_id"))
        assertEquals("statement.csv", payload.getString("display_name"))
        assertEquals(512, payload.getLong("size_bytes"))
        assertEquals("1234", payload.getString("account_last4"))
        assertEquals("Kevin Checking", payload.getString("account_nickname"))
        assertEquals("USD", payload.getString("currency"))
        assertEquals("LLM_RECONCILED", payload.getString("provenance"))
        assertEquals(149550, payload.getLong("stated_total_cents"))
        assertEquals(500000, payload.getLong("opening_balance_cents"))
        assertEquals(649550, payload.getLong("closing_balance_cents"))

        val lines = payload.getJSONArray("lines")
        assertEquals(3, lines.length())
        assertEquals("2026-07-03", lines.getJSONObject(0).getString("txn_date"))
        assertEquals("COFFEE", lines.getJSONObject(0).getString("description"))
        assertEquals(-450, lines.getJSONObject(0).getLong("amount_cents"))
    }

    @Test
    fun `round trip also produces valid Room rows tagged LLM_RECONCILED`() {
        val transactions = LegionCsvStatementParser.parse("statement.csv", wellFormedCsv().byteInputStream())
        assertEquals(3, transactions.size)
        assertTrue(transactions.all { it.ingestMethod == IngestMethod.LLM_RECONCILED })
        assertTrue(transactions.all { it.accountId == "1234" })
        assertTrue(transactions.all { it.currency == LedgerCurrency.USD })
        assertEquals(-450, transactions[0].amountCents)
    }

    @Test
    fun `missing stated_total_cents fails with wording naming that anchor`() {
        val csv = buildString {
            appendLine(anchorHeader)
            appendLine("1234,Kevin Checking,USD,,500000,649550")
            appendLine()
            appendLine(linesHeader)
            appendLine("2026-07-03,COFFEE,-450")
        }
        val ex = assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
        assertTrue(ex.userMessage!!.contains("stated_total_cents"))
    }

    @Test
    fun `missing opening_balance_cents fails with wording naming that anchor`() {
        val csv = buildString {
            appendLine(anchorHeader)
            appendLine("1234,Kevin Checking,USD,149550,,649550")
            appendLine()
            appendLine(linesHeader)
            appendLine("2026-07-03,COFFEE,-450")
        }
        val ex = assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
        assertTrue(ex.userMessage!!.contains("opening_balance_cents"))
    }

    @Test
    fun `missing closing_balance_cents fails with wording naming that anchor`() {
        val csv = buildString {
            appendLine(anchorHeader)
            appendLine("1234,Kevin Checking,USD,149550,500000,")
            appendLine()
            appendLine(linesHeader)
            appendLine("2026-07-03,COFFEE,-450")
        }
        val ex = assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
        assertTrue(ex.userMessage!!.contains("closing_balance_cents"))
    }

    @Test
    fun `lines not summing to the stated total fail the pre-check`() {
        val csv = wellFormedCsv(statedTotal = 999)
        val ex = assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
        assertTrue(ex.userMessage!!.contains("Lines sum to"))
    }

    @Test
    fun `closing minus opening disagreeing with the line sum fails the pre-check`() {
        val csv = wellFormedCsv(closing = 1)
        val ex = assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
        assertTrue(ex.userMessage!!.contains("Closing balance minus opening balance"))
    }

    @Test
    fun `an ambiguous dollar-formatted amount is rejected, not coerced`() {
        val csv = buildString {
            appendLine(anchorHeader)
            appendLine("1234,Kevin Checking,USD,149550,500000,649550")
            appendLine()
            appendLine(linesHeader)
            appendLine("2026-07-03,COFFEE,\$4.50")
        }
        val ex = assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
        assertTrue(ex.userMessage!!.contains("amount"))
    }

    @Test
    fun `a comma-thousands amount is rejected, not coerced`() {
        val csv = wellFormedCsv(
            statedTotal = 1500000,
            opening = 0,
            closing = 1500000,
            lines = listOf(Triple("2026-07-03", "BONUS", 1500000L)),
        )
        // Sanity: the well-formed version of this line parses fine.
        val ok = LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        assertEquals(1500000, ok.statedTotalCents)

        val malformed = buildString {
            appendLine(anchorHeader)
            appendLine("1234,Kevin Checking,USD,1500000,0,1500000")
            appendLine()
            appendLine(linesHeader)
            appendLine("2026-07-03,BONUS,\"15,000.00\"")
        }
        assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", malformed.byteInputStream())
        }
    }

    @Test
    fun `zero data rows fails the whole file`() {
        val csv = buildString {
            appendLine(anchorHeader)
            appendLine("1234,Kevin Checking,USD,0,500000,500000")
            appendLine()
            appendLine(linesHeader)
        }
        assertThrows(GenericStatementParseException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
    }

    @Test
    fun `an unrecognized header falls through as unrecognized, not a hard failure`() {
        val csv = "Date,Description,Amount\n2026-01-01,Something,1.00\n"
        assertThrows(UnrecognizedLayoutException::class.java) {
            LegionCsvStatementParser.parseStatement("statement.csv", csv.byteInputStream())
        }
    }

    @Test
    fun `rows are tagged LLM_RECONCILED even though the parse itself is deterministic`() {
        val transactions = LegionCsvStatementParser.parse("statement.csv", wellFormedCsv().byteInputStream())
        assertTrue(transactions.isNotEmpty())
        assertTrue(transactions.none { it.ingestMethod == IngestMethod.DETERMINISTIC })
    }

    @Test
    fun `last-4 plus nickname both survive into the payload`() {
        val statement = LegionCsvStatementParser.parseStatement(
            "statement.csv",
            wellFormedCsv(last4 = "7823", nickname = "Household Card").byteInputStream(),
        )
        assertEquals("7823", statement.accountLast4)
        assertEquals("Household Card", statement.accountNickname)
    }

    @Test
    fun `two accounts sharing a last-4 are distinguished by nickname in the payload`() {
        val checking = LegionCsvStatementParser.parseStatement(
            "checking.csv",
            wellFormedCsv(last4 = "7823", nickname = "Kevin Checking").byteInputStream(),
        )
        val card = LegionCsvStatementParser.parseStatement(
            "card.csv",
            wellFormedCsv(last4 = "7823", nickname = "Household Card").byteInputStream(),
        )
        // Same last-4 - this is ruling 5's known collision - but the payload the RPC receives
        // still carries two different nicknames, which is what disambiguates them server-side.
        assertEquals(checking.accountLast4, card.accountLast4)
        assertTrue(checking.accountNickname != card.accountNickname)
        val checkingPayload = checking.toCommitPayload("sha-1", null, "checking.csv", 1)
        val cardPayload = card.toCommitPayload("sha-2", null, "card.csv", 1)
        assertTrue(checkingPayload.getString("account_nickname") != cardPayload.getString("account_nickname"))
    }
}
