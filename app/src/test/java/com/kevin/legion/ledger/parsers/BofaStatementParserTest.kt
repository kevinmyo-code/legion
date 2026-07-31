package com.kevin.legion.ledger.parsers

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BofaStatementParserTest {
    @Before
    fun setup() {
        PdfWords.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `parses a happy-path statement across all four sections`() {
        val fixture = File("src/test/resources/ledger_fixtures/bofa_happy_path.pdf")
        val transactions = fixture.inputStream().use { BofaStatementParser.parse(fixture.name, it) }

        assertEquals(4, transactions.size)
        assertEquals(200000L, transactions[0].amountCents)
        assertEquals("PAYROLL DEPOSIT", transactions[0].description)
        assertEquals(-10000L, transactions[1].amountCents)
        assertEquals(-5000L, transactions[2].amountCents)
        assertEquals(-1200L, transactions[3].amountCents)
        assertEquals(LedgerCurrency.USD, transactions[0].currency)
        assertEquals(IngestMethod.DETERMINISTIC, transactions[0].ingestMethod)
        assertEquals("123456789012", transactions[0].accountId)
    }

    @Test
    fun `rejects a statement with a corrupted section total`() {
        val fixture = File("src/test/resources/ledger_fixtures/bofa_section_mismatch.pdf")
        assertThrows(BalanceContinuityException::class.java) {
            fixture.inputStream().use { BofaStatementParser.parse(fixture.name, it) }
        }
    }
}
