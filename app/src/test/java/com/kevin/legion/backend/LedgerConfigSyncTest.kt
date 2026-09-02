package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CategoryRule
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LedgerConfigSync.pull] - exercised the same way [MemorySyncTest] exercises [MemorySync.pull]:
 * against an in-memory [FakeLedgerConfigBackend] and the real (Robolectric) local tables, never a
 * network. Exercised against `category_rules` as the representative table, since
 * [LedgerConfigMerge.merge] is the ONE shared implementation every one of the three tables' sub-
 * pulls calls - see that object's own class doc for why testing it three times by hand would be
 * three independent chances to get rule 6 wrong in exactly one of them. `category_rules` was
 * chosen over `categories` specifically because `categories` gets a starter set seeded by
 * [MIGRATION_5_6]/[MIGRATION_11_12] on every fresh install, which would make plain row counts a
 * lie about what this test actually did; `category_rules` starts genuinely empty.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerConfigSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeLedgerConfigBackend : LedgerConfigBackend {
        val categoryRules = mutableMapOf<String, RemoteCategoryRule>()

        override suspend fun fetchChangedCategoriesSince(sinceMs: Long) = Result.success(emptyList<RemoteCategory>())
        override suspend fun upsertCategory(originGuid: String, fields: CategoryFields) =
            Result.failure<RemoteCategory>(LedgerConfigBackendException("not used"))
        override suspend fun softDeleteCategory(originGuid: String) = Result.failure<Boolean>(LedgerConfigBackendException("not used"))

        override suspend fun fetchChangedCategoryRulesSince(sinceMs: Long) = Result.success(categoryRules.values.toList())
        override suspend fun upsertCategoryRule(originGuid: String, fields: CategoryRuleFields): Result<RemoteCategoryRule> {
            val row = RemoteCategoryRule(originGuid, fields.category, fields.substring, fields.createdAtMs, fields.createdAtMs, false, originGuid)
            categoryRules[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteCategoryRule(originGuid: String): Result<Boolean> {
            val existing = categoryRules[originGuid] ?: return Result.success(false)
            categoryRules[originGuid] = existing.copy(deleted = true)
            return Result.success(true)
        }

        override suspend fun fetchChangedBudgetTargetsSince(sinceMs: Long) = Result.success(emptyList<RemoteBudgetTarget>())
        override suspend fun upsertBudgetTarget(originGuid: String, fields: BudgetTargetFields) =
            Result.failure<RemoteBudgetTarget>(LedgerConfigBackendException("not used"))
        override suspend fun softDeleteBudgetTarget(originGuid: String) = Result.failure<Boolean>(LedgerConfigBackendException("not used"))
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteRule(guid: String, substring: String, updatedAtMs: Long, deleted: Boolean = false) =
        RemoteCategoryRule(serverId = "srv-$guid", category = "Groceries", substring = substring, createdAtMs = updatedAtMs, updatedAtMs = updatedAtMs, deleted = deleted, originGuid = guid)

    private suspend fun insertLocalRule(guid: String, substring: String, updatedAtMs: Long, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.categoryRuleDao().insert(
            CategoryRule(category = "Groceries", substring = substring, createdAt = updatedAtMs, guid = guid, updatedAtMs = updatedAtMs, deleted = deleted),
        )
    }

    @Test
    fun `a server-only row is inserted`() = runBlocking {
        val backend = FakeLedgerConfigBackend()
        backend.categoryRules["guid-1"] = remoteRule("guid-1", "KROGER", 1_000L)

        val report = LedgerConfigSync.pull(context, backend)

        assertEquals(1, report.inserted)
        val all = CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted()
        assertEquals(1, all.size)
        assertEquals("KROGER", all.single().substring)
        assertEquals("guid-1", all.single().guid)
    }

    @Test
    fun `a local-only row survives untouched`() = runBlocking {
        val id = insertLocalRule("local-guid", "TARGET", 1_000L)
        val backend = FakeLedgerConfigBackend() // server has nothing

        val report = LedgerConfigSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.updated)
        assertEquals(0, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted().find { it.id == id }
        assertNotNull(stored)
        assertFalse(stored!!.deleted)
        assertEquals("TARGET", stored.substring)
    }

    @Test
    fun `a newer local row is not overwritten by an older server row`() = runBlocking {
        val id = insertLocalRule("guid-2", "NEW-SUBSTRING", 5_000L)
        val backend = FakeLedgerConfigBackend()
        backend.categoryRules["guid-2"] = remoteRule("guid-2", "STALE-SUBSTRING", 1_000L)

        val report = LedgerConfigSync.pull(context, backend)

        assertEquals(0, report.updated)
        assertEquals(1, report.skippedLocalNewer)
        val stored = CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted().find { it.id == id }
        assertEquals("NEW-SUBSTRING", stored!!.substring)
    }

    @Test
    fun `a newer server row is applied over an older local row`() = runBlocking {
        val id = insertLocalRule("guid-3", "STALE-SUBSTRING", 1_000L)
        val backend = FakeLedgerConfigBackend()
        backend.categoryRules["guid-3"] = remoteRule("guid-3", "FRESH-SUBSTRING", 5_000L)

        val report = LedgerConfigSync.pull(context, backend)

        assertEquals(1, report.updated)
        val stored = CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted().find { it.id == id }
        assertEquals("FRESH-SUBSTRING", stored!!.substring)
    }

    @Test
    fun `a server tombstone soft-deletes the matching local row`() = runBlocking {
        val id = insertLocalRule("guid-4", "TO-BE-FORGOTTEN", 1_000L)
        val backend = FakeLedgerConfigBackend()
        backend.categoryRules["guid-4"] = remoteRule("guid-4", "TO-BE-FORGOTTEN", 5_000L, deleted = true)

        val report = LedgerConfigSync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted().find { it.id == id }
        assertNotNull(stored)
        assertTrue(stored!!.deleted)
    }

    @Test
    fun `a tombstoned server row with no local match is skipped, never inserted`() = runBlocking {
        val backend = FakeLedgerConfigBackend()
        backend.categoryRules["guid-ghost"] = remoteRule("guid-ghost", "NEVER-EXISTED", 1_000L, deleted = true)

        val report = LedgerConfigSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.tombstoned)
        assertEquals(1, report.skippedTombstoneNoLocalMatch)
        assertEquals(0, CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted().size)
    }

    @Test
    fun `a second consecutive pull of the same server state is a no-op`() = runBlocking {
        val backend = FakeLedgerConfigBackend()
        backend.categoryRules["guid-5"] = remoteRule("guid-5", "UTILITIES-CO", 1_000L)

        val first = LedgerConfigSync.pull(context, backend)
        assertEquals(1, first.inserted)

        val beforeSecond = CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted()
        val second = LedgerConfigSync.pull(context, backend)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        val afterSecond = CarDatabase.getDatabase(context).categoryRuleDao().getAllIncludingDeleted()
        assertEquals(beforeSecond, afterSecond)
    }
}
