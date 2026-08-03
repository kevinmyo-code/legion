package com.kevin.legion.ui.pantry

import com.kevin.legion.data.local.PantryLineItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [formatMacros] - ticket 09 resolution §2's
 * segregated estimate block. No Room, no Android dependency, plain JVM test.
 */
class PantryRowsTest {
    private fun item(
        kcal: Int? = null,
        protein: Double? = null,
        carbs: Double? = null,
        fat: Double? = null,
    ) = PantryLineItem(
        receiptId = 1, name = "test item", totalPriceCents = 100,
        caloriesKcal = kcal, proteinG = protein, carbsG = carbs, fatG = fat,
    )

    @Test
    fun `all four fields present`() {
        assertEquals(
            "610 kcal - 32P 48C 32F",
            formatMacros(item(kcal = 610, protein = 32.0, carbs = 48.0, fat = 32.0)),
        )
    }

    @Test
    fun `grams round to the nearest whole number`() {
        assertEquals("1090 kcal - 205P 0C 24F", formatMacros(item(kcal = 1090, protein = 204.6, carbs = 0.3, fat = 23.9)))
    }

    @Test
    fun `no calories but some grams present - grams only, no dangling dash`() {
        assertEquals("13P 2F", formatMacros(item(protein = 13.0, fat = 2.0)))
    }

    @Test
    fun `calories present but no grams - calories only, no dangling dash`() {
        assertEquals("104 kcal", formatMacros(item(kcal = 104)))
    }

    @Test
    fun `nothing extracted for this item reads as an honest 'no estimate', never a blank or a zero`() {
        assertEquals("no estimate", formatMacros(item()))
    }
}
