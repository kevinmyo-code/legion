package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
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
 * [LastAspectsBackfill] - a pre-write-through local row (no `serverId`, never pushed) reaches the
 * server exactly once, a second run is a genuine no-op, and a mid-run failure resumes without
 * double-sending. Exercised against `goals`, the one table this slice still carries after the
 * lists redesign slice 1a; the `grocery_staples` natural-key, no-cursor path went with that table's
 * sync, so only the autoincrement-id cursor path remains to represent. Same "one representative per
 * code path" posture [LedgerConfigBackfillTest]'s own class doc states.
 */
@RunWith(RobolectricTestRunner::class)
class LastAspectsBackfillTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeLastAspectsBackend : LastAspectsBackend {
        val goalUpsertCalls = mutableListOf<String>()
        var failGoalGuid: String? = null

        override suspend fun fetchChangedGoalsSince(sinceMs: Long) = Result.success(emptyList<RemoteGoal>())
        override suspend fun upsertGoal(originGuid: String, fields: GoalFields): Result<RemoteGoal> {
            if (originGuid == failGoalGuid) return Result.failure(LastAspectsBackendException("forced failure"))
            goalUpsertCalls.add(originGuid)
            return Result.success(RemoteGoal("srv-$originGuid", fields.lineageId, fields.aspect, fields.statement, fields.targetValue, fields.unit, fields.metricKey, fields.deadlineEpoch, fields.status, fields.supersedesGuid, fields.closedAt, fields.createdAt, fields.createdAt, false, originGuid))
        }
        override suspend fun softDeleteGoal(originGuid: String) = Result.success(true)
    }

    private lateinit var backend: FakeLastAspectsBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = FakeLastAspectsBackend()
    }

    private suspend fun insertGoal(syncId: String, serverId: String? = null, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.goalDao().insert(Goal(lineageId = 1L, aspect = "bio", statement = "stmt-$syncId", createdAt = 1_000L, syncId = syncId, serverId = serverId, deleted = deleted))
    }

    // --- goals: autoincrement-id cursor path -----------------------------------------------------

    @Test
    fun `a pre-write-through goal row with no serverId is pushed`() = runBlocking {
        insertGoal("guid-legacy")

        val report = LastAspectsBackfill.run(context, backend)

        assertEquals(1, report.pushed)
        assertEquals(0, report.alreadyPresent)
        assertTrue(report.failed.isEmpty())
        assertEquals(listOf("guid-legacy"), backend.goalUpsertCalls)
    }

    @Test
    fun `a goal row already known-synced is not re-sent`() = runBlocking {
        insertGoal("guid-synced", serverId = "srv-already-there")

        val report = LastAspectsBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.alreadyPresent)
        assertTrue(backend.goalUpsertCalls.isEmpty())
    }

    @Test
    fun `a second goal backfill run is a genuine no-op`() = runBlocking {
        insertGoal("guid-once")

        val first = LastAspectsBackfill.run(context, backend)
        val second = LastAspectsBackfill.run(context, backend)

        assertEquals(1, first.pushed)
        assertEquals(0, second.pushed)
        assertEquals(1, backend.goalUpsertCalls.size)
    }

    @Test
    fun `a mid-run goal failure resumes without double-sending`() = runBlocking {
        insertGoal("guid-ok-1")
        insertGoal("guid-fails")
        backend.failGoalGuid = "guid-fails"

        val first = LastAspectsBackfill.run(context, backend)
        assertEquals(1, first.pushed)
        assertEquals(1, first.failed.size)

        backend.failGoalGuid = null
        val second = LastAspectsBackfill.run(context, backend)
        // Only "guid-fails" is retried here - the cursor already advanced past "guid-ok-1" on the
        // first run (unlike LedgerConfigBackfillTest's own three-row version of this test, which
        // has a THIRD ok row after the failing one to also pick up on the retry).
        assertEquals(1, second.pushed)
        assertTrue(second.failed.isEmpty())
        assertEquals(listOf("guid-ok-1", "guid-fails"), backend.goalUpsertCalls)
    }

    @Test
    fun `running against an empty database is an honest zero, not silence`() = runBlocking {
        val report = LastAspectsBackfill.run(context, backend)

        assertTrue(report.failed.isEmpty())
        assertEquals(0, report.pushed)
        assertEquals(0, report.alreadyPresent)
    }
}
