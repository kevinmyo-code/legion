package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The same four branches [BodyServerFirstWriteTest] pins down, for `companion_memories` - see that
 * file's class doc for the table of cases and why order is only observable through consequences.
 *
 * **Memory is where a refused row does the most damage**, which is why it gets its own file rather
 * than a case in body's: [com.kevin.legion.ai.MemoryConsolidator] and
 * [com.kevin.legion.ai.ReflectionEngine] write here unattended, from a transcript they then
 * DELETE, and each follows its write with a `WRITTEN` audit line pointing at the row's id. A row
 * that never landed has no id, so the audit branch is asserted here too: no row, no audit claim.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryServerFirstWriteTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeMemoryBackend : MemoryBackend {
        var companionResult: Result<RemoteCompanionMemory> = Result.failure(MemoryBackendException("not set"))
        var companionPushes = 0

        override suspend fun fetchChangedMemoryEntriesSince(sinceMs: Long) =
            Result.success(emptyList<RemoteMemoryEntry>())
        override suspend fun upsertMemoryEntry(originGuid: String, fields: MemoryEntryFields) =
            Result.failure<RemoteMemoryEntry>(MemoryBackendException("not used"))
        override suspend fun softDeleteMemoryEntry(originGuid: String) =
            Result.failure<Boolean>(MemoryBackendException("not used"))

        override suspend fun fetchChangedCompanionMemoriesSince(sinceMs: Long) =
            Result.success(emptyList<RemoteCompanionMemory>())
        override suspend fun upsertCompanionMemory(
            originGuid: String,
            fields: CompanionMemoryFields,
        ): Result<RemoteCompanionMemory> {
            companionPushes++
            return companionResult
        }
        override suspend fun softDeleteCompanionMemory(originGuid: String) =
            Result.failure<Boolean>(MemoryBackendException("not used"))

        override suspend fun fetchChangedMemoryAuditSince(sinceMs: Long) =
            Result.success(emptyList<RemoteMemoryAudit>())
        override suspend fun upsertMemoryAudit(originGuid: String, fields: MemoryAuditFields) =
            Result.failure<RemoteMemoryAudit>(MemoryBackendException("not used"))
    }

    private lateinit var backend: FakeMemoryBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = FakeMemoryBackend()
        MemoryWriteThrough.backendOverride = backend
    }

    @After
    fun tearDown() {
        MemoryWriteThrough.backendOverride = null
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun onDjango() = EngineTransport(context).setTransport(EngineBackends.ASPECT_MEMORY, Transport.DJANGO)

    private fun freshMemory() = CompanionMemory(
        vehicleId = "veh-1",
        text = "prefers the 6am gym slot",
        category = CompanionMemory.Category.DRIVER,
        source = CompanionMemory.Source.CONSOLIDATED,
        importance = 5,
        createdAt = 1_000L,
        lastAccessedAt = 1_000L,
        updatedAtMs = 1_000L,
    )

    @Test
    fun `a 400 writes no memory, queues nothing, and carries the server's own sentence`() = runBlocking {
        onDjango()
        backend.companionResult = Result.failure(
            EngineHttpException(EngineFailure.Refused(400, "text cannot be blank.")),
        )

        val outcome = MemoryWriteThrough.addCompanionMemory(context, freshMemory())

        assertTrue(outcome is WriteThroughOutcome.Refused)
        assertEquals("text cannot be blank.", (outcome as WriteThroughOutcome.Refused).message)
        // No row means no id, which is what stops the unattended passes writing a WRITTEN audit
        // line for a memory that does not exist.
        assertNull("a refusal carries no row", outcome.row)
        val db = CarDatabase.getDatabase(context)
        assertTrue(db.companionMemoryDao().getAll().isEmpty())
        assertTrue(db.outboxDao().getAll().isEmpty())
        assertEquals(1, backend.companionPushes)
    }

    /**
     * Memory shares [refusalSentence] with body, so it leaked the raw DRF envelope identically -
     * see that function's own doc comment for the sentence the A25 spoke on 2026-09-07. This is
     * the same fix seen from the other caller, because the same helper serves both and a fix
     * proven on one says nothing about the other reaching it.
     */
    @Test
    fun `a DRF field-error body is unwrapped before it reaches the caller`() = runBlocking {
        onDjango()
        backend.companionResult = Result.failure(
            EngineHttpException(EngineFailure.Refused(400, """{"text":["This field may not be blank."]}""")),
        )

        val outcome = MemoryWriteThrough.addCompanionMemory(context, freshMemory())

        assertTrue(outcome is WriteThroughOutcome.Refused)
        assertEquals(
            "This field may not be blank.",
            (outcome as WriteThroughOutcome.Refused).message,
        )
        val db = CarDatabase.getDatabase(context)
        assertTrue("still nothing written", db.companionMemoryDao().getAll().isEmpty())
        assertTrue("still nothing queued", db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun `a 500 keeps the memory and queues it, and is not reported as a refusal`() = runBlocking {
        onDjango()
        backend.companionResult = Result.failure(
            EngineHttpException(EngineFailure.Refused(503, "The engine failed on its side (HTTP 503).")),
        )

        val outcome = MemoryWriteThrough.addCompanionMemory(context, freshMemory())

        assertTrue("a fault queues, it does not refuse", outcome is WriteThroughOutcome.Queued)
        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.companionMemoryDao().getAll().size)
        assertEquals(1, db.outboxDao().getAll().size)
        // The row carries the id Room assigned, so the caller's audit line still points somewhere
        // real - the reason this branch hands back a row at all.
        assertTrue("the queued row carries its local id", (outcome.row?.id ?: 0L) > 0L)
    }

    @Test
    fun `an unreachable engine keeps the memory and queues it`() = runBlocking {
        onDjango()
        backend.companionResult = Result.failure(
            EngineHttpException(EngineFailure.Unreachable("http://192.168.1.117:8000", "Nothing was sent.")),
        )

        val outcome = MemoryWriteThrough.addCompanionMemory(context, freshMemory())

        assertTrue(outcome is WriteThroughOutcome.Queued)
        assertEquals("Nothing was sent.", (outcome as WriteThroughOutcome.Queued).reason)
        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.companionMemoryDao().getAll().size)
        assertEquals("companion_memories", db.outboxDao().getAll().single().targetTable)
    }

    @Test
    fun `on the Supabase transport a refusal behaves exactly as it did before this ticket`() = runBlocking {
        assertEquals(Transport.SUPABASE, EngineTransport(context).transportFor(EngineBackends.ASPECT_MEMORY))
        backend.companionResult = Result.failure(
            EngineHttpException(EngineFailure.Refused(400, "text cannot be blank.")),
        )

        val outcome = MemoryWriteThrough.addCompanionMemory(context, freshMemory())

        assertTrue("the unflipped path never reports a refusal", outcome is WriteThroughOutcome.StoredLocally)
        val db = CarDatabase.getDatabase(context)
        assertEquals(
            "local write first, unconditionally, exactly as before",
            1,
            db.companionMemoryDao().getAll().size,
        )
        assertEquals("and the failed push queued, exactly as before", 1, db.outboxDao().getAll().size)
    }
}
