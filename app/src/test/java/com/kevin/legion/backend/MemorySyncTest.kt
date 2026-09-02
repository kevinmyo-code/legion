package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MemoryEntry
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MemorySync.pull] - exercised the same way [BodySyncTest] exercises [BodySync.pull]: against an
 * in-memory [FakeMemoryBackend] and the real (Robolectric) local tables, never a network.
 * Exercised against `memories` as the representative table, since [MemoryMerge.merge] is the ONE
 * shared implementation every one of the three tables' sub-pulls calls - see that object's own
 * class doc for why testing it three times by hand would be three independent chances to get rule
 * 6 wrong in exactly one of them.
 */
@RunWith(RobolectricTestRunner::class)
class MemorySyncTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeMemoryBackend : MemoryBackend {
        val memoryEntries = mutableMapOf<String, RemoteMemoryEntry>()
        var failNextUpsert = false

        override suspend fun fetchChangedMemoryEntriesSince(sinceMs: Long) = Result.success(memoryEntries.values.toList())
        override suspend fun upsertMemoryEntry(originGuid: String, fields: MemoryEntryFields): Result<RemoteMemoryEntry> {
            if (failNextUpsert) return Result.failure(MemoryBackendException("forced failure"))
            val row = RemoteMemoryEntry(originGuid, fields.text, fields.loggedAtMs, fields.loggedAtMs, false, originGuid)
            memoryEntries[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteMemoryEntry(originGuid: String): Result<Boolean> {
            val existing = memoryEntries[originGuid] ?: return Result.success(false)
            memoryEntries[originGuid] = existing.copy(deleted = true)
            return Result.success(true)
        }

        override suspend fun fetchChangedCompanionMemoriesSince(sinceMs: Long) = Result.success(emptyList<RemoteCompanionMemory>())
        override suspend fun upsertCompanionMemory(originGuid: String, fields: CompanionMemoryFields) =
            Result.failure<RemoteCompanionMemory>(MemoryBackendException("not used"))
        override suspend fun softDeleteCompanionMemory(originGuid: String) = Result.failure<Boolean>(MemoryBackendException("not used"))

        override suspend fun fetchChangedMemoryAuditSince(sinceMs: Long) = Result.success(emptyList<RemoteMemoryAudit>())
        override suspend fun upsertMemoryAudit(originGuid: String, fields: MemoryAuditFields) =
            Result.failure<RemoteMemoryAudit>(MemoryBackendException("not used"))
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteMemory(guid: String, text: String, updatedAtMs: Long, deleted: Boolean = false) =
        RemoteMemoryEntry(serverId = "srv-$guid", text = text, loggedAtMs = updatedAtMs, updatedAtMs = updatedAtMs, deleted = deleted, originGuid = guid)

    private suspend fun insertLocalMemory(syncId: String, text: String, updatedAtMs: Long, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.memoryDao().insert(
            MemoryEntry(text = text, timestamp = updatedAtMs, syncId = syncId, updatedAtMs = updatedAtMs, deleted = deleted),
        )
    }

    @Test
    fun `a server-only row is inserted`() = runBlocking {
        val backend = FakeMemoryBackend()
        backend.memoryEntries["guid-1"] = remoteMemory("guid-1", "work address", 1_000L)

        val report = MemorySync.pull(context, backend)

        assertEquals(1, report.inserted)
        val all = CarDatabase.getDatabase(context).memoryDao().getAll()
        assertEquals(1, all.size)
        assertEquals("work address", all.single().text)
        assertEquals("guid-1", all.single().syncId)
    }

    @Test
    fun `a local-only row survives untouched`() = runBlocking {
        val id = insertLocalMemory("local-guid", "gym address", 1_000L)
        val backend = FakeMemoryBackend() // server has nothing

        val report = MemorySync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.updated)
        assertEquals(0, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).memoryDao().getAll().find { it.id == id }
        assertNotNull(stored)
        assertEquals(false, stored!!.deleted)
        assertEquals("gym address", stored.text)
    }

    @Test
    fun `a newer local row is not overwritten by an older server row`() = runBlocking {
        val id = insertLocalMemory("guid-2", "new text", 5_000L)
        val backend = FakeMemoryBackend()
        backend.memoryEntries["guid-2"] = remoteMemory("guid-2", "stale text", 1_000L)

        val report = MemorySync.pull(context, backend)

        assertEquals(0, report.updated)
        assertEquals(1, report.skippedLocalNewer)
        val stored = CarDatabase.getDatabase(context).memoryDao().getAll().find { it.id == id }
        assertEquals("new text", stored!!.text)
    }

    @Test
    fun `a newer server row is applied over an older local row`() = runBlocking {
        val id = insertLocalMemory("guid-3", "stale text", 1_000L)
        val backend = FakeMemoryBackend()
        backend.memoryEntries["guid-3"] = remoteMemory("guid-3", "fresh text", 5_000L)

        val report = MemorySync.pull(context, backend)

        assertEquals(1, report.updated)
        val stored = CarDatabase.getDatabase(context).memoryDao().getAll().find { it.id == id }
        assertEquals("fresh text", stored!!.text)
    }

    @Test
    fun `a server tombstone soft-deletes the matching local row`() = runBlocking {
        val id = insertLocalMemory("guid-4", "to be forgotten", 1_000L)
        val backend = FakeMemoryBackend()
        backend.memoryEntries["guid-4"] = remoteMemory("guid-4", "to be forgotten", 5_000L, deleted = true)

        val report = MemorySync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).memoryDao().getAll().find { it.id == id }
        assertNotNull(stored)
        assertTrue(stored!!.deleted)
    }

    @Test
    fun `a tombstoned server row with no local match is skipped, never inserted`() = runBlocking {
        val backend = FakeMemoryBackend()
        backend.memoryEntries["guid-ghost"] = remoteMemory("guid-ghost", "never existed here", 1_000L, deleted = true)

        val report = MemorySync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.tombstoned)
        assertEquals(1, report.skippedTombstoneNoLocalMatch)
        assertEquals(0, CarDatabase.getDatabase(context).memoryDao().getAll().size)
    }

    @Test
    fun `a second consecutive pull of the same server state is a no-op`() = runBlocking {
        val backend = FakeMemoryBackend()
        backend.memoryEntries["guid-5"] = remoteMemory("guid-5", "height", 1_000L)

        val first = MemorySync.pull(context, backend)
        assertEquals(1, first.inserted)

        val beforeSecond = CarDatabase.getDatabase(context).memoryDao().getAll()
        val second = MemorySync.pull(context, backend)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        val afterSecond = CarDatabase.getDatabase(context).memoryDao().getAll()
        assertEquals(beforeSecond, afterSecond)
    }
}
