package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.workouts.WorkoutController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Body writes push BEFORE they touch Room on the Django transport, and behave exactly as they
 * always did on Supabase (`.scratch/django-engine/issues/15-*`, the precondition ticket 14 names).
 *
 * **Every assertion is about ORDER, which is only observable through its consequences**: whether a
 * row is in Room, whether an [com.kevin.legion.data.local.OutboxEntry] exists, and what the
 * controller said. The four cases are the four branches of [WriteThroughOutcome], and each one is
 * a different sentence to a user:
 *
 * | Engine answers | Room | Outbox | What is said |
 * |---|---|---|---|
 * | 400, "exercise cannot be blank." | nothing | nothing | the server's own words, success = false |
 * | 500 | the row | queued | logged, plus "not on the server yet" - never a refusal |
 * | unreachable | the row | queued | logged, plus "not on the server yet" |
 * | 400, but on the SUPABASE transport | the row | queued | logged - byte-identical to before |
 *
 * The last row is the one that would be easy to break by accident, and it is the one ticket 15's
 * brief protects explicitly: "nothing may change for an install that has not flipped".
 *
 * **The 400 case is also how the six duplicated Kotlin guards would be deleted safely.** The
 * server's `exercise cannot be blank.` (`server/api/body.py`'s `WorkoutSetLogSerializer`) reaches
 * the driver in words with nothing written, which is precisely what [WorkoutController.logSet]'s
 * own blank-exercise guard does today - so on THIS transport the guard is genuinely redundant.
 * See the report for why it was not deleted: `body` still defaults to Supabase, where the local
 * write happens first and the identical CHECK constraint would poison the row instead.
 */
@RunWith(RobolectricTestRunner::class)
class BodyServerFirstWriteTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Answers every workout-set upsert with [setLogResult]; nothing else in this test's paths is
     * ever called. Kept local to this file rather than shared with [BodyOutboxDrainTest]'s own
     * fake, which answers a different table. */
    private class FakeBodyBackend : BodyBackend {
        var setLogResult: Result<RemoteWorkoutSetLog> = Result.failure(BodyBackendException("not set"))
        var setLogPushes = 0

        override suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteBodyweightLog>())
        override suspend fun upsertBodyweightLog(originGuid: String, fields: BodyweightLogFields) =
            Result.failure<RemoteBodyweightLog>(BodyBackendException("not used"))
        override suspend fun softDeleteBodyweightLog(originGuid: String) = Result.success(true)

        override suspend fun fetchChangedMealLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteMealLog>())
        override suspend fun upsertMealLog(originGuid: String, fields: MealLogFields) =
            Result.failure<RemoteMealLog>(BodyBackendException("not used"))
        override suspend fun softDeleteMealLog(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedMealTargetsSince(sinceMs: Long) = Result.success(emptyList<RemoteMealTarget>())
        override suspend fun upsertMealTarget(originGuid: String, fields: MealTargetFields) =
            Result.failure<RemoteMealTarget>(BodyBackendException("not used"))
        override suspend fun softDeleteMealTarget(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedSleepLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteSleepLog>())
        override suspend fun upsertSleepLog(originGuid: String, fields: SleepLogFields) =
            Result.failure<RemoteSleepLog>(BodyBackendException("not used"))
        override suspend fun softDeleteSleepLog(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedSleepTargetsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteSleepTarget>())
        override suspend fun upsertSleepTarget(originGuid: String, fields: SleepTargetFields) =
            Result.failure<RemoteSleepTarget>(BodyBackendException("not used"))
        override suspend fun softDeleteSleepTarget(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutPlansSince(sinceMs: Long) =
            Result.success(emptyList<RemoteWorkoutPlan>())
        override suspend fun upsertWorkoutPlan(originGuid: String, fields: WorkoutPlanFields) =
            Result.failure<RemoteWorkoutPlan>(BodyBackendException("not used"))
        override suspend fun softDeleteWorkoutPlan(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutPlanItemsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteWorkoutPlanItem>())
        override suspend fun upsertWorkoutPlanItem(originGuid: String, fields: WorkoutPlanItemFields) =
            Result.failure<RemoteWorkoutPlanItem>(BodyBackendException("not used"))
        override suspend fun softDeleteWorkoutPlanItem(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))

        override suspend fun fetchChangedWorkoutSetLogsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteWorkoutSetLog>())
        override suspend fun upsertWorkoutSetLog(
            originGuid: String,
            fields: WorkoutSetLogFields,
        ): Result<RemoteWorkoutSetLog> {
            setLogPushes++
            return setLogResult
        }
        override suspend fun softDeleteWorkoutSetLog(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))
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

    /** The override supplies the backend; the transport supplies the ORDER, and they are separate
     * knobs on purpose - see `BodyWriteThrough.route`'s own doc comment. */
    private fun onDjango() = EngineTransport(context).setTransport(EngineBackends.ASPECT_BODY, Transport.DJANGO)

    private fun refused(status: Int, body: String) =
        Result.failure<RemoteWorkoutSetLog>(EngineHttpException(EngineFailure.Refused(status, body)))

    private suspend fun logOneSet() = WorkoutController.logSet(
        context, exercise = "goblet squat", sets = 3, reps = 10, weightValue = null, weightUnit = null,
    )

    @Test
    fun `a 400 writes nothing to Room, queues nothing, and hands back the server's own sentence`() = runBlocking {
        onDjango()
        backend.setLogResult = refused(400, "exercise cannot be blank.")

        val outcome = logOneSet()

        assertFalse("a refused write is not a success", outcome.success)
        assertTrue(
            "the server's own words reach the caller verbatim, never a paraphrase: ${outcome.message}",
            outcome.message.contains("exercise cannot be blank."),
        )
        val db = CarDatabase.getDatabase(context)
        assertTrue("a refused row must never reach Room", db.workoutSetLogDao().getAll().isEmpty())
        assertTrue("a refused row must never be queued for retry", db.outboxDao().getAll().isEmpty())
        assertEquals("and the push was actually attempted", 1, backend.setLogPushes)
    }

    /**
     * The A25 defect of 2026-09-07, in a test: the raw DRF envelope reached the user as
     * `That set didn't go through: {"reps":["0 is not a valid reps. It must be greater than 0."]}`.
     * Every real refusal from `server/api/body.py` arrives in this shape - a serializer's
     * `ValidationError` on a named field - so the case above (a bare sentence in the body) is the
     * one that never happens in production and this one is the one that always does.
     */
    @Test
    fun `a DRF field-error body reaches the user as a readable sentence, not JSON`() = runBlocking {
        onDjango()
        backend.setLogResult = refused(400, """{"reps":["0 is not a valid reps. It must be greater than 0."]}""")

        val outcome = logOneSet()

        assertFalse("a refused write is not a success", outcome.success)
        assertEquals(
            "the envelope is gone and the engine's own sentence is all that is left",
            "That set didn't go through: 0 is not a valid reps. It must be greater than 0.",
            outcome.message,
        )
        assertFalse("no brace may survive into spoken copy: ${outcome.message}", outcome.message.contains("{"))
        assertFalse("nor an escaped quote: ${outcome.message}", outcome.message.contains("\\\""))
    }

    /**
     * DRF's other refusal shape, `{"detail": ...}` - what an `APIException` renders as, where the
     * case above is what a serializer's per-field `ValidationError` renders as. Unwrapped by the
     * same helper and read out the same way.
     *
     * **400, not 403, and that is not an arbitrary choice.** `EngineHttp.classify` files 401 and
     * 403 under [EngineFailure.Unauthorized], never under [EngineFailure.Refused] - see
     * [com.kevin.legion.location.PlaceController.engineRefusal]'s own doc comment - so a
     * `Refused(403, ...)` is a shape production can never produce and testing it would prove
     * nothing about the app.
     */
    @Test
    fun `a DRF detail body reads the same way`() = runBlocking {
        onDjango()
        backend.setLogResult = refused(400, """{"detail":"JSON parse error - Expecting value."}""")

        val outcome = logOneSet()

        assertFalse(outcome.success)
        assertEquals(
            "That set didn't go through: JSON parse error - Expecting value.",
            outcome.message,
        )
    }

    @Test
    fun `a 500 is not a refusal - the write lands locally, queues, and says so`() = runBlocking {
        onDjango()
        // EngineHttp.classify files 5xx under Refused too (it keeps the status). "The engine
        // failed on its side" is a fault, not something the user asked for and was told no about,
        // so this must NOT take the refusal branch.
        backend.setLogResult = refused(500, "The engine failed on its side (HTTP 500).")

        val outcome = logOneSet()

        assertTrue("a server fault must not read as a user refusal", outcome.success)
        assertFalse("nothing may claim the write was rejected", outcome.message.contains("didn't go through"))
        assertTrue(
            "and it says the row is not on the server yet: ${outcome.message}",
            outcome.message.contains("not on the server yet"),
        )
        val db = CarDatabase.getDatabase(context)
        assertEquals("the row is kept", 1, db.workoutSetLogDao().getAll().size)
        assertEquals("and queued for retry", 1, db.outboxDao().getAll().size)
    }

    @Test
    fun `an unreachable engine still writes locally, queues, and says so in words`() = runBlocking {
        onDjango()
        backend.setLogResult = Result.failure(
            EngineHttpException(
                EngineFailure.Unreachable("http://192.168.1.117:8000", "Nothing was sent - the engine is unreachable."),
            ),
        )

        val outcome = logOneSet()

        // ADR 0044 rule 4: reads keep working from Room, the write queues, and the app says so.
        assertTrue(outcome.success)
        assertTrue(
            "the queue is stated, not implied: ${outcome.message}",
            outcome.message.contains("not on the server yet"),
        )
        assertTrue("with the reason named: ${outcome.message}", outcome.message.contains("unreachable"))
        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.workoutSetLogDao().getAll().size)
        assertEquals(1, db.outboxDao().getAll().size)
        assertEquals("workout_set_logs", db.outboxDao().getAll().single().targetTable)
    }

    @Test
    fun `on the Supabase transport a refusal behaves exactly as it did before this ticket`() = runBlocking {
        // No setTransport call: `body` defaults to SUPABASE and this install has not flipped.
        assertEquals(Transport.SUPABASE, EngineTransport(context).transportFor(EngineBackends.ASPECT_BODY))
        backend.setLogResult = refused(400, "exercise cannot be blank.")

        val outcome = logOneSet()

        // The old behaviour, unchanged and deliberately so: local write first, push after, queue
        // on failure, and not a word about it. That is what makes the six duplicated Kotlin guards
        // still load-bearing on this transport.
        assertTrue(outcome.success)
        assertTrue("no new words on the unflipped path: ${outcome.message}", outcome.message.endsWith("logged."))
        val db = CarDatabase.getDatabase(context)
        assertEquals("the row is written locally regardless of the refusal", 1, db.workoutSetLogDao().getAll().size)
        assertEquals("and the failed push is queued", 1, db.outboxDao().getAll().size)
    }

    @Test
    fun `a successful push writes the row and says nothing extra`() = runBlocking {
        onDjango()
        backend.setLogResult = Result.success(
            RemoteWorkoutSetLog(
                serverId = "srv-1", exercise = "goblet squat", sets = 3, reps = 10, weightValue = null,
                weightUnit = null, loggedAtMs = 1_000L, trustTier = "REPORTED", updatedAtMs = 1_000L,
                deleted = false, originGuid = "guid-1",
            ),
        )

        val outcome = logOneSet()

        assertTrue(outcome.success)
        assertTrue("a sent write says nothing about queues: ${outcome.message}", outcome.message.endsWith("logged."))
        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.workoutSetLogDao().getAll().size)
        assertTrue(db.outboxDao().getAll().isEmpty())
    }
}
