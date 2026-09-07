package com.kevin.legion.service

import com.kevin.legion.backend.BodyBackend
import com.kevin.legion.backend.BodyBackendException
import com.kevin.legion.backend.BodyWriteThrough
import com.kevin.legion.backend.BodyweightLogFields
import com.kevin.legion.backend.MealLogFields
import com.kevin.legion.backend.MealTargetFields
import com.kevin.legion.backend.RemoteBodyweightLog
import com.kevin.legion.backend.RemoteMealLog
import com.kevin.legion.backend.RemoteMealTarget
import com.kevin.legion.backend.RemoteSleepLog
import com.kevin.legion.backend.RemoteSleepTarget
import com.kevin.legion.backend.RemoteWorkoutPlan
import com.kevin.legion.backend.RemoteWorkoutPlanItem
import com.kevin.legion.backend.RemoteWorkoutSetLog
import com.kevin.legion.backend.SleepLogFields
import com.kevin.legion.backend.SleepTargetFields
import com.kevin.legion.backend.WorkoutPlanFields
import com.kevin.legion.backend.WorkoutPlanItemFields
import com.kevin.legion.backend.WorkoutSetLogFields
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The `success` flag on six tool results, which used to be the constant `true`.
 *
 * **This is CLAUDE.md section 7's outcome-verb rule at the tool boundary.** `CANNOT_CLAUSE` is
 * conditioned on the tool RESULT - "an unsuccessful result is the same as no tool at all" - so a
 * flag that says `true` over a message reading "That meal didn't go through: ..." does not merely
 * fail to help, it actively defeats the clause. The model receives
 * `{"success": true, "message": "<a failure>"}` and the honest reading of that pair is that the
 * write landed.
 *
 * The defect was introduced by nothing: the flag was hardcoded from the beginning and was harmless
 * while the local write happened first and unconditionally. `.scratch/django-engine/issues/15-*`
 * made body writes push BEFORE they store, so a REFUSED write now reaches no table at all - the
 * messages learnt to say so and the flags did not. `log_workout_set` and `remember` already derived
 * theirs (`WorkoutController.WriteOutcome`, `AriaBrain.RememberOutcome`); these six now do too.
 *
 * **Every case here refuses at the BACKEND**, which is the only place a refusal can come from on
 * the server-first path, and asserts on the JSON the model would actually receive rather than on
 * the controller's return value - the controller is already covered by
 * [com.kevin.legion.backend.BodyServerFirstWriteTest].
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxWriteFlagTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Refuses every write with a 400 carrying [REFUSAL]; every read answers empty. A 400 is the
     * one branch [com.kevin.legion.backend.WriteThroughOutcome.Refused] covers - a 5xx or an
     * unreachable engine queues instead and is a genuine local success. */
    private class RefusingBackend : BodyBackend {
        private fun <T> no() = Result.failure<T>(EngineHttpException(EngineFailure.Refused(400, REFUSAL)))

        override suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteBodyweightLog>())
        override suspend fun upsertBodyweightLog(originGuid: String, fields: BodyweightLogFields) =
            no<RemoteBodyweightLog>()
        override suspend fun softDeleteBodyweightLog(originGuid: String) = no<Boolean>()

        override suspend fun fetchChangedMealLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteMealLog>())
        override suspend fun upsertMealLog(originGuid: String, fields: MealLogFields) = no<RemoteMealLog>()
        override suspend fun softDeleteMealLog(originGuid: String) = no<Boolean>()

        override suspend fun fetchChangedMealTargetsSince(sinceMs: Long) = Result.success(emptyList<RemoteMealTarget>())
        override suspend fun upsertMealTarget(originGuid: String, fields: MealTargetFields) = no<RemoteMealTarget>()
        override suspend fun softDeleteMealTarget(originGuid: String) = no<Boolean>()

        override suspend fun fetchChangedSleepLogsSince(sinceMs: Long) = Result.success(emptyList<RemoteSleepLog>())
        override suspend fun upsertSleepLog(originGuid: String, fields: SleepLogFields) = no<RemoteSleepLog>()
        override suspend fun softDeleteSleepLog(originGuid: String) = no<Boolean>()

        override suspend fun fetchChangedSleepTargetsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteSleepTarget>())
        override suspend fun upsertSleepTarget(originGuid: String, fields: SleepTargetFields) = no<RemoteSleepTarget>()
        override suspend fun softDeleteSleepTarget(originGuid: String) = no<Boolean>()

        override suspend fun fetchChangedWorkoutPlansSince(sinceMs: Long) =
            Result.success(emptyList<RemoteWorkoutPlan>())
        override suspend fun upsertWorkoutPlan(originGuid: String, fields: WorkoutPlanFields) = no<RemoteWorkoutPlan>()
        override suspend fun softDeleteWorkoutPlan(originGuid: String) = no<Boolean>()

        override suspend fun fetchChangedWorkoutPlanItemsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteWorkoutPlanItem>())
        override suspend fun upsertWorkoutPlanItem(originGuid: String, fields: WorkoutPlanItemFields) =
            no<RemoteWorkoutPlanItem>()
        override suspend fun softDeleteWorkoutPlanItem(originGuid: String) = no<Boolean>()

        override suspend fun fetchChangedWorkoutSetLogsSince(sinceMs: Long) =
            Result.success(emptyList<RemoteWorkoutSetLog>())
        override suspend fun upsertWorkoutSetLog(originGuid: String, fields: WorkoutSetLogFields) =
            no<RemoteWorkoutSetLog>()
        override suspend fun softDeleteWorkoutSetLog(originGuid: String) =
            Result.failure<Boolean>(BodyBackendException("not used"))
    }

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        BodyWriteThrough.backendOverride = RefusingBackend()
        // The override supplies the backend; the transport supplies the ORDER. Only on Django does
        // a refusal stop the local write, which is what makes success = false the honest answer.
        EngineTransport(context).setTransport(EngineBackends.ASPECT_BODY, Transport.DJANGO)
    }

    @After
    fun tearDown() {
        BodyWriteThrough.backendOverride = null
        EngineTransport(context).setTransport(EngineBackends.ASPECT_BODY, Transport.SUPABASE)
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun dispatch(name: String, vararg pairs: Pair<String, Any?>): JSONObject = runBlocking {
        val args = JSONObject()
        for ((k, v) in pairs) args.put(k, v)
        LiveToolbox.dispatch(context, name, args)!!
    }

    private fun assertRefused(result: JSONObject) {
        val message = result.optString("message")
        assertFalse(
            "a refused write must not come back as success = true, message was: $message",
            result.optBoolean("success"),
        )
        assertTrue(
            "and the server's own sentence reaches the model verbatim: $message",
            message.contains(REFUSAL),
        )
    }

    @Test
    fun `log_meal reports a refused write as a failure`() {
        assertRefused(dispatch("log_meal", "description" to "chicken burrito bowl"))
    }

    @Test
    fun `set_meal_target reports a refused write as a failure`() {
        assertRefused(
            dispatch(
                "set_meal_target",
                "calories" to 2200,
                "protein_g" to 150.0,
                "carbs_g" to 220.0,
                "fat_g" to 70.0,
            ),
        )
    }

    @Test
    fun `log_sleep reports a refused write as a failure`() {
        assertRefused(dispatch("log_sleep", "duration_hours" to 7.5))
    }

    @Test
    fun `set_sleep_target reports a refused write as a failure`() {
        assertRefused(dispatch("set_sleep_target", "hours" to 8.0))
    }

    @Test
    fun `log_bodyweight reports a refused write as a failure`() {
        assertRefused(dispatch("log_bodyweight", "weight" to 183.5, "weight_unit" to "lbs"))
    }

    @Test
    fun `log_sleep refuses an implausible duration without claiming it logged one`() {
        // Not a backend refusal at all - SleepController rejects the duration before any write.
        // The old dispatch reported this as success = true too, so it is the same defect reached
        // by a second road, and no phone or engine is needed to hit it.
        val result = dispatch("log_sleep", "duration_hours" to 99.0)

        assertFalse(result.optBoolean("success"))
        assertTrue(result.optString("message").contains("real duration"))
    }

    @Test
    fun `create_workout_plan reports a plan it could not draft as a failure`() {
        // WorkoutPlanAgent needs the user's own Gemini key, which no test environment has, so
        // generatePlan takes its "I couldn't put a plan together" branch and writes nothing. That
        // branch is exactly what used to be reported as success = true: a plan that does not exist,
        // announced as set.
        val result = dispatch("create_workout_plan", "goal" to "get stronger")

        assertFalse(
            "a plan that was never drafted is not a success: ${result.optString("message")}",
            result.optBoolean("success"),
        )
    }

    private companion object {
        /** The shape `server/api/body.py`'s own validators produce - a sentence meant to be shown
         * to a person, which `EngineFailure.Refused` carries verbatim. */
        const val REFUSAL = "description cannot be blank."
    }
}
