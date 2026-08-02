package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [resolveDedup] against the eight cases specified in
 * `.scratch/ledger-drive-ingestion/issues/04-twin-transactions.md`'s
 * resolution §7. Deliberately a plain JUnit test - no Room, no Robolectric,
 * no Android - because [resolveDedup] is pure by construction (see its own
 * doc comment for why). Case 7 (a replace resetting an overlapping file back
 * to `NEW`) is NOT here; see the class doc comment below for why.
 *
 * Every fixture below carries a distinct [LedgerTransaction.lineRef] and
 * [LedgerTransaction.sourceFile] even where the dedup key matches, which is
 * the point: [resolveDedup] must ignore both and compare only
 * `(accountId, txnDate, amountCents, normalizedDescription)`.
 */
class LedgerDedupTest {

    private var nextLineRef = 0

    /** One transaction fixture. Every field defaults to something distinct so an accidental match is never silent. */
    private fun txn(
        accountId: String = "acc-1",
        txnDate: Long = DAY_1,
        amountCents: Long = -500L,
        description: String = "COFFEE SHOP",
        sourceFile: String = "statement.pdf",
    ) = LedgerTransaction(
        sourceFile = sourceFile,
        accountId = accountId,
        currency = LedgerCurrency.USD,
        txnDate = txnDate,
        description = description,
        amountCents = amountCents,
        lineRef = "line-${nextLineRef++}",
        ingestMethod = IngestMethod.DETERMINISTIC,
    )

    @Test
    fun `case 1 - two identical lines in one statement both insert`() {
        val incoming = listOf(txn(), txn())
        val resolution = resolveDedup(existing = emptyList(), incoming = incoming)

        assertEquals(2, resolution.toInsert.size)
        assertEquals(0, resolution.duplicatesSkipped)
    }

    @Test
    fun `case 2 - the same statement imported twice inserts nothing the second time`() {
        val firstImport = listOf(txn(), txn())
        // Simulate the two coffees already committed from the first import.
        val secondImport = listOf(txn(), txn())

        val resolution = resolveDedup(existing = firstImport, incoming = secondImport)

        assertEquals(0, resolution.toInsert.size)
        assertEquals(2, resolution.duplicatesSkipped)
    }

    @Test
    fun `case 3 - a YTD statement fully overlapping a monthly statement inserts nothing`() {
        val monthlyAlreadyCommitted = listOf(
            txn(description = "COFFEE SHOP", amountCents = -500L),
            txn(description = "GROCERY STORE", amountCents = -8000L),
        )
        val ytdRestating = listOf(
            txn(description = "COFFEE SHOP", amountCents = -500L, sourceFile = "ytd.pdf"),
            txn(description = "GROCERY STORE", amountCents = -8000L, sourceFile = "ytd.pdf"),
        )

        val resolution = resolveDedup(existing = monthlyAlreadyCommitted, incoming = ytdRestating)

        assertEquals(0, resolution.toInsert.size)
        assertEquals(2, resolution.duplicatesSkipped)
    }

    @Test
    fun `case 4 - monthly has 2 of a tuple, YTD has 3, exactly 1 inserts`() {
        val monthlyAlreadyCommitted = listOf(txn(), txn())
        val ytdRestatingPlusOneNew = listOf(txn(sourceFile = "ytd.pdf"), txn(sourceFile = "ytd.pdf"), txn(sourceFile = "ytd.pdf"))

        val resolution = resolveDedup(existing = monthlyAlreadyCommitted, incoming = ytdRestatingPlusOneNew)

        assertEquals(1, resolution.toInsert.size)
        assertEquals(2, resolution.duplicatesSkipped)
    }

    @Test
    fun `case 5 - whitespace and case differences in description still match`() {
        val existing = listOf(txn(description = "COFFEE  SHOP #42"))
        val incoming = listOf(txn(description = "  coffee shop   #42  "))

        val resolution = resolveDedup(existing = existing, incoming = incoming)

        assertEquals(0, resolution.toInsert.size)
        assertEquals(1, resolution.duplicatesSkipped)
    }

    @Test
    fun `case 6 - same date and amount but a different merchant both insert`() {
        val existing = listOf(txn(description = "COFFEE SHOP"))
        val incoming = listOf(txn(description = "GAS STATION"))

        val resolution = resolveDedup(existing = existing, incoming = incoming)

        assertEquals(1, resolution.toInsert.size)
        assertEquals(0, resolution.duplicatesSkipped)
        assertEquals("GAS STATION", resolution.toInsert.single().description)
    }

    @Test
    fun `case 8 - duplicatesSkipped after a partial-overlap import equals the actual number skipped`() {
        val existing = listOf(
            txn(description = "COFFEE SHOP", amountCents = -500L),
            txn(description = "GROCERY STORE", amountCents = -8000L),
        )
        val incoming = listOf(
            txn(description = "COFFEE SHOP", amountCents = -500L, sourceFile = "ytd.pdf"), // duplicate
            txn(description = "GROCERY STORE", amountCents = -8000L, sourceFile = "ytd.pdf"), // duplicate
            txn(description = "GAS STATION", amountCents = -3000L, sourceFile = "ytd.pdf"), // new
        )

        val resolution = resolveDedup(existing = existing, incoming = incoming)

        assertEquals(1, resolution.toInsert.size)
        assertEquals(2, resolution.duplicatesSkipped)
        assertEquals(incoming.size, resolution.toInsert.size + resolution.duplicatesSkipped)
    }

    // Case 7 (replace a file overlapping another INGESTED file resets it to
    // NEW) is deliberately NOT a test here, and not anywhere else in this
    // change either. `IngestedFileDao.resetOverlapping` already exists (Part
    // 1) and is a raw Room `@Query` UPDATE, not something `resolveDedup`
    // touches - this ticket's scope is the transaction-level dedup rewrite,
    // not the replace flow that would call `resetOverlapping`. That flow
    // doesn't exist yet (it belongs to the folder-scan pipeline, ticket 03,
    // explicitly out of scope here: "no scanner, no dispatcher split, no UI,
    // no sync"). Even once it exists, exercising `resetOverlapping` needs a
    // real Room database - `room-testing` is only wired as
    // `androidTestImplementation` in this module (see app/build.gradle.kts),
    // not `testImplementation`, so `./gradlew testDebugUnitTest` cannot run a
    // Room-backed query at all today. That makes case 7 an androidTest, not a
    // JVM one, on infrastructure grounds independent of this ticket's scope.

    companion object {
        private const val DAY_1 = 1_700_000_000_000L
    }
}
