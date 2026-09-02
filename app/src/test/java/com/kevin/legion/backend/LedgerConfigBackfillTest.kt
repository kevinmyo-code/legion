package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CategoryRule
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LedgerConfigBackfill] - a pre-write-through local row (no [serverId][CategoryRule.serverId],
 * never pushed) reaches the server exactly once, a second run is a genuine no-op, and a mid-run
 * failure resumes without double-sending. Exercised against `category_rules` as the representative
 * table (same posture [MemoryBackfillTest]'s own class doc states for standing in for every table
 * backed by the same generic `backfillTable` helper) - not `categories`, which
 * [MIGRATION_5_6]/[MIGRATION_11_12] seed with a starter set on every fresh install, making a plain
 * row count assertion there a lie about what this test actually did.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerConfigBackfillTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeLedgerConfigBackend : LedgerConfigBackend {
        val ruleUpsertCalls = mutableListOf<String>()
        var failGuid: String? = null

        // Genuinely succeeds, unlike a bare "not used" stub - MIGRATION_5_6/MIGRATION_11_12 seed
        // `categories` with a starter set on every fresh install, so
        // [LedgerConfigBackfill.run]'s own categories sub-call is never actually idle here the way
        // memory's three tables are for MemoryBackfillTest. See primeCategoriesBackfillCursor's own
        // comment for how the seeded rows are kept out of every test's own assertions.
        override suspend fun fetchChangedCategoriesSince(sinceMs: Long) = Result.success(emptyList<RemoteCategory>())
        override suspend fun upsertCategory(originGuid: String, fields: CategoryFields) =
            Result.success(RemoteCategory(originGuid, fields.name, fields.isFoodCategory, 0L, false, originGuid))
        override suspend fun softDeleteCategory(originGuid: String) = Result.success(true)

        override suspend fun fetchChangedCategoryRulesSince(sinceMs: Long) = Result.success(emptyList<RemoteCategoryRule>())
        override suspend fun upsertCategoryRule(originGuid: String, fields: CategoryRuleFields): Result<RemoteCategoryRule> {
            if (originGuid == failGuid) return Result.failure(LedgerConfigBackendException("forced failure"))
            ruleUpsertCalls.add(originGuid)
            return Result.success(RemoteCategoryRule(originGuid, fields.category, fields.substring, fields.createdAtMs, fields.createdAtMs, false, originGuid))
        }
        override suspend fun softDeleteCategoryRule(originGuid: String) = Result.success(true)

        override suspend fun fetchChangedBudgetTargetsSince(sinceMs: Long) = Result.success(emptyList<RemoteBudgetTarget>())
        override suspend fun upsertBudgetTarget(originGuid: String, fields: BudgetTargetFields) =
            Result.failure<RemoteBudgetTarget>(LedgerConfigBackendException("not used"))
        override suspend fun softDeleteBudgetTarget(originGuid: String) = Result.failure<Boolean>(LedgerConfigBackendException("not used"))
    }

    private lateinit var backend: FakeLedgerConfigBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = FakeLedgerConfigBackend()
    }

    /**
     * Runs [LedgerConfigBackfill.run] ONCE, before any test's own rows exist, so the
     * `categories` table's [MIGRATION_5_6]/[MIGRATION_11_12] starter set is pushed and its own
     * backfill cursor advances past every one of those ids - discarding the report entirely. Every
     * test below calls this FIRST, then inserts its own `category_rules` row(s), so the SECOND
     * (real) [LedgerConfigBackfill.run] call this test actually asserts on has nothing left
     * pending for `categories` and its `report.pushed`/`report.failed` reflect `category_rules`
     * alone - never a hardcoded seed-row count, which would silently go stale the day the starter
     * set's size changes.
     */
    private suspend fun primeCategoriesBackfillCursor() {
        LedgerConfigBackfill.run(context, backend)
    }

    private suspend fun insertLocalRule(guid: String, serverId: String? = null, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.categoryRuleDao().insert(
            CategoryRule(category = "Groceries", substring = "SUBSTR-$guid", createdAt = 1_000L, guid = guid, serverId = serverId, updatedAtMs = 1_000L, deleted = deleted),
        )
    }

    @Test
    fun `a pre-write-through category_rule row with no serverId is pushed`() = runBlocking {
        primeCategoriesBackfillCursor()
        insertLocalRule("guid-legacy")

        val report = LedgerConfigBackfill.run(context, backend)

        assertEquals(1, report.pushed)
        assertEquals(0, report.alreadyPresent)
        assertTrue(report.failed.isEmpty())
        assertEquals(listOf("guid-legacy"), backend.ruleUpsertCalls)
    }

    @Test
    fun `a category_rule row already known-synced is not re-sent`() = runBlocking {
        primeCategoriesBackfillCursor()
        insertLocalRule("guid-synced", serverId = "srv-already-there")

        val report = LedgerConfigBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.alreadyPresent)
        assertTrue(backend.ruleUpsertCalls.isEmpty())
    }

    @Test
    fun `a locally-deleted category_rule row that never synced is skipped, not resurrected`() = runBlocking {
        primeCategoriesBackfillCursor()
        insertLocalRule("guid-dead", deleted = true)

        val report = LedgerConfigBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.skippedLocalOnlyDeleted)
        assertTrue(backend.ruleUpsertCalls.isEmpty())
    }

    @Test
    fun `a second backfill run is a genuine no-op`() = runBlocking {
        primeCategoriesBackfillCursor()
        insertLocalRule("guid-once")

        val first = LedgerConfigBackfill.run(context, backend)
        val second = LedgerConfigBackfill.run(context, backend)

        assertEquals(1, first.pushed)
        assertEquals(0, second.pushed)
        assertEquals(1, backend.ruleUpsertCalls.size)
    }

    @Test
    fun `a partial failure across mixed rows resumes without double-sending`() = runBlocking {
        primeCategoriesBackfillCursor()
        insertLocalRule("guid-ok-1")
        insertLocalRule("guid-fails")
        insertLocalRule("guid-ok-2")
        backend.failGuid = "guid-fails"

        val first = LedgerConfigBackfill.run(context, backend)
        assertEquals(1, first.pushed) // only guid-ok-1, then this table's loop halts on guid-fails
        assertEquals(1, first.failed.size)
        assertTrue(first.failed.single().contains("guid-fails"))

        backend.failGuid = null
        val second = LedgerConfigBackfill.run(context, backend)
        assertEquals(2, second.pushed)
        assertTrue(second.failed.isEmpty())
        assertEquals(listOf("guid-ok-1", "guid-fails", "guid-ok-2"), backend.ruleUpsertCalls)
    }

    @Test
    fun `running against empty category_rules and budget_targets is an honest zero, not silence`() = runBlocking {
        val report = LedgerConfigBackfill.run(context, backend)

        // categories is deliberately excluded from this assertion - MIGRATION_5_6/MIGRATION_11_12
        // seed it with a starter set on every fresh install (see this class's own doc comment), so
        // its own pushed count here is genuinely > 0 on a fresh CarDatabase; category_rules and
        // budget_targets have no such seeding and must report a true zero.
        assertTrue(report.failed.isEmpty())
        val db = CarDatabase.getDatabase(context)
        assertTrue(db.categoryRuleDao().getAllIncludingDeleted().isEmpty())
        assertTrue(db.budgetTargetDao().getAll().isEmpty())
    }

    // --- LedgerConfigBackfill.runIfSignedIn - the cold-start guard -------------------------------

    private class RetryOnceGateway(private val userIdOnceReady: String) : SupabaseAuthGateway {
        var awaitSessionReadyCalls = 0
        override suspend fun signInWithPassword(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override fun currentUserId(): String? = if (awaitSessionReadyCalls >= 2) userIdOnceReady else null
        override suspend fun awaitSessionReady(timeoutMillis: Long): Boolean {
            awaitSessionReadyCalls++
            return awaitSessionReadyCalls >= 2
        }
        override suspend fun householdRosterSize(): Int = 0
    }

    @Test
    fun `runIfSignedIn retries once through a still-restoring session, then backfills`() = runBlocking {
        primeCategoriesBackfillCursor()
        insertLocalRule("guid-cold-start")
        val gateway = RetryOnceGateway(userIdOnceReady = "user-1")
        val auth = SupabaseAuth(context, gatewayProvider = { gateway })

        val report = LedgerConfigBackfill.runIfSignedIn(context, auth, backend)

        assertEquals(1, report?.pushed)
        assertEquals(listOf("guid-cold-start"), backend.ruleUpsertCalls)
    }
}
