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

    // ---------------------------------------------------- the overlap pass (2026-08-04)

    /**
     * Kevin's real folder, reduced: the July checking PDF covers 06/05-07/06,
     * the mid-cycle CSV covers 07/01-07/31, and BofA words the same -$8.99 on
     * 07/06 differently in each export. Before the second pass this row was
     * counted twice, every month, by construction.
     */
    @Test
    fun `a transaction the PDF worded differently is not counted a second time from the CSV`() {
        val fromThePdf = listOf(
            txn(txnDate = JUL_6, amountCents = -899L, description = "PURCHASE   0706 VPN24.ME EDINBURGH    00"),
        )
        val fromTheCsv = listOf(
            txn(txnDate = JUL_6, amountCents = -899L, description = "VPN24.ME 07/06 PURCHASE EDINBURGH 00", sourceFile = "july.csv"),
        )

        val resolution = resolveDedup(
            existing = fromThePdf,
            incoming = fromTheCsv,
            enumeratedWindows = listOf(LedgerCoveredWindow(JUN_5, JUL_6)),
        )

        assertEquals(0, resolution.toInsert.size)
        assertEquals(1, resolution.duplicatesSkipped)
        assertEquals(1, resolution.restatementsSkipped)
    }

    @Test
    fun `the same row with no window covering it still inserts - the relaxation is not global`() {
        val fromThePdf = listOf(
            txn(txnDate = JUL_6, amountCents = -899L, description = "PURCHASE   0706 VPN24.ME EDINBURGH    00"),
        )
        val fromTheCsv = listOf(
            txn(txnDate = JUL_6, amountCents = -899L, description = "VPN24.ME 07/06 PURCHASE EDINBURGH 00", sourceFile = "july.csv"),
        )

        val resolution = resolveDedup(existing = fromThePdf, incoming = fromTheCsv)

        assertEquals(1, resolution.toInsert.size)
        assertEquals(0, resolution.restatementsSkipped)
    }

    @Test
    fun `a CSV row PAST the PDF's window is new data and must survive`() {
        val fromThePdf = listOf(txn(txnDate = JUL_6, amountCents = -899L, description = "PURCHASE 0706 VPN24.ME"))
        val fromTheCsv = listOf(
            txn(txnDate = JUL_6, amountCents = -899L, description = "VPN24.ME 07/06 PURCHASE", sourceFile = "july.csv"),
            // Same merchant, same amount, a fortnight later - outside anything
            // the PDF attested to. Dropping this would be silent data loss.
            txn(txnDate = JUL_20, amountCents = -899L, description = "VPN24.ME 07/20 PURCHASE", sourceFile = "july.csv"),
        )

        val resolution = resolveDedup(
            existing = fromThePdf,
            incoming = fromTheCsv,
            enumeratedWindows = listOf(LedgerCoveredWindow(JUN_5, JUL_6)),
        )

        assertEquals(1, resolution.toInsert.size)
        assertEquals(JUL_20, resolution.toInsert.single().txnDate)
        assertEquals(1, resolution.restatementsSkipped)
    }

    /**
     * The guarantee that makes the loose key safe: a statement that covers a
     * day enumerated EVERYTHING on that day, so two real same-day, same-amount
     * purchases are two existing rows, two loose credits, and exactly two
     * drops - never three.
     */
    @Test
    fun `two genuine twins inside a covered window are dropped exactly twice, not three times`() {
        val pdfListedBoth = listOf(
            txn(txnDate = JUL_6, amountCents = -450L, description = "COFFEE 0706"),
            txn(txnDate = JUL_6, amountCents = -450L, description = "COFFEE 0706"),
        )
        val csvRestatesBothPlusOneNew = listOf(
            txn(txnDate = JUL_6, amountCents = -450L, description = "COFFEE SHOP 07/06", sourceFile = "july.csv"),
            txn(txnDate = JUL_6, amountCents = -450L, description = "COFFEE SHOP 07/06", sourceFile = "july.csv"),
            txn(txnDate = JUL_6, amountCents = -450L, description = "COFFEE SHOP 07/06", sourceFile = "july.csv"),
        )

        val resolution = resolveDedup(
            existing = pdfListedBoth,
            incoming = csvRestatesBothPlusOneNew,
            enumeratedWindows = listOf(LedgerCoveredWindow(JUN_5, JUL_6)),
        )

        assertEquals(1, resolution.toInsert.size)
        assertEquals(2, resolution.restatementsSkipped)
    }

    @Test
    fun `an exact match inside a window is counted as a duplicate, not as a restatement`() {
        val existing = listOf(txn(txnDate = JUL_6, description = "COFFEE SHOP"))
        val incoming = listOf(txn(txnDate = JUL_6, description = "COFFEE SHOP", sourceFile = "july.csv"))

        val resolution = resolveDedup(
            existing = existing,
            incoming = incoming,
            enumeratedWindows = listOf(LedgerCoveredWindow(JUN_5, JUL_6)),
        )

        assertEquals(0, resolution.toInsert.size)
        assertEquals(1, resolution.duplicatesSkipped)
        assertEquals(0, resolution.restatementsSkipped)
    }

    /**
     * One committed row can absorb one incoming row, never two. Without the
     * shared credit pool the exact match would spend the strict credit and the
     * differently-worded row would then spend a loose credit for the SAME
     * committed transaction, dropping a row that has nothing behind it.
     */
    @Test
    fun `one existing row cannot absorb both an exact match and a restatement`() {
        val existing = listOf(txn(txnDate = JUL_6, amountCents = -899L, description = "VPN24 0706"))
        val incoming = listOf(
            txn(txnDate = JUL_6, amountCents = -899L, description = "VPN24 0706", sourceFile = "july.csv"),
            txn(txnDate = JUL_6, amountCents = -899L, description = "VPN24.ME 07/06", sourceFile = "july.csv"),
        )

        val resolution = resolveDedup(
            existing = existing,
            incoming = incoming,
            enumeratedWindows = listOf(LedgerCoveredWindow(JUN_5, JUL_6)),
        )

        assertEquals(1, resolution.toInsert.size)
        assertEquals("VPN24.ME 07/06", resolution.toInsert.single().description)
        assertEquals(1, resolution.duplicatesSkipped)
        assertEquals(0, resolution.restatementsSkipped)
    }

    @Test
    fun `a window's boundaries are inclusive at both ends`() {
        val window = LedgerCoveredWindow(JUN_5, JUL_6)
        assertEquals(true, JUN_5 in window)
        assertEquals(true, JUL_6 in window)
        assertEquals(false, JUN_5 - 1 in window)
        assertEquals(false, JUL_6 + 1 in window)
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

    // ---------------------------------------------------- ticket 12: sameCard / resolveDedup unchanged

    /** Test 14: sameCard - exact match true; suffix match true; mismatched suffix false; short strings don't throw. */
    @Test
    fun `sameCard - exact, suffix-matched, mismatched, and short-string cases`() {
        assertEquals(true, sameCard("4146", "4146"))
        assertEquals(true, sameCard("4146", "4111111111114146"))
        assertEquals(true, sameCard("4111111111114146", "4146"))
        assertEquals(false, sameCard("4146", "1234"))
        // Strings under 4 chars must not throw (takeLast(4) on a short
        // string just returns the whole string, but the length guard means
        // neither branch even reaches takeLast for these).
        assertEquals(false, sameCard("12", "4146"))
        assertEquals(false, sameCard("4146", "1"))
        assertEquals(true, sameCard("", ""))
    }

    /** Test 15: resolveDedup is unchanged by this ticket - an UNRECONCILED row and a DETERMINISTIC row with the same key still dedup exactly as before. */
    @Test
    fun `resolveDedup still dedups an UNRECONCILED row against a DETERMINISTIC one sharing the same key`() {
        val provisional = LedgerTransaction(
            sourceFile = "currentTransaction_4146.csv",
            accountId = "acc-1",
            currency = LedgerCurrency.USD,
            txnDate = DAY_1,
            description = "COFFEE SHOP",
            amountCents = -500L,
            balanceCents = null,
            lineRef = "csv-line",
            ingestMethod = IngestMethod.UNRECONCILED,
        )
        val reconciled = txn(description = "COFFEE SHOP", amountCents = -500L, sourceFile = "eStmt.pdf")

        val resolution = resolveDedup(existing = listOf(provisional), incoming = listOf(reconciled))

        assertEquals(0, resolution.toInsert.size)
        assertEquals(1, resolution.duplicatesSkipped)
    }

    companion object {
        private const val DAY_1 = 1_700_000_000_000L

        // The 2026-08-04 overlap cases. Only their ORDER matters
        // (JUN_5 < JUL_6 < JUL_20); nothing here parses or formats them.
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val JUN_5 = 1_749_081_600_000L
        private const val JUL_6 = JUN_5 + 31 * DAY_MS
        private const val JUL_20 = JUL_6 + 14 * DAY_MS
    }
}
