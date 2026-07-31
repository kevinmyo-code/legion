package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DbsStatementParserTest {
    @Before
    fun setup() {
        PdfWords.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `parses a happy-path statement with correct amounts and running balance`() {
        val fixture = File("src/test/resources/ledger_fixtures/dbs_happy_path.pdf")
        val transactions = fixture.inputStream().use { DbsStatementParser.parse(fixture.name, it) }

        assertEquals(3, transactions.size)
        assertEquals(-5000L, transactions[0].amountCents)
        assertEquals(295000L, transactions[1].balanceCents)
        assertEquals(IngestMethod.DETERMINISTIC, transactions[0].ingestMethod)
        assertEquals("1234567890", transactions[0].accountId)
        assertEquals("GROCERY STORE", transactions[0].description)
        assertEquals(200000L, transactions[1].amountCents)
        assertEquals(-5000L, transactions[2].amountCents)
        assertEquals(290000L, transactions[2].balanceCents)
    }

    @Test
    fun `rejects a statement with a corrupted closing balance`() {
        val fixture = File("src/test/resources/ledger_fixtures/dbs_balance_mismatch.pdf")
        assertThrows(BalanceContinuityException::class.java) {
            fixture.inputStream().use { DbsStatementParser.parse(fixture.name, it) }
        }
    }
}
