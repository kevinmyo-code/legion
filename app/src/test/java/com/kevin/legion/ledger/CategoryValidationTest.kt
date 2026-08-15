package com.kevin.legion.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [validateNewCategoryName] - Kevin adding a category by hand (2026-08-07), a different door than
 * `set_category`'s D14 fixed-list boundary. Plain JUnit, no Room/Robolectric: this function is pure
 * by construction, same shape [LedgerDedupTest] documents for [resolveDedup].
 */
class CategoryValidationTest {
    private val existing = listOf("Groceries", "Dining Out", "Pets")

    @Test
    fun `a genuinely new name is valid and trimmed`() {
        val result = validateNewCategoryName("  Hobbies  ", existing)
        assertTrue(result is NewCategoryValidation.Valid)
        assertEquals("Hobbies", (result as NewCategoryValidation.Valid).trimmed)
    }

    @Test
    fun `blank input is rejected in words`() {
        val result = validateNewCategoryName("   ", existing)
        assertTrue(result is NewCategoryValidation.Invalid)
        assertEquals("Category name can't be blank.", (result as NewCategoryValidation.Invalid).reason)
    }

    @Test
    fun `an exact-case duplicate is rejected`() {
        val result = validateNewCategoryName("Pets", existing)
        assertTrue(result is NewCategoryValidation.Invalid)
        assertEquals("\"Pets\" already exists.", (result as NewCategoryValidation.Invalid).reason)
    }

    @Test
    fun `a case-insensitive duplicate is rejected the same as an exact one - Pets vs pets`() {
        val result = validateNewCategoryName("pets", existing)
        assertTrue(result is NewCategoryValidation.Invalid)
    }

    @Test
    fun `a duplicate check is whitespace-insensitive too - untrimmed input still matches`() {
        val result = validateNewCategoryName("  PETS  ", existing)
        assertTrue(result is NewCategoryValidation.Invalid)
    }

    @Test
    fun `a name at exactly the length cap is valid`() {
        val exactly40 = "A".repeat(MAX_CATEGORY_NAME_LENGTH)
        val result = validateNewCategoryName(exactly40, existing)
        assertTrue(result is NewCategoryValidation.Valid)
    }

    @Test
    fun `a name one character over the cap is rejected in words`() {
        val tooLong = "A".repeat(MAX_CATEGORY_NAME_LENGTH + 1)
        val result = validateNewCategoryName(tooLong, existing)
        assertTrue(result is NewCategoryValidation.Invalid)
        assertEquals(
            "Category name is too long (max $MAX_CATEGORY_NAME_LENGTH characters).",
            (result as NewCategoryValidation.Invalid).reason,
        )
    }

    @Test
    fun `an empty existing list never crashes and any non-blank name is valid`() {
        val result = validateNewCategoryName("Anything", emptyList())
        assertTrue(result is NewCategoryValidation.Valid)
    }
}
