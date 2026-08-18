package com.kevin.legion.ui.ledger

import com.kevin.legion.ledger.CategoryGuessResult
import com.kevin.legion.ledger.UncategorizedMerchants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LedgerCategoryResolver]'s 2026-08-18 fix - the "it says nothing to categorize" bug
 * (`CategorizeDrilldownScreen`'s old empty-state check never looked at `category IS NULL` rows at
 * all). [categorizeEmptyStateSentence] is the one function that decides whether the placeholder
 * sentence renders; [categorizeEmptyStateSentenceNeverFiresWithRealWork] below is the regression
 * test for the actual bug - it must FAIL against the old `pending.isEmpty() && guesses.isEmpty()`
 * check (which this file does not re-implement, on purpose - the whole point is that the real
 * function must never again agree with that check).
 */
class LedgerCategoryResolverTest {

    // ---- categorizeEmptyStateSentence: the regression test for the actual bug -----------------

    @Test
    fun `the empty-state sentence CANNOT appear while real uncategorised rows exist`() {
        // Exactly Kevin's reported shape: zero pending, zero guesses, but 22 real uncategorised
        // rows on file - the old check (`pending.isEmpty() && categoryGuesses.isEmpty()`) would
        // have said "Nothing to categorize right now." here. This must not.
        val sentence = LedgerCategoryResolver.categorizeEmptyStateSentence(
            pendingCount = 0,
            categoryGuessCount = 0,
            uncategorizedRealCount = 22,
            uncategorizedTransfersCount = 22,
        )
        assertNull("a sentence claiming 'nothing to categorize' must never render while 22 real rows are uncategorised", sentence)
    }

    @Test
    fun `still null when only pending or only guesses are non-empty, even with zero uncategorised`() {
        assertNull(LedgerCategoryResolver.categorizeEmptyStateSentence(1, 0, 0, 0))
        assertNull(LedgerCategoryResolver.categorizeEmptyStateSentence(0, 1, 0, 0))
    }

    @Test
    fun `every list truly empty reads a plain 'nothing to categorize'`() {
        assertEquals(
            "Nothing to categorize right now.",
            LedgerCategoryResolver.categorizeEmptyStateSentence(0, 0, 0, 0),
        )
    }

    @Test
    fun `transfers-only leaves nothing that NEEDS a category, but says so honestly rather than 'nothing'`() {
        val sentence = LedgerCategoryResolver.categorizeEmptyStateSentence(
            pendingCount = 0, categoryGuessCount = 0, uncategorizedRealCount = 0, uncategorizedTransfersCount = 22,
        )
        assertNotNull(sentence)
        assertEquals(
            "Nothing left that needs a category. 22 transfer-shaped rows stay uncategorised on " +
                "purpose - excluded from spend, never a category.",
            sentence,
        )
    }

    @Test
    fun `a single transfer is grammatical`() {
        val sentence = LedgerCategoryResolver.categorizeEmptyStateSentence(0, 0, 0, 1)
        assertEquals(
            "Nothing left that needs a category. 1 transfer-shaped row stays uncategorised on " +
                "purpose - excluded from spend, never a category.",
            sentence,
        )
    }

    // ---- rulesRunSentence -----------------------------------------------------------------------

    @Test
    fun `rules run sentence reports zero, one, and many distinctly`() {
        assertEquals("Rules matched nothing new.", LedgerCategoryResolver.rulesRunSentence(0))
        assertEquals("Rules fixed 1 row.", LedgerCategoryResolver.rulesRunSentence(1))
        assertEquals("Rules fixed 11 rows.", LedgerCategoryResolver.rulesRunSentence(11))
    }

    // ---- guessPoolSentence ------------------------------------------------------------------------

    @Test
    fun `guess pool sentence states the transfer count skipped, never silently`() {
        val pool = UncategorizedMerchants(keys = listOf("CHEVRON", "PETCO"), transfersSkipped = 22)
        assertEquals(
            "2 merchants still need a category. (22 transfer-shaped rows skipped, never guessed)",
            LedgerCategoryResolver.guessPoolSentence(pool, hasGeminiKey = true),
        )
    }

    @Test
    fun `guess pool sentence names the missing key rather than silently doing nothing`() {
        val pool = UncategorizedMerchants(keys = listOf("CHEVRON"), transfersSkipped = 0)
        assertEquals(
            "1 merchant still needs a category, but no Gemini key is set up - add one in Settings to guess.",
            LedgerCategoryResolver.guessPoolSentence(pool, hasGeminiKey = false),
        )
    }

    @Test
    fun `guess pool sentence for an empty pool still discloses any transfers skipped`() {
        val pool = UncategorizedMerchants(keys = emptyList(), transfersSkipped = 3)
        assertEquals(
            "Nothing left to guess. (3 transfer-shaped rows skipped, never guessed)",
            LedgerCategoryResolver.guessPoolSentence(pool, hasGeminiKey = true),
        )
    }

    // ---- guessResultSentence ---------------------------------------------------------------------

    @Test
    fun `guess result sentence reports the zero case explicitly - a missing key or network failure must never look identical to a real run`() {
        assertEquals(
            "The model returned no usable guesses - nothing changed.",
            LedgerCategoryResolver.guessResultSentence(CategoryGuessResult(rowsCategorized = 0, merchantsCategorized = 0)),
        )
    }

    @Test
    fun `guess result sentence reports merchants and rows separately when they differ`() {
        assertEquals(
            "Guessed 2 categories, covering 5 rows.",
            LedgerCategoryResolver.guessResultSentence(CategoryGuessResult(rowsCategorized = 5, merchantsCategorized = 2)),
        )
    }
}
