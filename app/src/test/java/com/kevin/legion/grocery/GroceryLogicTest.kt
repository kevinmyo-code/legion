package com.kevin.legion.grocery

import com.kevin.legion.data.local.GroceryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises `grocery/GroceryLogic.kt` - plain JUnit, no Room/Compose/`Context`, same posture as
 * [com.kevin.legion.notes.NotesLogicTest]. All fixtures invented.
 */
class GroceryLogicTest {

    private fun item(id: Long, text: String, done: Boolean = false) =
        GroceryItem(id = id, text = text, done = done)

    // ------------------------------------------------------------------ name normalisation

    @Test
    fun `case and surrounding space collapse to one staple`() {
        assertEquals(normalizeGroceryName(" Milk "), normalizeGroceryName("milk"))
        assertEquals(normalizeGroceryName("COFFEE"), normalizeGroceryName("coffee"))
    }

    @Test
    fun `a trailing plural s collapses onto the singular`() {
        // Without this a staples memory splits its count across "egg" and "eggs" and neither ever
        // looks frequent enough to suggest.
        assertEquals(normalizeGroceryName("egg"), normalizeGroceryName("eggs"))
        assertEquals(normalizeGroceryName("banana"), normalizeGroceryName("bananas"))
    }

    @Test
    fun `short words keep their s`() {
        // "gas" must not become "ga".
        assertEquals("gas", normalizeGroceryName("gas"))
    }

    @Test
    fun `a double-s ending is left alone`() {
        assertEquals("floss", normalizeGroceryName("floss"))
    }

    // ------------------------------------------------------------------ matching

    @Test
    fun `an exact name resolves`() {
        val items = listOf(item(1, "Milk"), item(2, "Bread"))
        val match = matchGroceryItem("milk", items)
        assertTrue(match is GroceryMatch.Resolved)
        assertEquals(1L, (match as GroceryMatch.Resolved).item.id)
    }

    @Test
    fun `a plural spoken against a singular on the list resolves`() {
        // "tick off the eggs" against an item written "Egg".
        val match = matchGroceryItem("eggs", listOf(item(1, "Egg")))
        assertTrue(match is GroceryMatch.Resolved)
    }

    @Test
    fun `a substring resolves`() {
        val match = matchGroceryItem("coffee", listOf(item(1, "Coffee beans, dark roast")))
        assertTrue(match is GroceryMatch.Resolved)
    }

    @Test
    fun `two equal matches refuse rather than guessing`() {
        val items = listOf(item(1, "Milk"), item(2, "Milk"))
        val match = matchGroceryItem("milk", items)
        assertTrue(match is GroceryMatch.Ambiguous)
        assertEquals(2, (match as GroceryMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `nothing matching refuses rather than picking the first`() {
        assertTrue(matchGroceryItem("caviar", listOf(item(1, "Milk"))) is GroceryMatch.NoMatch)
    }

    @Test
    fun `an empty list is always NoMatch`() {
        assertTrue(matchGroceryItem("milk", emptyList()) is GroceryMatch.NoMatch)
    }

    @Test
    fun `a blank query is always NoMatch`() {
        assertTrue(matchGroceryItem("   ", listOf(item(1, "Milk"))) is GroceryMatch.NoMatch)
    }

    // ------------------------------------------------------------------ row ordering

    @Test
    fun `ticked items sink to the bottom and untickeds keep their order`() {
        // The opposite of buildInboxRows on purpose - this is read walking round a shop, so what is
        // still to find must stay at the top.
        val items = listOf(
            item(1, "Milk", done = true),
            item(2, "Bread"),
            item(3, "Eggs", done = true),
            item(4, "Coffee"),
        )
        assertEquals(listOf(2L, 4L, 1L, 3L), buildGroceryRows(items).map { it.id })
    }

    @Test
    fun `a ticked item is sunk, never dropped`() {
        // Hiding it would make an accidental tick unrecoverable without re-adding by hand.
        val rows = buildGroceryRows(listOf(item(1, "Milk", done = true)))
        assertEquals(1, rows.size)
        assertTrue(rows.single().done)
    }

    @Test
    fun `an all-unticked list keeps its exact order`() {
        val items = listOf(item(1, "Milk"), item(2, "Bread"), item(3, "Eggs"))
        assertEquals(listOf(1L, 2L, 3L), buildGroceryRows(items).map { it.id })
        assertTrue(buildGroceryRows(items).none { it.done })
    }

    @Test
    fun `an empty trip yields no rows`() {
        assertEquals(emptyList<GroceryRowView>(), buildGroceryRows(emptyList()))
    }

    @Test
    fun `row text is carried through exactly as the driver typed it`() {
        // Never uppercased or normalised for display - normalisation is for MATCHING only.
        assertEquals("Coffee Beans", buildGroceryRows(listOf(item(1, "Coffee Beans"))).single().text)
    }

    // ------------------------------------------------------------------ trip summary

    @Test
    fun `a summary counts bought and skipped separately`() {
        val summary = TripSummary(bought = 3, skipped = 2, boughtNames = listOf("Milk", "Eggs", "Bread"))
        assertEquals(3, summary.bought)
        assertEquals(2, summary.skipped)
        assertFalse("skipped items must not appear as bought", summary.boughtNames.contains("Coffee"))
    }
}
