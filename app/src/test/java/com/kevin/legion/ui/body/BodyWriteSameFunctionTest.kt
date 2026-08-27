package com.kevin.legion.ui.body

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.meals.MealController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.sleep.SleepController
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.workouts.WorkoutController
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 03 (`.scratch/command-center/issues/03-body-writes-by-hand.md`) verification: "a test
 * that UI write path and voice write path reach the same function per stream". `ui/body/
 * BodyWriteDialogs.kt` calls the controller functions below DIRECTLY (see that file's own doc
 * comment) - the same functions `LiveToolbox.dispatch`'s matching voice tool calls. Each test
 * below calls the DIALOG-shaped call first, then the VOICE-tool-shaped call (`LiveToolbox.dispatch`
 * with the exact JSON args the model would send) into the SAME database, and checks both rows
 * landed with the shape each caller asked for. This is not a test of the Compose layer itself (this
 * repo has no `createComposeRule` harness - noted as `reasoned`, not `tested`, in the ticket
 * report) - it is the strongest assertion available without one, and it is exactly what a code
 * review would otherwise have to eyeball off the import lines in `BodyWriteDialogs.kt` and
 * `LiveToolbox.kt`.
 *
 * [CarDatabase.getDatabase] opens a REAL named database file (`DATABASE_FILE_NAME`), not an
 * in-memory one - so unlike [RoomTestReset]'s usual one-reset-per-@Test-method shape, calling it a
 * second time mid-test would reconnect to the SAME on-disk rows rather than a clean slate. These
 * tests therefore keep ONE continuous database per test method and distinguish the two calls by
 * asserting on the specific rows each one produced, rather than resetting in between.
 *
 * Robolectric + real [CarDatabase], same shape as [com.kevin.legion.service.LiveToolboxGoalPlanTest].
 * No Gemini key is configured in this environment, so [MealController.logMeal]'s LLM estimate call
 * always takes its own caught-exception "no usable response" path and writes a row with null
 * macros - deterministic either way the row got there, per that function's own doc comment ("a meal
 * log is written even if some macro fields come back null").
 */
@RunWith(RobolectricTestRunner::class)
class BodyWriteSameFunctionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    // ---------------------------------------------------------------------- meal (INTAKE)

    @Test
    fun `LogMealDialog and log_meal both write through MealController logMeal`() = runBlocking {
        // Not asserted on the row's exact DESCRIPTION text: MealController.logMeal hands whatever
        // was typed/said to MealAgent's LLM estimate, which is free to return a reworded
        // description (see MealAgent.parse - only a BLANK model description falls back to the
        // input verbatim). On a machine with no Gemini key configured (`-Pnokey`, CI's own posture)
        // that call always fails and falls back to the input unchanged; on a machine WITH a key
        // (Kevin's own dev convenience build, per CLAUDE.md sec 6) it can genuinely rewrite the
        // text, and asserting exact equality here would make this test pass or fail depending on
        // whose machine ran it rather than on whether both callers reached the same function - the
        // exact flakiness this rewrite fixes (caught running the full suite keyed, not `-Pnokey`).
        // Row COUNT is what proves both calls landed through MealController.logMeal into the same
        // table, and that claim is true independent of the LLM's wording.
        val before = CarDatabase.getDatabase(context).mealLogDao().getRecent(10).size

        // The dialog's call (BodyWriteDialogs.kt: MealController.logMeal(context, description.trim())).
        MealController.logMeal(context, "chicken burrito bowl")
        // The voice tool's call (LiveToolbox.kt dispatch: MealController.logMeal(context, args.optString("description"))).
        LiveToolbox.dispatch(context, "log_meal", JSONObject().put("description", "protein shake"))

        val after = CarDatabase.getDatabase(context).mealLogDao().getRecent(10)
        assertEquals("both calls landed a row - same function, same table", before + 2, after.size)
    }

    @Test
    fun `SetMealTargetDialog and set_meal_target both write through MealController setTarget`() = runBlocking {
        // The dialog's call (BodyWriteDialogs.kt: MealController.setTarget(context, calories, protein, carbs, fat)).
        MealController.setTarget(context, 2200, 150.0, 220.0, 70.0)
        val afterDialog = CarDatabase.getDatabase(context).mealTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))!!
        assertEquals(2200, afterDialog.caloriesKcal)

        // The voice tool's call (LiveToolbox.kt dispatch: MealController.setTarget(...)) - a target
        // is copy-forward (one effective row at a time), so this call SUPERSEDES the dialog's own
        // write, exactly as a second voice call would supersede a first voice call.
        LiveToolbox.dispatch(
            context, "set_meal_target",
            JSONObject().put("calories", 2400).put("protein_g", 160.0).put("carbs_g", 230.0).put("fat_g", 75.0),
        )
        val afterVoice = CarDatabase.getDatabase(context).mealTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))!!
        assertEquals("the voice call's own function (MealController.setTarget) landed its own value", 2400, afterVoice.caloriesKcal)
    }

    // ---------------------------------------------------------------------- sleep

    @Test
    fun `LogSleepDialog and log_sleep both write through SleepController logSleep`() = runBlocking {
        // The dialog's call (BodyWriteDialogs.kt: SleepController.logSleep(context, durationHours=..., ...)).
        SleepController.logSleep(context, durationHours = 7.5, quality = 4, notes = null, sleepDateOverride = null)
        // The voice tool's call (LiveToolbox.kt logSleep(): SleepController.logSleep(...)).
        LiveToolbox.dispatch(context, "log_sleep", JSONObject().put("duration_hours", 6.0).put("quality", 2))

        val rows = CarDatabase.getDatabase(context).sleepLogDao().getRecent(10)
        assertEquals("both calls landed a row - same function, same table", 2, rows.size)
        assertTrue(rows.any { it.durationMinutes == 450 && it.quality == 4 })
        assertTrue(rows.any { it.durationMinutes == 360 && it.quality == 2 })
    }

    @Test
    fun `SetSleepTargetDialog and set_sleep_target both write through SleepController setTarget`() = runBlocking {
        SleepController.setTarget(context, 8.0)
        val afterDialog = CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))!!
        assertEquals(480, afterDialog.targetMinutes)

        LiveToolbox.dispatch(context, "set_sleep_target", JSONObject().put("hours", 7.0))
        val afterVoice = CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))!!
        assertEquals("the voice call's own function (SleepController.setTarget) landed its own value", 420, afterVoice.targetMinutes)
    }

    // ---------------------------------------------------------------------- mass (bodyweight)

    @Test
    fun `LogBodyweightDialog and log_bodyweight both write through WorkoutController logBodyweight`() = runBlocking {
        // The dialog's call (BodyWriteDialogs.kt: WorkoutController.logBodyweight(context, weight, unit)).
        WorkoutController.logBodyweight(context, 183.5, "lbs")
        // The voice tool's call (LiveToolbox.kt dispatch: WorkoutController.logBodyweight(context, weight, unit)).
        LiveToolbox.dispatch(context, "log_bodyweight", JSONObject().put("weight", 83.0).put("weight_unit", "kg"))

        val rows = CarDatabase.getDatabase(context).bodyweightLogDao().getRecent(10)
        assertEquals("both calls landed a row - same function, same table", 2, rows.size)
        assertTrue(rows.any { it.weightValue == 183.5 && it.weightUnit == "lbs" })
        assertTrue(rows.any { it.weightValue == 83.0 && it.weightUnit == "kg" })
    }

    // ---------------------------------------------------------------------- training (workout set)

    @Test
    fun `LogWorkoutSetDialog and log_workout_set both write through WorkoutController logSet`() = runBlocking {
        // The dialog's call (BodyWriteDialogs.kt: WorkoutController.logSet(context, exercise, sets, reps, weight, unit)).
        val viaDialogOutcome = WorkoutController.logSet(context, "Squat", 3, 5, 225.0, "lbs")
        assertTrue(viaDialogOutcome.success)
        // The voice tool's call (LiveToolbox.kt logWorkoutSet(): WorkoutController.logSet(...)).
        LiveToolbox.dispatch(
            context, "log_workout_set",
            JSONObject().put("exercise", "Pushups").put("sets", 3).put("reps", 15),
        )

        val rows = CarDatabase.getDatabase(context).workoutSetLogDao().getRecent(10)
        assertEquals("both calls landed a row - same function, same table", 2, rows.size)
        assertTrue(rows.any { it.exercise == "Squat" && it.sets == 3 && it.weightValue == 225.0 })
        assertTrue(rows.any { it.exercise == "Pushups" && it.sets == 3 && it.weightValue == null })
    }

    // ------------------------------------------------------------------------- delete

    /**
     * Ticket 03 build item 3's "per-row delete via the DAO the controller already owns" claim,
     * checked directly: [WorkoutController.deleteBodyweightLog] (what a MASS drilldown row's `DEL`
     * calls) is the SAME function `undo_last_log`'s dispatch calls for a bodyweight row - logging
     * one row and letting `undo_last_log` remove it proves the voice path reaches this exact
     * function; a second row then shows a UI row calling that same function directly, by its own
     * specific log rather than "whichever was most recent", removes it identically.
     */
    @Test
    fun `undo_last_log and a per-row DEL both remove a bodyweight row via WorkoutController deleteBodyweightLog`() = runBlocking {
        WorkoutController.logBodyweight(context, 183.5, "lbs")

        // Voice path: undo_last_log's dispatch resolves the same row (it's the only candidate) and
        // deletes it via WorkoutController.deleteBodyweightLog.
        LiveToolbox.dispatch(context, "undo_last_log", JSONObject())
        assertEquals(0, CarDatabase.getDatabase(context).bodyweightLogDao().getRecent(10).size)

        // UI path: a MASS drilldown row's DEL calls the identical function directly against a
        // specific row (BodyScreen.kt's onDelete lambda for BodyMassDrilldown).
        WorkoutController.logBodyweight(context, 190.0, "lbs")
        val second = CarDatabase.getDatabase(context).bodyweightLogDao().mostRecent()!!
        WorkoutController.deleteBodyweightLog(context, second)
        assertEquals(0, CarDatabase.getDatabase(context).bodyweightLogDao().getRecent(10).size)
    }
}
