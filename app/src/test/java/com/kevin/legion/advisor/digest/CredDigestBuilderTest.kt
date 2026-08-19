package com.kevin.legion.advisor.digest

import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * [CredDigestBuilder] over a real Room database (Robolectric, same shape as `GoalControllerTest`/
 * `BioDigestBuilderTest`) - pins the two things ticket 16 exists to gate for CRED specifically: a
 * month nothing was ever imported for reads [com.kevin.legion.advisor.DigestText.notLogged] rather
 * than a coerced "$0.00" (this aspect's own version of "0 kcal for an unlogged day"), and an
 * [IngestMethod.UNRECONCILED] row is marked unverified IN WORDS wherever it contributes to a figure
 * (CLAUDE.md §4 rule 7).
 */
@RunWith(RobolectricTestRunner::class)
class CredDigestBuilderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val builder = CredDigestBuilder()
    private val month: YearMonth = YearMonth.now()
    private val monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** Marks the whole month as INGESTED coverage for [accountId] - the precondition every real figure needs before it stops reading "not logged". */
    private suspend fun markCovered(accountId: String) {
        CarDatabase.getDatabase(context).ingestedFileDao().upsert(
            IngestedFile(
                driveFileId = "file-$accountId",
                treeUri = null,
                displayName = "$accountId.csv",
                sizeBytes = 100,
                lastModified = monthStart,
                contentSha256 = "sha-$accountId",
                state = IngestState.INGESTED,
                transactionCount = 1,
                firstSeenAt = monthStart,
                lastAttemptAt = monthStart,
                accountId = accountId,
                minTxnDate = monthStart,
                maxTxnDate = monthEnd,
            )
        )
    }

    private fun txn(
        accountId: String, description: String, amountCents: Long, category: String? = null,
        ingestMethod: IngestMethod = IngestMethod.DETERMINISTIC, categoryPending: Boolean = false,
    ) = LedgerTransaction(
        sourceFile = "test", accountId = accountId, currency = LedgerCurrency.USD,
        txnDate = monthStart + 5 * 24 * 60 * 60 * 1000L, description = description, amountCents = amountCents,
        lineRef = "test:$description:$amountCents", ingestMethod = ingestMethod, category = category,
        categoryPending = categoryPending,
    )

    @Test
    fun `a month with no ingested coverage reads not logged, never a bare zero`() = runBlocking {
        val digest = builder.build(context)

        val notLoggedCount = Regex("not logged").findAll(digest).count()
        assertTrue("expected BUDGET/UNCATEGORIZED/COVERAGE/SPEND/MERCHANTS to all read not logged, got:\n$digest", notLoggedCount >= 5)
        assertFalse(digest.contains("actual 0.00"))
    }

    @Test
    fun `provisional row count is a real number even with no coverage at all`() = runBlocking {
        // pendingLoggedAt-style voice-logged rows live in Room independent of any ingested file -
        // PROVISIONAL must never fold into the "nothing imported" not-logged case.
        CarDatabase.getDatabase(context).ledgerTransactionDao().insertAll(
            listOf(txn("CARD1234", "voice charge", -2000, ingestMethod = IngestMethod.UNRECONCILED))
        )

        val digest = builder.build(context)
        val provisionalLine = digest.lines().first { it.startsWith("PROVISIONAL") }

        assertEquals("PROVISIONAL 1 row [reported]", provisionalLine)
    }

    @Test
    fun `a reconciled category spend reports target, actual, remaining, proven`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        markCovered("BOFA1234")
        db.budgetTargetDao().upsert(BudgetTarget(category = "Groceries", currency = LedgerCurrency.USD, amountCents = 40000, effectiveFromMonthEpoch = monthStart, updatedAt = monthStart))
        db.ledgerTransactionDao().insertAll(listOf(txn("BOFA1234", "TRADER JOES", -31245, category = "Groceries")))

        val digest = builder.build(context)
        val budgetLine = digest.lines().first { it.startsWith("BUDGET") }

        assertEquals("BUDGET Groceries target 400.00 actual 312.45 remaining 87.55 [proven]", budgetLine)
    }

    @Test
    fun `an unreconciled row's category line is marked unverified in words`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        markCovered("CARD1234")
        db.budgetTargetDao().upsert(BudgetTarget(category = "Dining", currency = LedgerCurrency.USD, amountCents = 20000, effectiveFromMonthEpoch = monthStart, updatedAt = monthStart))
        db.ledgerTransactionDao().insertAll(listOf(txn("CARD1234", "PENDING RESTAURANT", -4500, category = "Dining", ingestMethod = IngestMethod.UNRECONCILED)))

        val digest = builder.build(context)
        val budgetLine = digest.lines().first { it.startsWith("BUDGET") }

        assertTrue("must say unverified in words, not just tag reported", budgetLine.contains("(unverified)"))
        assertTrue(budgetLine.endsWith("[reported]"))
    }

    @Test
    fun `uncategorised spend is its own loud bucket, real zero when everything is categorised`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        markCovered("BOFA1234")
        db.ledgerTransactionDao().insertAll(listOf(txn("BOFA1234", "TRADER JOES", -1000, category = "Groceries")))

        val digest = builder.build(context)
        val uncategorizedLine = digest.lines().first { it.startsWith("UNCATEGORIZED") }

        // Coverage IS present and every row IS categorised - a real, verified $0.00, not "not logged".
        assertEquals("UNCATEGORIZED actual 0.00 [proven]", uncategorizedLine)
    }

    @Test
    fun `coverage gaps are stated in words, naming the account`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        // Only the first half of the month is covered - a real gap, not a full month.
        db.ingestedFileDao().upsert(
            IngestedFile(
                driveFileId = "partial", treeUri = null, displayName = "partial.csv", sizeBytes = 10,
                lastModified = monthStart, contentSha256 = "sha-partial", state = IngestState.INGESTED,
                transactionCount = 1, firstSeenAt = monthStart, lastAttemptAt = monthStart,
                accountId = "BOFA1234", minTxnDate = monthStart, maxTxnDate = monthStart + 10L * 24 * 60 * 60 * 1000,
            )
        )
        db.ledgerTransactionDao().insertAll(listOf(txn("BOFA1234", "TRADER JOES", -1000, category = "Groceries")))

        val digest = builder.build(context)
        val coverageLine = digest.lines().first { it.startsWith("COVERAGE") }

        assertTrue("expected a stated gap naming the account, got: $coverageLine", coverageLine.contains("gaps:"))
        assertTrue(coverageLine.contains("BOFA1234"))
    }

    @Test
    fun `top merchants lists the largest spenders by name`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        markCovered("BOFA1234")
        db.ledgerTransactionDao().insertAll(
            listOf(
                txn("BOFA1234", "AMAZON", -9000, category = "Shopping"),
                txn("BOFA1234", "STARBUCKS", -500, category = "Dining"),
            )
        )

        val digest = builder.build(context)
        val merchantsLine = digest.lines().first { it.startsWith("MERCHANTS") }

        assertTrue(merchantsLine.contains("AMAZON 90.00"))
        assertTrue(merchantsLine.contains("STARBUCKS 5.00"))
    }

    @Test
    fun `a savings goal reports its current balance from the latest reconciled statement`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        markCovered("BOFA1234")
        db.ledgerTransactionDao().insertAll(
            listOf(
                LedgerTransaction(
                    sourceFile = "test", accountId = "BOFA1234", currency = LedgerCurrency.USD,
                    txnDate = monthStart + 5 * 24 * 60 * 60 * 1000L, description = "opening balance",
                    amountCents = 0, balanceCents = 1_250_000L, lineRef = "test:balance",
                    ingestMethod = IngestMethod.DETERMINISTIC,
                )
            )
        )
        db.goalDao().insert(Goal(lineageId = 1, aspect = "cred", statement = "save 30k by 2028", targetValue = 30000.0, unit = "usd", metricKey = "savings_balance_cents"))

        val digest = builder.build(context)
        val goalLine = digest.lines().first { it.startsWith("GOAL") }

        assertTrue(goalLine.contains("current 12,500.00"))
        assertTrue(goalLine.endsWith("[proven]"))
    }
}
