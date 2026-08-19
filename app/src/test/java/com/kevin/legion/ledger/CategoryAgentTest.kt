package com.kevin.legion.ledger

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CategoryAgent.guessBatch] with an empty input - the one path that needs no network call and is
 * safe to test without mocking [com.kevin.legion.ai.SubAgent]/Gemini. The batch parsing/validation
 * logic downstream of a real response is exercised implicitly by [CategorizationTest]'s coverage
 * of the pure pieces it shares (`allowed` set membership is a plain `Set.contains`); a full
 * network-backed guess is out of scope for a JVM unit test, same posture
 * [LedgerStatementAgentTest] already takes with [LedgerStatementAgent.extract].
 *
 * Runs under Robolectric, not plain JUnit - the degenerate-list guard below logs via
 * `android.util.Log.w`, which throws `RuntimeException("not mocked")` off-device without
 * Robolectric's shadow, same reasoning [LedgerAddCategoryTest]/[LedgerStatementAgentTest] already
 * follow.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryAgentTest {

    @Test
    fun `an empty merchant list never calls out - returns immediately with no tokens spent`() = runBlocking {
        val outcome = CategoryAgent.guessBatch(emptyList(), listOf("Groceries", "Dining Out"))
        assertTrue(outcome.guesses.isEmpty())
        assertTrue(outcome.promptTokens == null)
        assertTrue(outcome.responseTokens == null)
    }

    /**
     * The degenerate-list guard (2026-08-13, CLAUDE.md §4 rule 6) - the exact shape of the fresh-
     * install seeding bug that put 497 of Kevin's ledger_transactions rows under a single leftover
     * `Pets` category (see MIGRATION_16_17's doc comment). A one-item list is not a real choice, so
     * [CategoryAgent.guessBatch] must refuse before ever calling out, same as the empty-list case
     * above - no network mocking needed since this path never reaches SubAgent.
     */
    @Test
    fun `a single-category list is refused - it cannot be a real choice, only a seeding bug`() = runBlocking {
        val outcome = CategoryAgent.guessBatch(listOf("STARBUCKS #123"), listOf("Pets"))
        assertTrue(outcome.guesses.isEmpty())
        assertTrue(outcome.promptTokens == null)
        assertTrue(outcome.responseTokens == null)
    }

    @Test
    fun `an empty category list is refused the same way`() = runBlocking {
        val outcome = CategoryAgent.guessBatch(listOf("STARBUCKS #123"), emptyList())
        assertTrue(outcome.guesses.isEmpty())
        assertTrue(outcome.promptTokens == null)
        assertTrue(outcome.responseTokens == null)
    }
}
