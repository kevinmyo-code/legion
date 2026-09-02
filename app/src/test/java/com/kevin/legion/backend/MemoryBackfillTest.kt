package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MemoryAudit
import com.kevin.legion.data.local.MemoryEntry
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
 * [MemoryBackfill] - a pre-write-through local row (no [serverId][MemoryEntry.serverId], never
 * pushed) reaches the server exactly once, a second run is a genuine no-op, and a mid-run failure
 * resumes without double-sending. Exercised against `memories` as the representative
 * write-through table (same posture [BodyBackfillTest]'s own class doc states for standing in for
 * every table backed by the same generic `backfillTable` helper), plus its own dedicated coverage
 * of `memory_audit` - the one table this backfill pushes as its ONLY route to the server, not a
 * catch-up for a separate write-through (see [MemoryBackfill]'s own class doc).
 */
@RunWith(RobolectricTestRunner::class)
class MemoryBackfillTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeMemoryBackend : MemoryBackend {
        val memoryUpsertCalls = mutableListOf<String>()
        val auditUpsertCalls = mutableListOf<String>()
        var failGuid: String? = null

        override suspend fun fetchChangedMemoryEntriesSince(sinceMs: Long) = Result.success(emptyList<RemoteMemoryEntry>())
        override suspend fun upsertMemoryEntry(originGuid: String, fields: MemoryEntryFields): Result<RemoteMemoryEntry> {
            if (originGuid == failGuid) return Result.failure(MemoryBackendException("forced failure"))
            memoryUpsertCalls.add(originGuid)
            return Result.success(RemoteMemoryEntry(originGuid, fields.text, fields.loggedAtMs, fields.loggedAtMs, false, originGuid))
        }
        override suspend fun softDeleteMemoryEntry(originGuid: String) = Result.success(true)

        override suspend fun fetchChangedCompanionMemoriesSince(sinceMs: Long) = Result.success(emptyList<RemoteCompanionMemory>())
        override suspend fun upsertCompanionMemory(originGuid: String, fields: CompanionMemoryFields) =
            Result.failure<RemoteCompanionMemory>(MemoryBackendException("not used"))
        override suspend fun softDeleteCompanionMemory(originGuid: String) = Result.failure<Boolean>(MemoryBackendException("not used"))

        override suspend fun fetchChangedMemoryAuditSince(sinceMs: Long) = Result.success(emptyList<RemoteMemoryAudit>())
        override suspend fun upsertMemoryAudit(originGuid: String, fields: MemoryAuditFields): Result<RemoteMemoryAudit> {
            if (originGuid == failGuid) return Result.failure(MemoryBackendException("forced failure"))
            auditUpsertCalls.add(originGuid)
            return Result.success(
                RemoteMemoryAudit(originGuid, fields.event, fields.store, fields.detail, fields.refId, fields.vehicleId, fields.loggedAtMs, fields.loggedAtMs, false, originGuid),
            )
        }
    }

    private lateinit var backend: FakeMemoryBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = FakeMemoryBackend()
    }

    private suspend fun insertLocalMemory(syncId: String, serverId: String? = null, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.memoryDao().insert(
            MemoryEntry(text = "a fact", timestamp = 1_000L, syncId = syncId, serverId = serverId, updatedAtMs = 1_000L, deleted = deleted),
        )
    }

    private suspend fun insertLocalAudit(guid: String, serverId: String? = null): Long {
        val db = CarDatabase.getDatabase(context)
        return db.memoryAuditDao().insert(
            MemoryAudit(event = MemoryAudit.Event.SPOKEN, store = MemoryAudit.Store.SPEECH, detail = "hello", at = 1_000L, guid = guid, serverId = serverId, updatedAtMs = 1_000L),
        )
    }

    @Test
    fun `a pre-write-through memory row with no serverId is pushed`() = runBlocking {
        insertLocalMemory("guid-legacy")

        val report = MemoryBackfill.run(context, backend)

        assertEquals(1, report.pushed)
        assertEquals(0, report.alreadyPresent)
        assertTrue(report.failed.isEmpty())
        assertEquals(listOf("guid-legacy"), backend.memoryUpsertCalls)
    }

    @Test
    fun `a memory row already known-synced is not re-sent`() = runBlocking {
        insertLocalMemory("guid-synced", serverId = "srv-already-there")

        val report = MemoryBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.alreadyPresent)
        assertTrue(backend.memoryUpsertCalls.isEmpty())
    }

    @Test
    fun `a locally-deleted memory row that never synced is skipped, not resurrected`() = runBlocking {
        insertLocalMemory("guid-dead", deleted = true)

        val report = MemoryBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.skippedLocalOnlyDeleted)
        assertTrue(backend.memoryUpsertCalls.isEmpty())
    }

    @Test
    fun `a second backfill run is a genuine no-op`() = runBlocking {
        insertLocalMemory("guid-once")

        val first = MemoryBackfill.run(context, backend)
        val second = MemoryBackfill.run(context, backend)

        assertEquals(1, first.pushed)
        assertEquals(0, second.pushed)
        assertEquals(1, backend.memoryUpsertCalls.size)
    }

    @Test
    fun `every memory_audit row reaches the server through backfill alone, with no write-through`() = runBlocking {
        insertLocalAudit("audit-guid-1")

        val report = MemoryBackfill.run(context, backend)

        assertEquals(1, report.pushed)
        assertEquals(listOf("audit-guid-1"), backend.auditUpsertCalls)

        // A second run is a genuine no-op - the cursor advanced past this row.
        val second = MemoryBackfill.run(context, backend)
        assertEquals(0, second.pushed)
        assertEquals(1, backend.auditUpsertCalls.size)
    }

    @Test
    fun `a partial failure across mixed tables resumes without double-sending`() = runBlocking {
        insertLocalMemory("guid-ok-1")
        insertLocalMemory("guid-fails")
        insertLocalMemory("guid-ok-2")
        backend.failGuid = "guid-fails"

        val first = MemoryBackfill.run(context, backend)
        assertEquals(1, first.pushed) // only guid-ok-1, then this table's loop halts on guid-fails
        assertEquals(1, first.failed.size)
        assertTrue(first.failed.single().contains("guid-fails"))

        backend.failGuid = null
        val second = MemoryBackfill.run(context, backend)
        assertEquals(2, second.pushed)
        assertTrue(second.failed.isEmpty())
        assertEquals(listOf("guid-ok-1", "guid-fails", "guid-ok-2"), backend.memoryUpsertCalls)
    }

    @Test
    fun `running against empty tables is an honest zero, not silence`() = runBlocking {
        val report = MemoryBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(0, report.alreadyPresent)
        assertEquals(0, report.skippedLocalOnlyDeleted)
        assertTrue(report.failed.isEmpty())
    }

    // --- MemoryBackfill.runIfSignedIn - the cold-start guard --------------------------------------

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
        insertLocalMemory("guid-cold-start")
        val gateway = RetryOnceGateway(userIdOnceReady = "user-1")
        val auth = SupabaseAuth(context, gatewayProvider = { gateway })

        val report = MemoryBackfill.runIfSignedIn(context, auth, backend)

        assertEquals(1, report?.pushed)
        assertEquals(listOf("guid-cold-start"), backend.memoryUpsertCalls)
    }
}
