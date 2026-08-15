package com.kevin.legion.ledger

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.LedgerTransactionDao
import com.kevin.legion.data.local.IngestedFileDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 12 §5 - the supersede wiring is a transaction-ordering property
 * ([IngestPipeline.commit]'s three load-bearing properties documented at its
 * call site), which needs a real Room database to observe at all. Same
 * pattern as [IngestPipelineReplaceFlowTest]: this deliberately does NOT
 * call [IngestPipeline.commit] itself, because that function resolves
 * [CarDatabase.getDatabase]'s process-wide singleton, which would make this
 * test touch the app-under-test's real on-disk database rather than an
 * isolated in-memory one. Instead it runs the IDENTICAL sequence
 * [IngestPipeline.commit] runs on its `LedgerIngestResult.Success` branch
 * (minus the `staged.isReplace` step, which is [IngestPipelineReplaceFlowTest]'s
 * concern, not this one's) against a [Room.inMemoryDatabaseBuilder] instance,
 * so it exercises the real production DAO methods
 * ([LedgerTransactionDao.deleteSupersededProvisional],
 * [LedgerTransactionDao.getForAccountInRange], [resolveDedup]) without that
 * cross-contamination risk.
 *
 * Test 17 deliberately gives the provisional row and the reconciled row the
 * SAME `accountId` string, not [sameCard]-suffix-related ones. Real BofA
 * card data never does this (ticket 12 §0's whole point is that the CSV's
 * last-4 and the PDF's full printed account are different strings), but
 * [LedgerTransactionDao.getForAccountInRange] - the read [resolveDedup]'s
 * false-duplicate risk runs through - filters on EXACT `accountId` equality,
 * not a suffix relation. The ordering guarantee this test exercises is a
 * general property of [IngestPipeline.commit]'s wiring, not something that
 * only has to hold for the specific string mismatch §0 names; using matching
 * strings is what makes the failure mode actually reachable to assert
 * against, rather than testing a bug that provably cannot occur given how
 * the two real accountIds happen to differ.
 */
@RunWith(AndroidJUnit4::class)
class IngestPipelineProvisionalSupersedeTest {

    private lateinit var db: CarDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CarDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun provisionalTxn(txnDate: Long, description: String, amountCents: Long, accountId: String = "7823") =
        LedgerTransaction(
            sourceFile = "currentTransaction_$accountId.csv",
            accountId = accountId,
            currency = LedgerCurrency.USD,
            txnDate = txnDate,
            description = description,
            amountCents = amountCents,
            balanceCents = null,
            lineRef = "csv:$description",
            ingestMethod = IngestMethod.UNRECONCILED,
        )

    private fun reconciledTxn(txnDate: Long, description: String, amountCents: Long, accountId: String) =
        LedgerTransaction(
            sourceFile = "eStmt_card.pdf",
            accountId = accountId,
            currency = LedgerCurrency.USD,
            txnDate = txnDate,
            description = description,
            amountCents = amountCents,
            balanceCents = null,
            lineRef = "pdf:$description",
            ingestMethod = IngestMethod.DETERMINISTIC,
        )

    /**
     * The exact sequence [IngestPipeline.commit] runs inside `db.withTransaction`
     * on its `LedgerIngestResult.Success` branch, minus the `staged.isReplace`
     * step - see this class's own doc comment for why that step is out of
     * scope here.
     */
    private suspend fun commitLikeIngestPipeline(
        txnDao: LedgerTransactionDao,
        ingestedDao: IngestedFileDao,
        driveFileId: String,
        transactions: List<LedgerTransaction>,
    ): CommitResult {
        val stamped = transactions.map { it.copy(sourceFileId = driveFileId) }
        val accountId = stamped.first().accountId
        val minDate = stamped.minOf { it.txnDate }
        val maxDate = stamped.maxOf { it.txnDate }

        var inserted = 0
        var duplicatesSkipped = 0
        var provisionalSuperseded = 0
        db.withTransaction {
            // The guard, per IngestPipeline.commit's own doc comment: a
            // provisional (UNRECONCILED) file must never supersede anything,
            // including a prior import of itself.
            if (stamped.first().ingestMethod != IngestMethod.UNRECONCILED) {
                provisionalSuperseded = txnDao.deleteSupersededProvisional(accountId, minDate, maxDate)
            }

            val existingRows = txnDao.getForAccountInRange(accountId, minDate, maxDate)
            val enumerated = ingestedDao
                .enumeratedWindows(accountId, driveFileId, minDate, maxDate)
                .map { LedgerCoveredWindow(it.fromMs, it.toMs) }
            val resolution = resolveDedup(existingRows, stamped, enumerated)
            if (resolution.toInsert.isNotEmpty()) txnDao.insertAll(resolution.toInsert)
            inserted = resolution.toInsert.size
            duplicatesSkipped = resolution.duplicatesSkipped

            ingestedDao.upsert(
                IngestedFile(
                    driveFileId = driveFileId, treeUri = null, displayName = driveFileId,
                    sizeBytes = 100, lastModified = 1, contentSha256 = "hash-$driveFileId",
                    state = IngestState.INGESTED, accountId = accountId,
                    minTxnDate = minDate, maxTxnDate = maxDate, transactionCount = inserted,
                    firstSeenAt = 1, lastAttemptAt = 1,
                )
            )
        }
        return CommitResult(inserted, duplicatesSkipped, provisionalSuperseded)
    }

    private data class CommitResult(val inserted: Int, val duplicatesSkipped: Int, val provisionalSuperseded: Int)

    /** Test 16: commit provisional rows, then a reconciled statement covering the same window -> provisional gone, reconciled present, count correct. */
    @Test
    fun reconciledStatementSupersedesProvisionalRowsInItsWindow() = kotlinx.coroutines.runBlocking {
        val txnDao = db.ledgerTransactionDao()
        val ingestedDao = db.ingestedFileDao()

        commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-csv-1",
            listOf(
                provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L),
                provisionalTxn(JUL_10, "GREENFIELD MARKET", -1200L),
            ),
        )
        assertEquals(2, txnDao.getForAccountInRange("7823", JUL_1, JUL_10).size)

        val reconciled = commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-pdf-1",
            listOf(
                reconciledTxn(JUL_1, "NORTHWIND OUTFITTERS.COM", -4500L, "5555555555557823"),
                reconciledTxn(JUL_10, "GREENFIELD MARKET #2", -1200L, "5555555555557823"),
            ),
        )

        assertEquals(2, reconciled.provisionalSuperseded)
        assertEquals(2, reconciled.inserted)
        assertTrue("provisional rows must be gone", txnDao.getForAccountInRange("7823", JUL_1, JUL_10).isEmpty())
        assertEquals(2, txnDao.getForAccountInRange("5555555555557823", JUL_1, JUL_10).size)
    }

    /** Test 17: the reconciled statement's own rows are NOT dropped as duplicates of the provisional rows they replaced (the §5 ordering bug, asserted directly). */
    @Test
    fun reconciledRowsAreNotDroppedAsDuplicatesOfTheProvisionalRowsTheyReplace() = kotlinx.coroutines.runBlocking {
        val txnDao = db.ledgerTransactionDao()
        val ingestedDao = db.ingestedFileDao()

        // Same accountId string deliberately - see this class's doc comment
        // for why that's what makes the failure mode reachable at all.
        commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-csv-1",
            listOf(provisionalTxn(JUL_1, "SAME DESCRIPTION", -4500L, accountId = "SAMEACCT")),
        )

        val reconciled = commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-pdf-1",
            listOf(reconciledTxn(JUL_1, "SAME DESCRIPTION", -4500L, "SAMEACCT")),
        )

        // Without the supersede running BEFORE getForAccountInRange, this
        // exact-key match would have been read as "already exists" and
        // dropped - the verified row lost in favour of the unverified one
        // it was meant to replace.
        assertEquals(1, reconciled.provisionalSuperseded)
        assertEquals(1, reconciled.inserted)
        assertEquals(0, reconciled.duplicatesSkipped)
        val rows = txnDao.getForAccountInRange("SAMEACCT", JUL_1, JUL_1)
        assertEquals(1, rows.size)
        assertEquals(IngestMethod.DETERMINISTIC, rows.single().ingestMethod)
    }

    /** Test 18: a reconciled statement covering a DIFFERENT window leaves provisional rows alone. */
    @Test
    fun aReconciledStatementForADifferentWindowLeavesProvisionalRowsAlone() = kotlinx.coroutines.runBlocking {
        val txnDao = db.ledgerTransactionDao()
        val ingestedDao = db.ingestedFileDao()

        commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-csv-1",
            listOf(provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L)),
        )

        // A card PDF for an entirely earlier cycle - doesn't touch July at all.
        val reconciled = commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-pdf-june",
            listOf(reconciledTxn(JUN_5, "SOME JUNE PURCHASE", -900L, "5555555555557823")),
        )

        assertEquals(0, reconciled.provisionalSuperseded)
        assertEquals(1, txnDao.getForAccountInRange("7823", JUL_1, JUL_1).size)
    }

    /** Test 19: importing the card CSV twice does not delete-then-reinsert (the §5 guard). */
    @Test
    fun importingTheProvisionalFileTwiceNeverSupersedesItself() = kotlinx.coroutines.runBlocking {
        val txnDao = db.ledgerTransactionDao()
        val ingestedDao = db.ingestedFileDao()

        commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-csv-1",
            listOf(provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L)),
        )

        // Re-importing the same provisional file (e.g. a re-scan before its
        // window has anything reconciled yet) must never trip the supersede
        // guard against its own prior rows.
        val second = commitLikeIngestPipeline(
            txnDao, ingestedDao, "card-csv-1",
            listOf(provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L)),
        )

        assertEquals(0, second.provisionalSuperseded)
        // Ordinary exact-match dedup still applies - the identical row is
        // recognized as already on file, not duplicated.
        assertEquals(0, second.inserted)
        assertEquals(1, second.duplicatesSkipped)
        assertEquals(1, txnDao.getForAccountInRange("7823", JUL_1, JUL_1).size)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val JUN_5 = 1_749_081_600_000L
        private const val JUL_1 = JUN_5 + 26 * DAY_MS
        private const val JUL_10 = JUL_1 + 9 * DAY_MS
    }
}
