package com.kevin.legion.backend

import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [BodyWriteThrough]/[BodyOutboxDrain] - a failed push enqueues, and a later successful drain
 * clears it. Mirrors [EventsOutboxDrainTest]'s own shape exactly, against `bodyweight_logs` as the
 * representative table (every one of the eight write-through functions follows the identical
 * write-local-first/enqueue-on-failure shape - see [BodyWriteThrough]'s own class doc).
 */
@RunWith(RobolectricTestRunner::class)
class BodyOutboxDrainTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeBodyBackend : BodyBackend {
        var bodyweightUpsertResult: Result<RemoteBodyweightLog> = Result.failure(BodyBackendException("not set"))
        var bodyweightUpsertCallCount = 0

        override suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteBodyweightLog>())
        override suspend fun upsertBodyweightLog(originGuid: String, fields: BodyweightLogFields): Result<RemoteBodyweightLog> {
            bodyweightUpsertCallCount++
            return bodyweightUpsertResult
        }
        override suspend fun softDeleteBodyweightLog(originGuid: String) = Result.success(true)

        override suspend fun fetchChangedMealLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteMealLog>())
        override suspend fun upsertMealLog(originGuid: String, fields: MealLogFields) = Result.failure<RemoteMealLog>(BodyBackendException("not used"))
        override suspend fun softDeleteMealLog(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedMealTargetsSince(sinceMs: Long) = Result.success(emptyList<RemoteMealTarget>())
        override suspend fun upsertMealTarget(originGuid: String, fields: MealTargetFields) = Result.failure<RemoteMealTarget>(BodyBackendException("not used"))
        override suspend fun softDeleteMealTarget(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedSleepLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteSleepLog>())
        override suspend fun upsertSleepLog(originGuid: String, fields: SleepLogFields) = Result.failure<RemoteSleepLog>(BodyBackendException("not used"))
        override suspend fun softDeleteSleepLog(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedSleepTargetsSince(sinceMs: Long) = Result.success(emptyList<RemoteSleepTarget>())
        override suspend fun upsertSleepTarget(originGuid: String, fields: SleepTargetFields) = Result.failure<RemoteSleepTarget>(BodyBackendException("not used"))
        override suspend fun softDeleteSleepTarget(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutPlansSince(sinceMs: Long) = Result.success(emptyList<RemoteWorkoutPlan>())
        override suspend fun upsertWorkoutPlan(originGuid: String, fields: WorkoutPlanFields) = Result.failure<RemoteWorkoutPlan>(BodyBackendException("not used"))
        override suspend fun softDeleteWorkoutPlan(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutPlanItemsSince(sinceMs: Long) = Result.success(emptyList<RemoteWorkoutPlanItem>())
        override suspend fun upsertWorkoutPlanItem(originGuid: String, fields: WorkoutPlanItemFields) = Result.failure<RemoteWorkoutPlanItem>(BodyBackendException("not used"))
        override suspend fun softDeleteWorkoutPlanItem(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutSetLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteWorkoutSetLog>())
        override suspend fun upsertWorkoutSetLog(originGuid: String, fields: WorkoutSetLogFields) = Result.failure<RemoteWorkoutSetLog>(BodyBackendException("not used"))
        override suspend fun softDeleteWorkoutSetLog(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))
    }

    private lateinit var backend: FakeBodyBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = FakeBodyBackend()
        BodyWriteThrough.backendOverride = backend
    }

    @After
    fun tearDown() {
        BodyWriteThrough.backendOverride = null
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun freshRow(guid: String) = BodyweightLog(
        weightValue = 180.0, weightUnit = "lbs", loggedAt = 1_000L, trustTier = TrustTier.REPORTED,
        guid = guid, updatedAtMs = 1_000L,
    )

    @Test
    fun `a failed push enqueues an outbox entry, never losing the write`() = runBlocking {
        backend.bodyweightUpsertResult = Result.failure(BodyBackendException("offline"))

        val row = BodyWriteThrough.addBodyweightLog(context, freshRow("guid-a"))

        // Local write always lands regardless of the push outcome.
        val stored = CarDatabase.getDatabase(context).bodyweightLogDao().getAll()
        assertEquals(1, stored.size)
        assertEquals(row.guid, stored.single().guid)
        // And the failed push is durably queued, not silently dropped.
        val outbox = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals(1, outbox.size)
        assertEquals("bodyweight_logs", outbox.single().targetTable)
        assertEquals("upsert", outbox.single().operation)
    }

    @Test
    fun `a later successful drain clears the queued entry`() = runBlocking {
        backend.bodyweightUpsertResult = Result.failure(BodyBackendException("offline"))
        BodyWriteThrough.addBodyweightLog(context, freshRow("guid-b"))
        assertEquals(1, CarDatabase.getDatabase(context).outboxDao().getAll().size)

        backend.bodyweightUpsertResult = Result.success(
            RemoteBodyweightLog("srv-b", 180.0, "lbs", 1_000L, "REPORTED", 1_000L, false, "guid-b"),
        )
        val report = BodyOutboxDrain.drain(context, backend)

        assertEquals(1, report.succeeded)
        assertEquals(0, report.stillPending)
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }

    @Test
    fun `draining an already-drained outbox a second time is a genuine no-op`() = runBlocking {
        backend.bodyweightUpsertResult = Result.failure(BodyBackendException("offline"))
        BodyWriteThrough.addBodyweightLog(context, freshRow("guid-c"))
        backend.bodyweightUpsertResult = Result.success(
            RemoteBodyweightLog("srv-c", 180.0, "lbs", 1_000L, "REPORTED", 1_000L, false, "guid-c"),
        )

        BodyOutboxDrain.drain(context, backend)
        val second = BodyOutboxDrain.drain(context, backend)

        assertEquals(0, second.succeeded)
        assertEquals(0, second.stillPending)
        assertEquals(0, second.poisoned)
    }

    @Test
    fun `an unconfigured install pushes nothing and queues nothing`() = runBlocking {
        BodyWriteThrough.backendOverride = null
        val client = SupabaseClientProvider.get(context)
        // Guard the test's own assumption: no Supabase project is configured in this test env.
        assertEquals(null, client)

        BodyWriteThrough.addBodyweightLog(context, freshRow("guid-d"))

        assertEquals(1, CarDatabase.getDatabase(context).bodyweightLogDao().getAll().size)
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }
}
