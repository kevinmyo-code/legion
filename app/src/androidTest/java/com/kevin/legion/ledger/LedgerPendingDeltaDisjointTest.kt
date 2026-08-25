package com.kevin.legion.ledger

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `LedgerTransactionDao.provisionalDeltaCentsAfter` and `LedgerTransactionDao.pendingDeltaCents`
 * must never both count the same row - see [com.kevin.legion.data.local.LedgerTransaction.pendingLoggedAt]'s
 * doc comment for why a voice-logged pending row is ALSO tagged `IngestMethod.UNRECONCILED`, which
 * is exactly what makes double-counting possible without the `AND pendingLoggedAt IS NULL` guard
 * added to `provisionalDeltaCentsAfter` alongside `pendingDeltaCents`'s own addition. This needs a
 * real Room database to observe, so it lives in `androidTest` rather than a plain JVM unit test.
 *
 * This used to cite `IngestPipelineProvisionalSupersedeTest` as the same-posture precedent. That
 * test moved to `src/test` (JVM/Robolectric) on 2026-08-25, hardening ticket 05 defect 1, because
 * `RoomTestReset.resetCarDatabaseSingleton()` makes `CarDatabase.getDatabase` reachable from
 * Robolectric. "Needs a real Room database" is therefore NOT on its own a reason to be
 * instrumented - try Robolectric first before adding to `androidTest`.
 */
@RunWith(AndroidJUnit4::class)
class LedgerPendingDeltaDisjointTest {

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

    private fun fileDerivedProvisional(txnDate: Long, amountCents: Long) = LedgerTransaction(
        sourceFile = "currentTransaction_7823.csv",
        accountId = "7823",
        currency = LedgerCurrency.USD,
        txnDate = txnDate,
        description = "CARD CSV ROW",
        amountCents = amountCents,
        balanceCents = null,
        lineRef = "csv:$txnDate",
        ingestMethod = IngestMethod.UNRECONCILED,
        pendingLoggedAt = null,
    )

    private fun voiceLoggedPending(txnDate: Long, amountCents: Long) = LedgerTransaction(
        sourceFile = "voice",
        accountId = "7823",
        currency = LedgerCurrency.USD,
        txnDate = txnDate,
        description = "VOICE-LOGGED ROW",
        amountCents = amountCents,
        balanceCents = null,
        lineRef = "voice:$txnDate",
        ingestMethod = IngestMethod.UNRECONCILED,
        pendingLoggedAt = 999L,
    )

    /**
     * One file-derived provisional row and one voice-logged pending row, same account/currency,
     * both dated after the (nonexistent) balance anchor. `provisionalDeltaCentsAfter` must sum
     * ONLY the file-derived row; `pendingDeltaCents` must sum ONLY the voice-logged one. Neither
     * query may see the other's row - proving the two sums are disjoint, not just individually
     * correct.
     */
    @Test
    fun theTwoDeltaQueriesNeverCountTheSameRow() = kotlinx.coroutines.runBlocking {
        val dao = db.ledgerTransactionDao()
        dao.insertAll(listOf(fileDerivedProvisional(JUL_1, -4500L), voiceLoggedPending(JUL_2, -1200L)))

        val provisional = dao.provisionalDeltaCentsAfter("7823", LedgerCurrency.USD, Long.MIN_VALUE)
        val pending = dao.pendingDeltaCents("7823", LedgerCurrency.USD)

        assertEquals(-4500L, provisional)
        assertEquals(-1200L, pending)
        // The two sums together account for every row exactly once - neither
        // is inflated by the other's row, and their sum is the true total.
        assertEquals(-5700L, (provisional ?: 0L) + (pending ?: 0L))
    }

    /** A voice-logged row alone: provisionalDeltaCentsAfter must read zero, never the pending amount. */
    @Test
    fun aVoiceLoggedRowNeverCountsTowardProvisionalDelta() = kotlinx.coroutines.runBlocking {
        val dao = db.ledgerTransactionDao()
        dao.insertAll(listOf(voiceLoggedPending(JUL_1, -8000L)))

        assertEquals(null, dao.provisionalDeltaCentsAfter("7823", LedgerCurrency.USD, Long.MIN_VALUE))
        assertEquals(-8000L, dao.pendingDeltaCents("7823", LedgerCurrency.USD))
    }

    /** A file-derived provisional row alone: pendingDeltaCents must read zero, never the file's amount. */
    @Test
    fun aFileDerivedRowNeverCountsTowardPendingDelta() = kotlinx.coroutines.runBlocking {
        val dao = db.ledgerTransactionDao()
        dao.insertAll(listOf(fileDerivedProvisional(JUL_1, -3000L)))

        assertEquals(-3000L, dao.provisionalDeltaCentsAfter("7823", LedgerCurrency.USD, Long.MIN_VALUE))
        assertEquals(null, dao.pendingDeltaCents("7823", LedgerCurrency.USD))
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val JUL_1 = 1_751_328_000_000L
        private const val JUL_2 = JUL_1 + DAY_MS
    }
}
