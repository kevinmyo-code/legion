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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 04 case 7 - "Replace a file overlapping another INGESTED file:
 * overlapping file resets to NEW; rows restored on rescan" - deferred from
 * Part 2 because `room-testing` is `androidTestImplementation` only (see
 * `app/build.gradle.kts`) and no replace flow existed yet. It exists now
 * ([IngestPipeline.commit]'s `staged.isReplace` branch), so this exercises
 * the exact two-statement sequence that flow runs (`LedgerTransactionDao
 * .deleteBySourceFileId` + `IngestedFileDao.resetOverlapping`, in one Room
 * transaction) against a real in-memory Room database - a plain JVM unit
 * test cannot run either query at all.
 *
 * Deliberately does NOT go through [IngestPipeline.commit] itself: that
 * function resolves its `CarDatabase` via [CarDatabase.getDatabase]'s
 * process-wide singleton, which would make this test touch the same on-disk
 * database file the app under test uses rather than an isolated in-memory
 * one. This test instead runs the identical two-query sequence
 * `IngestPipeline.commit` runs on its replace-flow branch, against an
 * isolated [Room.inMemoryDatabaseBuilder] instance, so it verifies the real
 * production DAO methods without that cross-contamination risk.
 */
@RunWith(AndroidJUnit4::class)
class IngestPipelineReplaceFlowTest {

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

    private fun txn(sourceFileId: String, txnDate: Long, description: String, amountCents: Long) = LedgerTransaction(
        sourceFile = "$sourceFileId.pdf",
        accountId = ACCOUNT_ID,
        currency = LedgerCurrency.USD,
        txnDate = txnDate,
        description = description,
        amountCents = amountCents,
        lineRef = "$sourceFileId:$description",
        ingestMethod = IngestMethod.DETERMINISTIC,
        sourceFileId = sourceFileId,
    )

    @Test
    fun replacingAFileResetsAnOverlappingIngestedFileToNew() = kotlinx.coroutines.runBlocking {
        val ingestedDao = db.ingestedFileDao()
        val txnDao = db.ledgerTransactionDao()

        // The monthly statement (about to be replaced) and a YTD statement
        // that restates the same two transactions - ticket 04's routine
        // overlapping-restatement case, both already INGESTED.
        val monthlyRows = listOf(
            txn("monthly-v1", DAY_5, "COFFEE SHOP", -500L),
            txn("monthly-v1", DAY_10, "GROCERY STORE", -8000L),
        )
        txnDao.insertAll(monthlyRows)
        ingestedDao.upsert(
            IngestedFile(
                driveFileId = "monthly-v1", treeUri = "tree-1", displayName = "monthly.pdf",
                sizeBytes = 100, lastModified = 1_000, contentSha256 = "hash-v1",
                state = IngestState.INGESTED, accountId = ACCOUNT_ID,
                minTxnDate = DAY_5, maxTxnDate = DAY_10, transactionCount = 2,
                firstSeenAt = 1, lastAttemptAt = 1,
            )
        )
        ingestedDao.upsert(
            IngestedFile(
                driveFileId = "ytd", treeUri = "tree-1", displayName = "ytd.pdf",
                sizeBytes = 500, lastModified = 2_000, contentSha256 = "hash-ytd",
                state = IngestState.INGESTED, accountId = ACCOUNT_ID,
                minTxnDate = DAY_1, maxTxnDate = DAY_10, transactionCount = 0, // per-tuple dedup: contributed zero net rows
                firstSeenAt = 2, lastAttemptAt = 2,
            )
        )

        // The monthly file gets replaced in place (corrected export, size/mtime
        // changed) - the exact sequence IngestPipeline.commit's isReplace
        // branch runs, captured against the OLD file's own committed window
        // before anything is deleted.
        db.withTransaction {
            txnDao.deleteBySourceFileId("monthly-v1")
            ingestedDao.resetOverlapping(
                accountId = ACCOUNT_ID, fileId = "monthly-v1",
                replacedMin = DAY_5, replacedMax = DAY_10,
            )
            txnDao.insertAll(
                listOf(
                    txn("monthly-v1", DAY_5, "COFFEE SHOP", -500L),
                    txn("monthly-v1", DAY_10, "GROCERY STORE", -8000L),
                    txn("monthly-v1", DAY_10, "NEW CORRECTED LINE", -1200L),
                )
            )
        }

        // The overlapping YTD file resets to NEW - it will be re-scanned and
        // re-derive its (still legitimately zero) contribution, rather than
        // being silently skipped forever because it's already INGESTED.
        val ytdAfter = ingestedDao.getByDriveFileId("ytd")
        assertEquals(IngestState.NEW, ytdAfter?.state)

        // The replaced file's own rows are exactly its new set - old rows
        // gone, no duplication, the corrected third line present.
        val monthlyRowsAfter = txnDao.getForAccountInRange(ACCOUNT_ID, DAY_1, DAY_10)
            .filter { it.sourceFileId == "monthly-v1" }
        assertEquals(3, monthlyRowsAfter.size)
        assertEquals(setOf("COFFEE SHOP", "GROCERY STORE", "NEW CORRECTED LINE"), monthlyRowsAfter.map { it.description }.toSet())

        // The replaced file's own record is untouched by resetOverlapping
        // (its own re-commit is IngestPipeline.commit's job, not this
        // sequence's) - resetOverlapping explicitly excludes fileId = self.
        val monthlyAfter = ingestedDao.getByDriveFileId("monthly-v1")
        assertEquals(IngestState.INGESTED, monthlyAfter?.state)
    }

    companion object {
        private const val ACCOUNT_ID = "bofa-checking"
        private const val DAY_1 = 1_700_000_000_000L
        private const val DAY_5 = 1_700_400_000_000L
        private const val DAY_10 = 1_700_800_000_000L
    }
}
