package com.kevin.legion.backend

import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CategoryRule
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.home.moneyTileDisclosure
import com.kevin.legion.ui.home.moneyTileStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LedgerTransactionsSync.mirror] against the real (Robolectric) Room table and a fake engine -
 * backend-etl ticket 14. The pure rules are pinned in [LedgerMirrorPlanTest]; this proves they are
 * applied to Room as planned, that a failed read touches nothing, and that the result reaches the
 * Money tile's own wording for September 2026.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerTransactionsMirrorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    private class FakeEngine(
        val rows: MutableList<RemoteLedgerTransaction> = mutableListOf(),
        var complete: Boolean = true,
        var failWith: Exception? = null,
    ) : LedgerBackend {
        override suspend fun fetchActiveTransactions(): Result<List<RemoteLedgerTransaction>> = Result.success(rows)
        override suspend fun uploadMigratedTransaction(txn: MigratedLedgerTransaction): Result<Boolean> =
            Result.failure(LedgerBackendException("not used"))
        override suspend fun fetchTransactionSet(): Result<RemoteTransactionSet> =
            failWith?.let { Result.failure(it) } ?: Result.success(RemoteTransactionSet(rows.toList(), complete))
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun epoch(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun remote(
        serverId: String,
        description: String,
        amountCents: Long,
        provenance: String = "DETERMINISTIC",
        category: String? = null,
        day: Int = 10,
    ) = RemoteLedgerTransaction(
        serverId = serverId,
        statementId = if (provenance == "UNRECONCILED") null else "stmt-1",
        accountLast4 = "4146",
        accountNickname = "BofA card",
        currency = "USD",
        txnDateEpochMs = epoch(2026, 9, day),
        description = description,
        amountCents = amountCents,
        balanceCents = null,
        lineRef = "ref-$serverId",
        category = category,
        categoryPending = category == null,
        pendingLoggedAtMs = null,
        provenance = provenance,
        createdAtMs = epoch(2026, 9, 28),
        originGuid = null,
    )

    private fun voiceRow() = LedgerTransaction(
        sourceFile = "voice",
        accountId = "BofA card",
        currency = LedgerCurrency.USD,
        txnDate = epoch(2026, 9, 27),
        description = "coffee I told you about",
        amountCents = -500L,
        lineRef = "voice-1",
        ingestMethod = IngestMethod.UNRECONCILED,
        pendingLoggedAt = epoch(2026, 9, 27),
    )

    private val noRules: suspend (android.content.Context) -> Int = { 0 }

    @Test
    fun `inserts, fills a category, deletes a superseded row, and leaves a voice row alone`() = runBlocking {
        val engine = FakeEngine()
        engine.rows += remote("s-1", "UBER *TRIP", -1_200L, "UNRECONCILED")
        engine.rows += remote("s-2", "HEB GROCERY", -6_000L)
        LedgerTransactionsSync.mirror(context, engine, noRules)
        db.ledgerTransactionDao().insertAll(listOf(voiceRow()))

        // Next day: s-1 was superseded by a statement row s-3, and s-2 got a category (a fresh row
        // on the engine would carry it from insert; here it stands in for the fill path).
        engine.rows.clear()
        engine.rows += remote("s-2", "HEB GROCERY", -6_000L, category = "Groceries")
        engine.rows += remote("s-3", "UBER *TRIP", -1_200L)
        val report = LedgerTransactionsSync.mirror(context, engine, noRules)

        assertEquals(1, report.inserted)
        assertEquals(1, report.categoriesFilled)
        assertEquals(1, report.deleted)
        assertFalse(report.deletionsSkipped)
        val stored = db.ledgerTransactionDao().getAll()
        assertEquals(setOf("s-2", "s-3"), stored.filter { it.sourceFile == SYNCED_SOURCE_FILE }.map { it.syncId }.toSet())
        assertEquals("Groceries", stored.single { it.syncId == "s-2" }.category)
        assertTrue("the voice-logged row survives", stored.any { it.sourceFile == "voice" })
    }

    @Test
    fun `an incomplete list inserts but removes nothing`() = runBlocking {
        val engine = FakeEngine()
        engine.rows += remote("s-1", "A", -100L, "UNRECONCILED")
        LedgerTransactionsSync.mirror(context, engine, noRules)

        engine.rows.clear()
        engine.rows += remote("s-2", "B", -100L)
        engine.complete = false
        val report = LedgerTransactionsSync.mirror(context, engine, noRules)

        assertTrue(report.deletionsSkipped)
        assertEquals(0, report.deleted)
        assertEquals(setOf("s-1", "s-2"), db.ledgerTransactionDao().getAll().map { it.syncId }.toSet())
    }

    @Test
    fun `an unreachable engine leaves Room exactly as it was and the Money line says so`() = runBlocking {
        val engine = FakeEngine()
        engine.rows += remote("s-1", "HEB GROCERY", -6_000L)
        LedgerTransactionsSync.runMirror(context, engine)
        assertNull(LedgerMirrorStatus.line(context))
        val before = db.ledgerTransactionDao().getAll()

        engine.rows.clear()
        engine.failWith = LedgerBackendException("Couldn't reach the engine to load your transactions.")
        try {
            LedgerTransactionsSync.runMirror(context, engine)
            fail("an unreachable engine must surface as a failure, not a quiet pass")
        } catch (expected: LedgerBackendException) {
            // expected
        }

        assertEquals(before, db.ledgerTransactionDao().getAll())
        assertEquals(
            "Not synced: the server couldn't be read just now, so this shows what this phone last had",
            LedgerMirrorStatus.line(context),
        )

        engine.failWith = null
        engine.rows += remote("s-1", "HEB GROCERY", -6_000L)
        LedgerTransactionsSync.runMirror(context, engine)
        assertNull("a later success clears the line", LedgerMirrorStatus.line(context))
    }

    /**
     * The phone's categoriser, on the same vectors `server/tests/test_ledger_categories_and_paging.py`
     * runs against `ingest/category_rules.py` - the port claims identical semantics, so both sides
     * are held to one set of cases: case-insensitive substring, oldest rule wins, deleted rules
     * ignored, `ß` uppercases to `SS`, and a category already set is never touched.
     */
    @Test
    fun `the mirror's categoriser matches the server port's semantics`() = runBlocking {
        val rules = db.categoryRuleDao()
        rules.insert(CategoryRule(category = "Transport", substring = "uber", createdAt = 1L))
        rules.insert(CategoryRule(category = "Dining", substring = "UBER EATS", createdAt = 5L))
        rules.insert(CategoryRule(category = "Shopping", substring = "uber", createdAt = 0L, deleted = true))
        rules.insert(CategoryRule(category = "Travel", substring = "STRASSE", createdAt = 2L))
        rules.insert(CategoryRule(category = "Coffee", substring = "starbucks", createdAt = 3L))

        val engine = FakeEngine()
        engine.rows += remote("s-1", "Uber Eats 555", -1_000L)
        engine.rows += remote("s-2", "Hauptstraße 5", -2_000L)
        engine.rows += remote("s-3", "STAR BUCKS", -300L)
        engine.rows += remote("s-4", "starbucks #1", -400L, category = "Gifts")
        val report = LedgerTransactionsSync.mirror(context, engine)

        val byId = db.ledgerTransactionDao().getAll().associateBy { it.syncId }
        assertEquals("Transport", byId.getValue("s-1").category)
        assertEquals("Travel", byId.getValue("s-2").category)
        assertNull(byId.getValue("s-3").category)
        assertEquals("Gifts", byId.getValue("s-4").category)
        assertFalse(byId.getValue("s-1").categoryPending)
        assertEquals(2, report.rulesApplied)
    }

    @Test
    fun `September 2026 on the Money tile - spent of budget, uncategorised excluded, unverified said`() = runBlocking {
        val sept = epoch(2026, 9, 1)
        db.budgetTargetDao().upsert(BudgetTarget(category = "Groceries", currency = LedgerCurrency.USD, amountCents = 50_000L, effectiveFromMonthEpoch = sept, updatedAt = 1L))
        db.budgetTargetDao().upsert(BudgetTarget(category = "Dining", currency = LedgerCurrency.USD, amountCents = 20_000L, effectiveFromMonthEpoch = sept, updatedAt = 1L))

        val engine = FakeEngine()
        engine.rows += remote("s-1", "HEB GROCERY", -6_000L, category = "Groceries")
        engine.rows += remote("s-2", "TACO PLACE", -1_200L, "UNRECONCILED", category = "Dining")
        engine.rows += remote("s-3", "MYSTERY MERCHANT", -300L, "UNRECONCILED")
        // August: must not count toward September.
        engine.rows += remote("s-4", "HEB GROCERY", -9_900L, category = "Groceries").copy(txnDateEpochMs = epoch(2026, 8, 31))
        LedgerTransactionsSync.mirror(context, engine, noRules)

        val budget = LedgerController.budgetVsActual(context, LedgerEntity.US, YearMonth.of(2026, 9))

        assertEquals(7_200L, budget.spentCents)
        assertEquals("USD 72.00 of USD 700.00", moneyTileStatus(budget).text)
        val disclosure = moneyTileDisclosure(budget)
        assertNotNull(disclosure)
        assertTrue(disclosure!!, disclosure.contains("Excludes USD 3.00 uncategorized"))
        assertTrue(disclosure, disclosure.contains("unverified"))
        assertTrue(
            "a failed sync leads the tile's disclosure",
            moneyTileDisclosure(budget, "Not synced - this phone's last copy")!!.startsWith("Not synced"),
        )
    }
}
