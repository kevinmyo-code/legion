package com.kevin.legion.backend

import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.plan.TrustTier
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
 * [BodyBackfill] - a pre-write-through local row (no [serverId][BodyweightLog.serverId], never
 * pushed) reaches the server exactly once, a second run of the whole backfill is a genuine no-op,
 * and a mid-run failure resumes without double-sending. Exercised against `bodyweight_logs` as the
 * representative table, same posture [BodyOutboxDrainTest]'s own class doc states for why one
 * table stands in for all eight - [BodyBackfill.run] calls the identical generic `backfillTable`
 * helper for every one of them.
 */
@RunWith(RobolectricTestRunner::class)
class BodyBackfillTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Records every upsert call so a test can assert "pushed exactly once", not just "ended up
     * present" - a map alone cannot tell a genuine backfill push apart from an accidental second
     * one landing on the same key. */
    private class FakeBodyBackend : BodyBackend {
        val bodyweightUpsertCalls = mutableListOf<String>()
        var failGuid: String? = null

        override suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteBodyweightLog>())
        override suspend fun upsertBodyweightLog(originGuid: String, fields: BodyweightLogFields): Result<RemoteBodyweightLog> {
            if (originGuid == failGuid) return Result.failure(BodyBackendException("forced failure"))
            bodyweightUpsertCalls.add(originGuid)
            return Result.success(
                RemoteBodyweightLog(originGuid, fields.weightValue, fields.weightUnit, fields.loggedAtMs, fields.trustTier, fields.loggedAtMs, false, originGuid),
            )
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
    }

    private suspend fun insertLocalBodyweight(guid: String, serverId: String? = null, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.bodyweightLogDao().insert(
            BodyweightLog(
                weightValue = 180.0, weightUnit = "lbs", loggedAt = 1_000L, trustTier = TrustTier.REPORTED,
                guid = guid, serverId = serverId, updatedAtMs = 1_000L, deleted = deleted,
            ),
        )
    }

    @Test
    fun `a pre-write-through row with no serverId is pushed`() = runBlocking {
        insertLocalBodyweight("guid-legacy")

        val report = BodyBackfill.run(context, backend)

        assertEquals(1, report.pushed)
        assertEquals(0, report.alreadyPresent)
        assertTrue(report.failed.isEmpty())
        assertEquals(listOf("guid-legacy"), backend.bodyweightUpsertCalls)
    }

    @Test
    fun `a row already known-synced (serverId set) is not re-sent`() = runBlocking {
        insertLocalBodyweight("guid-synced", serverId = "srv-already-there")

        val report = BodyBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.alreadyPresent)
        assertTrue(backend.bodyweightUpsertCalls.isEmpty())
    }

    @Test
    fun `a locally-deleted row that never synced is skipped, not resurrected`() = runBlocking {
        insertLocalBodyweight("guid-dead", deleted = true)

        val report = BodyBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.skippedLocalOnlyDeleted)
        assertTrue(backend.bodyweightUpsertCalls.isEmpty())
    }

    @Test
    fun `a second backfill run is a genuine no-op`() = runBlocking {
        insertLocalBodyweight("guid-once")

        val first = BodyBackfill.run(context, backend)
        val second = BodyBackfill.run(context, backend)

        assertEquals(1, first.pushed)
        assertEquals(0, second.pushed)
        assertEquals(0, second.alreadyPresent)
        assertEquals(1, backend.bodyweightUpsertCalls.size)
    }

    @Test
    fun `a partial failure resumes without double-sending`() = runBlocking {
        insertLocalBodyweight("guid-ok-1")
        insertLocalBodyweight("guid-fails")
        insertLocalBodyweight("guid-ok-2")
        backend.failGuid = "guid-fails"

        val first = BodyBackfill.run(context, backend)
        assertEquals(1, first.pushed) // only guid-ok-1, then the run halts on guid-fails
        assertEquals(1, first.failed.size)
        assertTrue(first.failed.single().contains("guid-fails"))
        assertEquals(listOf("guid-ok-1"), backend.bodyweightUpsertCalls)

        // The failure clears; the retried run resumes exactly at guid-fails, never re-sending
        // guid-ok-1, and still reaches guid-ok-2 afterward.
        backend.failGuid = null
        val second = BodyBackfill.run(context, backend)
        assertEquals(2, second.pushed)
        assertTrue(second.failed.isEmpty())
        assertEquals(listOf("guid-ok-1", "guid-fails", "guid-ok-2"), backend.bodyweightUpsertCalls)
    }

    @Test
    fun `running against an empty table is an honest zero, not silence`() = runBlocking {
        val report = BodyBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(0, report.alreadyPresent)
        assertEquals(0, report.skippedLocalOnlyDeleted)
        assertTrue(report.failed.isEmpty())
    }

    // --- Untick cascade delete reaches the server (the "also fix" half of this ticket) ----------

    private suspend fun insertLocalWorkoutSetLog(guid: String, sourceListItemId: Long): Long {
        val db = CarDatabase.getDatabase(context)
        return db.workoutSetLogDao().insert(
            WorkoutSetLog(
                exercise = "Squat", sets = 3, reps = 5, weightValue = 135.0, weightUnit = "lbs",
                loggedAt = 1_000L, trustTier = TrustTier.REPORTED, sourceListItemId = sourceListItemId,
                guid = guid, updatedAtMs = 1_000L,
            ),
        )
    }

    @Test
    fun `the untick cascade soft-deletes and pushes the tombstone, never a bare local delete`() = runBlocking {
        BodyWriteThrough.backendOverride = backend
        try {
            insertLocalWorkoutSetLog("guid-swept", sourceListItemId = 42L)
            val db = CarDatabase.getDatabase(context)

            for (row in db.workoutSetLogDao().getActiveBySourceListItemId(42L)) {
                BodyWriteThrough.deleteWorkoutSetLog(context, row)
            }

            // The row still exists locally (soft-deleted), not hard-deleted out from under sync.
            val all = db.workoutSetLogDao().getAll()
            assertEquals(1, all.size)
            assertTrue(all.single().deleted)
            // The fake backend's softDeleteWorkoutSetLog always fails ("not used"), same as a real
            // offline push would - so the tombstone is durably queued rather than lost, exactly
            // what BodyOutboxDrain later retries. See BodyWriteThrough.deleteWorkoutSetLog's own
            // doc for why a still-pending CREATE is cancelled outright instead (not exercised
            // here, this row has no pending create - it was inserted directly, bypassing write-
            // through, standing in for a row that predates it).
            val outbox = db.outboxDao().getAll()
            assertEquals(1, outbox.size)
            assertEquals("soft_delete", outbox.single().operation)
            assertEquals("workout_set_logs", outbox.single().targetTable)
        } finally {
            BodyWriteThrough.backendOverride = null
        }
    }
}
