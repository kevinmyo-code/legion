package com.kevin.legion.purchases

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentences the bought log says, and the Groceries-versus-other-lists label choice (ADR 0055/0049). */
class PurchaseWordingTest {

    private val today = LocalDate.of(2026, 10, 4).toEpochDay().toInt()
    private val sep20 = LocalDate.of(2026, 9, 20).toEpochDay().toInt()

    private fun entry(item: String, by: String? = "Mia", price: Long? = null) = Purchase(
        id = item, item = item, boughtOn = sep20, loggedBy = by, loggedByMe = false, store = null,
        priceCents = price, priceNote = price?.let { "entered by hand" }, quantityNote = null,
        isPrivate = false, source = "MANUAL",
    )

    private fun match(item: String, by: String? = "Mia", exact: Boolean = true) =
        PurchaseMatch(entry(item, by), exact, 1)

    @Test
    fun `the Groceries label names the date and who, and a backfilled entry says not recorded`() {
        assertEquals("Last bought Sep 20 · Mia", GroceriesLabel.label(match("shampoo"), today))
        assertEquals(
            "Last bought Sep 20 · who not recorded",
            GroceriesLabel.label(match("shampoo", by = null), today),
        )
    }

    @Test
    fun `a loose match names its own text, never the query's`() {
        val label = GroceriesLabel.label(match("Head & Shoulders shampoo", exact = false), today)
        assertTrue(label, label.contains("\"Head & Shoulders shampoo\""))
    }

    @Test
    fun `only the Groceries list reads last bought, case and spacing aside`() {
        assertTrue(GroceriesLabel.isGroceriesList("Groceries"))
        assertTrue(GroceriesLabel.isGroceriesList("  groceries "))
        assertFalse(GroceriesLabel.isGroceriesList("Todo"))
        assertFalse(GroceriesLabel.isGroceriesList("Hardware store"))
        assertFalse(GroceriesLabel.isGroceriesList(null))
    }

    @Test
    fun `an unreadable log is said on the Groceries label, and no match is a no-record line`() {
        assertEquals(
            GroceriesLabel.UNREADABLE,
            GroceriesLabel.labelFor(PurchaseOutcome.Unreachable("x"), today),
        )
        assertEquals(
            GroceriesLabel.NO_RECORD,
            GroceriesLabel.labelFor(PurchaseOutcome.Ok(emptyList()), today),
        )
        assertFalse(GroceriesLabel.NO_RECORD.contains("never", ignoreCase = true))
    }

    @Test
    fun `last bought names the exact entry and lists several matches`() {
        val one = PurchaseWording.lastBoughtAnswer(
            "shampoo",
            listOf(match("Head & Shoulders shampoo", exact = false)),
            today,
        )
        assertEquals("You logged \"Head & Shoulders shampoo\" on Sep 20 (Mia).", one)

        val many = PurchaseWording.lastBoughtAnswer(
            "shampoo",
            listOf(match("shampoo"), match("dog shampoo", by = null, exact = false)),
            today,
        )
        assertTrue(many, many.contains("\"dog shampoo\" on Sep 20 (who logged it was not recorded)"))
    }

    @Test
    fun `no match is an absent record, never never-bought`() {
        val none = PurchaseWording.lastBoughtAnswer("conditioner", emptyList(), today)
        assertTrue(none, none.startsWith("I have no record of buying conditioner"))
        assertFalse(none.contains("never bought", ignoreCase = true))
    }

    @Test
    fun `a failed write says nothing was logged and an unreadable reply says it is unknown`() {
        assertEquals(
            "I can't reach the bought log right now, so I didn't log shampoo.",
            PurchaseFailures.logFailed(PurchaseOutcome.Unreachable("x"), "shampoo"),
        )
        assertTrue(
            PurchaseFailures.logFailed(PurchaseOutcome.Refused("blank"), "shampoo")
                .contains("I didn't log shampoo: blank"),
        )
        val unknown = PurchaseFailures.logFailed(PurchaseOutcome.Unconfirmed("x"), "shampoo")
        assertTrue(unknown.contains("can't say whether"))
    }

    @Test
    fun `a logged entry with a price says it was entered by hand`() {
        val said = PurchaseWording.logged(entry("shampoo", price = 899), today)
        assertEquals("Logged \"shampoo\" as bought on Sep 20. Price \$8.99, entered by hand.", said)
    }

    @Test
    fun `a date in another year carries the year`() {
        assertEquals("Sep 20, 2025", PurchaseWording.date(LocalDate.of(2025, 9, 20).toEpochDay().toInt(), today))
    }
}
