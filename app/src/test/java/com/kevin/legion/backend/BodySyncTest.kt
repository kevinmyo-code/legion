package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MealTarget
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
 * [BodySync.pull] - exercised the same way [EventsSyncTest] exercises [EventsSync.pull]: against
 * an in-memory [FakeBodyBackend] and the real (Robolectric) local tables, never a network. Most
 * cases are proven once, against `bodyweight_logs`, because [BodyMerge.merge] is the ONE shared
 * implementation every one of the eight tables' sub-pulls calls - see that object's own class doc
 * for why testing it eight times by hand would be eight independent chances to get rule 6 wrong in
 * exactly one of them. [meal_targets] gets its own small test proving the one place that table's
 * wiring genuinely differs: its LWW clock is `updatedAt`, not a same-named `updatedAtMs`.
 */
@RunWith(RobolectricTestRunner::class)
class BodySyncTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Exposes every table's rows directly (like [EventsSyncTest]'s own `FakeEventsBackend`) so a
     * test can seed an arbitrary server snapshot without going through an upsert path at all. */
    private class FakeBodyBackend : BodyBackend {
        val bodyweightLogs = mutableMapOf<String, RemoteBodyweightLog>()
        val mealLogs = mutableMapOf<String, RemoteMealLog>()
        val mealTargets = mutableMapOf<String, RemoteMealTarget>()
        val sleepLogs = mutableMapOf<String, RemoteSleepLog>()
        val sleepTargets = mutableMapOf<String, RemoteSleepTarget>()
        val workoutPlans = mutableMapOf<String, RemoteWorkoutPlan>()
        val workoutPlanItems = mutableMapOf<String, RemoteWorkoutPlanItem>()
        val workoutSetLogs = mutableMapOf<String, RemoteWorkoutSetLog>()

        var failNextUpsert = false

        override suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long) = Result.success(bodyweightLogs.values.toList())
        override suspend fun upsertBodyweightLog(originGuid: String, fields: BodyweightLogFields): Result<RemoteBodyweightLog> {
            if (failNextUpsert) return Result.failure(BodyBackendException("forced failure"))
            val row = RemoteBodyweightLog(originGuid, fields.weightValue, fields.weightUnit, fields.loggedAtMs, fields.trustTier, fields.loggedAtMs, false, originGuid)
            bodyweightLogs[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteBodyweightLog(originGuid: String): Result<Boolean> {
            val existing = bodyweightLogs[originGuid] ?: return Result.success(false)
            bodyweightLogs[originGuid] = existing.copy(deleted = true)
            return Result.success(true)
        }

        override suspend fun fetchChangedMealLogsSince(sinceMs: Long) = Result.success(mealLogs.values.toList())
        override suspend fun upsertMealLog(originGuid: String, fields: MealLogFields) = Result.failure<RemoteMealLog>(BodyBackendException("not used"))
        override suspend fun softDeleteMealLog(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedMealTargetsSince(sinceMs: Long) = Result.success(mealTargets.values.toList())
        override suspend fun upsertMealTarget(originGuid: String, fields: MealTargetFields): Result<RemoteMealTarget> {
            val row = RemoteMealTarget(originGuid, fields.caloriesKcal, fields.proteinG, fields.carbsG, fields.fatG, fields.effectiveFromDateEpochMs, System.currentTimeMillis(), false, originGuid)
            mealTargets[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteMealTarget(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedSleepLogsSince(sinceMs: Long) = Result.success(sleepLogs.values.toList())
        override suspend fun upsertSleepLog(originGuid: String, fields: SleepLogFields) = Result.failure<RemoteSleepLog>(BodyBackendException("not used"))
        override suspend fun softDeleteSleepLog(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedSleepTargetsSince(sinceMs: Long) = Result.success(sleepTargets.values.toList())
        override suspend fun upsertSleepTarget(originGuid: String, fields: SleepTargetFields) = Result.failure<RemoteSleepTarget>(BodyBackendException("not used"))
        override suspend fun softDeleteSleepTarget(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutPlansSince(sinceMs: Long) = Result.success(workoutPlans.values.toList())
        override suspend fun upsertWorkoutPlan(originGuid: String, fields: WorkoutPlanFields) = Result.failure<RemoteWorkoutPlan>(BodyBackendException("not used"))
        override suspend fun softDeleteWorkoutPlan(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutPlanItemsSince(sinceMs: Long) = Result.success(workoutPlanItems.values.toList())
        override suspend fun upsertWorkoutPlanItem(originGuid: String, fields: WorkoutPlanItemFields) = Result.failure<RemoteWorkoutPlanItem>(BodyBackendException("not used"))
        override suspend fun softDeleteWorkoutPlanItem(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutSetLogsSince(sinceMs: Long) = Result.success(workoutSetLogs.values.toList())
        override suspend fun upsertWorkoutSetLog(originGuid: String, fields: WorkoutSetLogFields): Result<RemoteWorkoutSetLog> {
            if (failNextUpsert) return Result.failure(BodyBackendException("forced failure"))
            val row = RemoteWorkoutSetLog(originGuid, fields.exercise, fields.sets, fields.reps, fields.weightValue, fields.weightUnit, fields.loggedAtMs, fields.trustTier, fields.loggedAtMs, false, originGuid)
            workoutSetLogs[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteWorkoutSetLog(originGuid: String) = Result.failure<Boolean>(BodyBackendException("not used"))
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteBodyweight(guid: String, weight: Double, updatedAtMs: Long, deleted: Boolean = false) =
        RemoteBodyweightLog(serverId = "srv-$guid", weightValue = weight, weightUnit = "lbs", loggedAtMs = updatedAtMs, trustTier = "REPORTED", updatedAtMs = updatedAtMs, deleted = deleted, originGuid = guid)

    private suspend fun insertLocalBodyweight(guid: String, weight: Double, updatedAtMs: Long, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.bodyweightLogDao().insert(
            com.kevin.legion.data.local.BodyweightLog(
                weightValue = weight, weightUnit = "lbs", loggedAt = updatedAtMs,
                trustTier = com.kevin.legion.plan.TrustTier.REPORTED,
                guid = guid, updatedAtMs = updatedAtMs, deleted = deleted,
            ),
        )
    }

    @Test
    fun `a server-only row is inserted`() = runBlocking {
        val backend = FakeBodyBackend()
        backend.bodyweightLogs["guid-1"] = remoteBodyweight("guid-1", 180.0, 1_000L)

        val report = BodySync.pull(context, backend)

        assertEquals(1, report.inserted)
        val all = CarDatabase.getDatabase(context).bodyweightLogDao().getAll()
        assertEquals(1, all.size)
        assertEquals(180.0, all.single().weightValue, 0.0001)
        assertEquals("guid-1", all.single().guid)
    }

    @Test
    fun `a local-only row survives untouched`() = runBlocking {
        val id = insertLocalBodyweight("local-guid", 175.0, 1_000L)
        val backend = FakeBodyBackend() // server has nothing

        val report = BodySync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.updated)
        assertEquals(0, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).bodyweightLogDao().getAll().find { it.id == id }
        assertNotNull(stored)
        assertEquals(false, stored!!.deleted)
    }

    @Test
    fun `a newer local row is not overwritten by an older server row`() = runBlocking {
        val id = insertLocalBodyweight("guid-2", 200.0, 5_000L)
        val backend = FakeBodyBackend()
        backend.bodyweightLogs["guid-2"] = remoteBodyweight("guid-2", 150.0, 1_000L)

        val report = BodySync.pull(context, backend)

        assertEquals(0, report.updated)
        assertEquals(1, report.skippedLocalNewer)
        val stored = CarDatabase.getDatabase(context).bodyweightLogDao().getAll().find { it.id == id }
        assertEquals(200.0, stored!!.weightValue, 0.0001)
    }

    @Test
    fun `a newer server row is applied over an older local row`() = runBlocking {
        val id = insertLocalBodyweight("guid-3", 150.0, 1_000L)
        val backend = FakeBodyBackend()
        backend.bodyweightLogs["guid-3"] = remoteBodyweight("guid-3", 210.0, 5_000L)

        val report = BodySync.pull(context, backend)

        assertEquals(1, report.updated)
        val stored = CarDatabase.getDatabase(context).bodyweightLogDao().getAll().find { it.id == id }
        assertEquals(210.0, stored!!.weightValue, 0.0001)
    }

    @Test
    fun `a server tombstone soft-deletes the matching local row`() = runBlocking {
        val id = insertLocalBodyweight("guid-4", 190.0, 1_000L)
        val backend = FakeBodyBackend()
        backend.bodyweightLogs["guid-4"] = remoteBodyweight("guid-4", 190.0, 5_000L, deleted = true)

        val report = BodySync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).bodyweightLogDao().getAll().find { it.id == id }
        assertNotNull(stored)
        assertTrue(stored!!.deleted)
    }

    @Test
    fun `a tombstoned server row with no local match is skipped, never inserted`() = runBlocking {
        val backend = FakeBodyBackend()
        backend.bodyweightLogs["guid-ghost"] = remoteBodyweight("guid-ghost", 100.0, 1_000L, deleted = true)

        val report = BodySync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.tombstoned)
        assertEquals(1, report.skippedTombstoneNoLocalMatch)
        assertEquals(0, CarDatabase.getDatabase(context).bodyweightLogDao().getAll().size)
    }

    @Test
    fun `a second consecutive pull of the same server state is a no-op`() = runBlocking {
        val backend = FakeBodyBackend()
        backend.bodyweightLogs["guid-5"] = remoteBodyweight("guid-5", 180.0, 1_000L)

        val first = BodySync.pull(context, backend)
        assertEquals(1, first.inserted)

        val beforeSecond = CarDatabase.getDatabase(context).bodyweightLogDao().getAll()
        val second = BodySync.pull(context, backend)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        val afterSecond = CarDatabase.getDatabase(context).bodyweightLogDao().getAll()
        assertEquals(beforeSecond, afterSecond)
    }

    // --- meal_targets: proves the one place this table's wiring genuinely differs - LWW reads
    // `updatedAt`, never a same-named `updatedAtMs` (that table has no such column - see
    // MealTarget's own v60 doc comment). ------------------------------------------------------

    @Test
    fun `meal_targets LWW compares against updatedAt, not a separate updatedAtMs`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.mealTargetDao().upsert(
            MealTarget(caloriesKcal = 2000, proteinG = 100.0, carbsG = 200.0, fatG = 60.0, effectiveFromDateEpoch = 500L, updatedAt = 5_000L, guid = "target-guid"),
        )
        val backend = FakeBodyBackend()
        backend.mealTargets["target-guid"] = RemoteMealTarget("srv-target", 2200, 150.0, 220.0, 70.0, 500L, updatedAtMs = 1_000L, deleted = false, originGuid = "target-guid")

        val report = BodySync.pull(context, backend)

        // Server's updated_at (1000) is OLDER than local's updatedAt (5000) -> local wins.
        assertEquals(0, report.updated)
        assertEquals(1, report.skippedLocalNewer)
        val stored = db.mealTargetDao().getByEffectiveDate(500L)
        assertEquals(2000, stored!!.caloriesKcal)
    }
}
