package com.kevin.legion.pantry

import com.kevin.legion.data.local.LedgerCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [PantryReceiptAgent.parseAndReconcile] against canned model
 * output - no network, no image fixture needed, mirrors
 * `StatementDispatcherTest`'s approach for the parts of ledger ingestion that
 * ARE unit-testable without a real Gemini call. This is the one piece of
 * pantry ingestion with no deterministic ground truth to synthesize fixtures
 * against, so the reconciliation arithmetic itself is what's under test.
 */
class PantryReceiptAgentTest {

    @Test
    fun `happy path reconciles and succeeds`() {
        val raw = """
            {"store": "NTUC FairPrice", "purchaseDate": "2026-07-20", "currency": "SGD",
             "total": "12.50",
             "items": [
               {"name": "Milk", "quantity": 1, "unitPrice": "4.50", "totalPrice": "4.50",
                "caloriesKcal": 150, "proteinG": 8.0, "carbsG": 12.0, "fatG": 8.0},
               {"name": "Bread", "quantity": 2, "unitPrice": "4.00", "totalPrice": "8.00",
                "caloriesKcal": 265, "proteinG": 9.0, "carbsG": 49.0, "fatG": 3.2}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Success)
        val success = result as PantryIngestResult.Success
        assertEquals("NTUC FairPrice", success.receipt.store)
        assertEquals(LedgerCurrency.SGD, success.receipt.currency)
        assertEquals(1250L, success.receipt.totalCents)
        assertEquals(2, success.items.size)
        assertEquals(450L, success.items[0].unitPriceCents)
        assertEquals(150, success.items[0].caloriesKcal)
    }

    @Test
    fun `mismatched total quarantines`() {
        val raw = """
            {"store": "NTUC FairPrice", "purchaseDate": "2026-07-20", "currency": "SGD",
             "total": "99.99",
             "items": [
               {"name": "Milk", "quantity": 1, "totalPrice": "4.50"},
               {"name": "Bread", "quantity": 2, "totalPrice": "8.00"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Quarantined)
    }

    @Test
    fun `missing item price quarantines`() {
        val raw = """
            {"store": "NTUC FairPrice", "purchaseDate": "2026-07-20", "currency": "SGD",
             "total": "4.50",
             "items": [{"name": "Milk"}]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Quarantined)
    }

    @Test
    fun `unparseable garbage quarantines rather than crashing`() {
        val result = PantryReceiptAgent.parseAndReconcile("not json at all", "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Quarantined)
    }

    @Test
    fun `null macro fields never gate reconciliation`() {
        val raw = """
            {"store": "Cold Storage", "purchaseDate": "2026-07-21", "currency": "USD",
             "total": "5.00",
             "items": [{"name": "Mystery item", "totalPrice": "5.00",
                        "caloriesKcal": null, "proteinG": null, "carbsG": null, "fatG": null}]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Success)
        val item = (result as PantryIngestResult.Success).items[0]
        assertNull(item.caloriesKcal)
        assertNull(item.proteinG)
    }
}
