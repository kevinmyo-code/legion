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

    // ---- Sales tax (fixed 2026-08-07) -------------------------------------
    //
    // These use the numbers off Kevin's REAL Walmart receipt (Katy TX,
    // 2026-08-06): items 120.84, tax 8.02, total 128.86. The old
    // `sum(items) == total` check quarantined it, and could never have passed
    // it, because tax was neither asked for nor accounted for. The 128.86 is
    // independently corroborated - it is the same figure as the WAL-MART
    // #4512 debit on that day's Bank of America activity.

    @Test
    fun `sales tax receipt reconciles against subtotal, not grand total`() {
        val raw = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "128.86", "subtotal": "120.84", "tax": "8.02",
             "items": [
               {"name": "Paper towels", "quantity": 1, "totalPrice": "60.00"},
               {"name": "Chicken thighs", "quantity": 1, "totalPrice": "40.84"},
               {"name": "Laundry detergent", "quantity": 1, "totalPrice": "20.00"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue("expected Success, got $result", result is PantryIngestResult.Success)
        val success = result as PantryIngestResult.Success
        // totalCents stays the GRAND total - what actually left the account,
        // and what `get_grocery_spend` sums. The items sum to the subtotal.
        assertEquals(12886L, success.receipt.totalCents)
        assertEquals(12084L, success.items.sumOf { it.totalPriceCents })
    }

    @Test
    fun `tax that is charged but not extracted still quarantines`() {
        // Rule 6: an omitted figure must never loosen the gate. Same receipt,
        // but the model failed to report the tax line - this is exactly the
        // shape that used to be the ONLY shape, and it must still fail.
        val raw = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "128.86",
             "items": [
               {"name": "Paper towels", "quantity": 1, "totalPrice": "60.00"},
               {"name": "Chicken thighs", "quantity": 1, "totalPrice": "40.84"},
               {"name": "Laundry detergent", "quantity": 1, "totalPrice": "20.00"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Quarantined)
    }

    @Test
    fun `a dropped item fails anchor 1 and says the subtotal did not match`() {
        val raw = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "128.86", "subtotal": "120.84", "tax": "8.02",
             "items": [
               {"name": "Paper towels", "quantity": 1, "totalPrice": "60.00"},
               {"name": "Chicken thighs", "quantity": 1, "totalPrice": "40.84"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Quarantined)
        val reason = (result as PantryIngestResult.Quarantined).reason
        assertTrue("should name the subtotal: $reason", reason.contains("subtotal"))
        assertTrue("should name the shortfall: $reason", reason.contains("20.00"))
    }

    @Test
    fun `a misread tax line fails anchor 2 and says the figures do not tie out`() {
        val raw = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "128.86", "subtotal": "120.84", "tax": "80.20",
             "items": [
               {"name": "Paper towels", "quantity": 1, "totalPrice": "60.00"},
               {"name": "Chicken thighs", "quantity": 1, "totalPrice": "40.84"},
               {"name": "Laundry detergent", "quantity": 1, "totalPrice": "20.00"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Quarantined)
        val reason = (result as PantryIngestResult.Quarantined).reason
        assertTrue("should name the total it landed at: $reason", reason.contains("201.04"))
    }

    @Test
    fun `a printed bag fee is accounted for by otherCharges`() {
        val raw = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "129.36", "subtotal": "120.84", "tax": "8.02", "otherCharges": "0.50",
             "items": [
               {"name": "Paper towels", "quantity": 1, "totalPrice": "60.00"},
               {"name": "Chicken thighs", "quantity": 1, "totalPrice": "40.84"},
               {"name": "Laundry detergent", "quantity": 1, "totalPrice": "20.00"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue("expected Success, got $result", result is PantryIngestResult.Success)
        assertEquals(12936L, (result as PantryIngestResult.Success).receipt.totalCents)
    }

    @Test
    fun `a printed coupon arrives as a negative otherCharges`() {
        val raw = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "123.86", "subtotal": "120.84", "tax": "8.02", "otherCharges": "-5.00",
             "items": [
               {"name": "Paper towels", "quantity": 1, "totalPrice": "60.00"},
               {"name": "Chicken thighs", "quantity": 1, "totalPrice": "40.84"},
               {"name": "Laundry detergent", "quantity": 1, "totalPrice": "20.00"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue("expected Success, got $result", result is PantryIngestResult.Success)
    }

    @Test
    fun `a tax field that is present but unreadable quarantines`() {
        // Distinct from tax being absent: the model saw a tax line and
        // couldn't read it, so treating it as zero would drop a real figure.
        val raw = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "128.86", "subtotal": "120.84", "tax": "8.0O",
             "items": [{"name": "Everything", "quantity": 1, "totalPrice": "120.84"}]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(result is PantryIngestResult.Quarantined)
        assertTrue((result as PantryIngestResult.Quarantined).reason.contains("tax"))
    }

    @Test
    fun `a negative tax or a non-positive subtotal quarantines`() {
        val negativeTax = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "112.82", "subtotal": "120.84", "tax": "-8.02",
             "items": [{"name": "Everything", "quantity": 1, "totalPrice": "120.84"}]}
        """.trimIndent()
        assertTrue(
            PantryReceiptAgent.parseAndReconcile(negativeTax, "/tmp/r.jpg")
                is PantryIngestResult.Quarantined
        )

        val zeroSubtotal = """
            {"store": "Walmart", "purchaseDate": "2026-08-06", "currency": "USD",
             "total": "8.02", "subtotal": "0.00", "tax": "8.02",
             "items": [{"name": "Everything", "quantity": 1, "totalPrice": "0.00"}]}
        """.trimIndent()
        assertTrue(
            PantryReceiptAgent.parseAndReconcile(zeroSubtotal, "/tmp/r.jpg")
                is PantryIngestResult.Quarantined
        )
    }

    @Test
    fun `a tax-free receipt with no subtotal still reconciles the old way`() {
        // Singapore prices are GST-inclusive and the receipts Kevin has print
        // no separate tax line, so the single-anchor branch must keep working
        // exactly as it did - this is what the five tests above it cover.
        val raw = """
            {"store": "NTUC FairPrice", "purchaseDate": "2026-07-20", "currency": "SGD",
             "total": "12.50", "subtotal": null, "tax": null, "otherCharges": null,
             "items": [
               {"name": "Milk", "quantity": 1, "totalPrice": "4.50"},
               {"name": "Bread", "quantity": 2, "totalPrice": "8.00"}
             ]}
        """.trimIndent()

        val result = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue("expected Success, got $result", result is PantryIngestResult.Success)
    }
}
