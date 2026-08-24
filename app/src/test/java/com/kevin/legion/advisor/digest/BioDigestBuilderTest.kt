package com.kevin.legion.advisor.digest

import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlan
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.workouts.weekStartEpoch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [BioDigestBuilder] over a real Room database (Robolectric, same shape as `GoalControllerTest`)
 * - the two things ticket 16 exists to gate: an empty domain reads [com.kevin.legion.advisor.DigestText.notLogged],
 * never a coerced zero, and every non-empty figure carries its [TrustTier]. Seeds rows directly
 * through the DAOs rather than through `MealController`/`WorkoutController` so a test never depends
 * on a live [com.kevin.legion.ai.SubAgent] call.
 */
@RunWith(RobolectricTestRunner::class)
class BioDigestBuilderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val builder = BioDigestBuilder()

    // Wall-clock on purpose: BioDigestBuilder itself anchors "today"/"this week" to
    // System.currentTimeMillis() (see BioDigestBuilder.kt:48), so a fixture that seeds "today"'s
    // rows must share the real clock or the INTAKE/SLEEP "today" assertions below go stale
    // against whatever the builder actually computed. Anything that needs to stay inside a single
    // ISO week (see the bodyweight test) derives its offset from `now`'s own week rather than a
    // fixed day-count back, so it can never cross the Monday boundary regardless of what day this
    // suite runs on.
    private val now = System.currentTimeMillis()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `a fully empty database reads not logged everywhere, never a bare zero`() = runBlocking {
        val digest = builder.build(context)

        // Four domains with nothing ever recorded: each must say so in words.
        val notLoggedCount = Regex("not logged").findAll(digest).count()
        assertTrue("expected WEIGHT/INTAKE/SESSIONS/SLEEP to all read not logged, got:\n$digest", notLoggedCount >= 4)

        // The literal failure ticket 08's own worked example names: a bare "0 kcal"/"0" standing in
        // for an unlogged figure. None of these domains has ever been written to, so nothing here
        // should print a lone digit where a real figure would go.
        assertFalse(digest.contains("0kcal"))
        assertFalse(digest.contains(Regex("""INTAKE\s+\(?\s*0\b""")))
    }

    @Test
    fun `bodyweight reports a weekly average, never one line per reading`() = runBlocking {
        val dao = CarDatabase.getDatabase(context).bodyweightLogDao()
        dao.insert(BodyweightLog(weightValue = 180.0, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED))
        // Anchored to the START of `now`'s own week rather than "now - 2 days": a fixed day-count
        // offset crosses the Monday boundary whenever this suite runs on a Monday or Tuesday (it
        // did, on 2026-08-18, and landed the second reading in the PREVIOUS week, silently
        // excluding it from the average and producing 180.0 instead of the asserted 181.0).
        // weekStartEpoch(now) is by definition inside `now`'s own week, so this can never cross.
        dao.insert(BodyweightLog(weightValue = 182.0, weightUnit = "lbs", loggedAt = weekStartEpoch(now), trustTier = TrustTier.REPORTED))

        val digest = builder.build(context)
        val weightLine = digest.lines().first { it.startsWith("WEIGHT") }

        // (180 + 182) / 2 = 181.0 - the AVERAGE, not either individual reading standing alone.
        assertTrue("expected the weekly average 181.0, got: $weightLine", weightLine.contains("181.0lbs"))
        assertFalse("a raw daily reading must not appear on its own", weightLine.contains("180.0lbs"))
        assertFalse(weightLine.contains("182.0lbs"))
        // Every BodyweightLog row is REPORTED by construction (see its own doc comment) - the
        // combined tier over a non-empty week must inherit that, never silently read PROVEN.
        assertTrue(weightLine.endsWith("[reported]"))
    }

    @Test
    fun `an unlogged day inside a target-set week is named, and a logged day's kcal is marked estimate`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        // Pinned to mid-week (weekStart + 2 days, noon-ish) via the clock-injectable overload: on
        // a real Monday run the live window holds only today, every day is logged, and the
        // "unlogged day" premise silently vanishes - this test failed exactly that way on
        // 2026-08-24 (a Monday), the same boundary the bodyweight test above documents.
        val pinnedNow = weekStartEpoch(now) + 2 * 86_400_000L + 12 * 60 * 60 * 1000L
        db.mealTargetDao().upsert(
            MealTarget(caloriesKcal = 2200, proteinG = 150.0, carbsG = 200.0, fatG = 70.0, effectiveFromDateEpoch = dayStartEpoch(pinnedNow), updatedAt = pinnedNow)
        )
        // One meal logged on the pinned "today"; Monday and Tuesday of the pinned week stay
        // unlogged, inside the window by construction.
        db.mealLogDao().insert(
            MealLog(description = "chicken and rice", caloriesKcal = 650, loggedAt = pinnedNow, trustTier = TrustTier.REPORTED)
        )

        val digest = builder.build(context, pinnedNow)
        val intakeLine = digest.lines().first { it.startsWith("INTAKE") }

        assertTrue("today's real kcal figure must be present", intakeLine.contains("650"))
        assertTrue("an unlogged day inside the window must say so in words", intakeLine.contains("not logged"))
        assertTrue("intake kcal is always an LLM guess (CLAUDE.md §4 rule 5) and must be marked", intakeLine.contains("(estimate)"))
    }

    @Test
    fun `sessions done versus planned this week carries the reported tier`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val weekStart = weekStartEpoch(now)
        db.workoutPlanDao().upsert(WorkoutPlan(sessionsPerWeek = 4, effectiveFromWeekEpoch = weekStart, updatedAt = now))
        db.workoutSetLogDao().insert(WorkoutSetLog(exercise = "squat", sets = 3, loggedAt = now, trustTier = TrustTier.REPORTED))

        val digest = builder.build(context)
        val sessionsLine = digest.lines().first { it.startsWith("SESSIONS") }

        assertTrue(sessionsLine.contains("wk0 1/4"))
        assertTrue(sessionsLine.endsWith("[reported]"))
    }

    @Test
    fun `a plateaued lift is named as the stalled exemplar`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val dayMs = 24 * 60 * 60 * 1000L
        db.workoutSetLogDao().insert(WorkoutSetLog(exercise = "bench", sets = 3, weightValue = 185.0, weightUnit = "lbs", loggedAt = now - 4 * dayMs, trustTier = TrustTier.REPORTED))
        db.workoutSetLogDao().insert(WorkoutSetLog(exercise = "bench", sets = 3, weightValue = 180.0, weightUnit = "lbs", loggedAt = now - 2 * dayMs, trustTier = TrustTier.REPORTED))
        db.workoutSetLogDao().insert(WorkoutSetLog(exercise = "bench", sets = 3, weightValue = 182.5, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED))

        val digest = builder.build(context)

        assertTrue("expected a LIFT line naming the stalled exercise, got:\n$digest", digest.contains("LIFT bench stalled 2 sessions"))
    }

    @Test
    fun `an active bio goal is carried without a tier tag`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.goalDao().insert(Goal(lineageId = 1, aspect = "bio", statement = "get to 175 lbs", targetValue = 175.0, unit = "lbs", metricKey = "bodyweight_kg"))

        val digest = builder.build(context)
        val goalLine = digest.lines().first { it.startsWith("GOAL") }

        assertEquals("GOAL get to 175 lbs target 175.0lbs", goalLine)
    }

    @Test
    fun `sleep vs target carries the reported tier when logged`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val dayStart = dayStartEpoch(now)
        db.sleepTargetDao().upsert(SleepTarget(targetMinutes = 480, effectiveFromDateEpoch = dayStart, updatedAt = now))
        db.sleepLogDao().insert(SleepLog(sleepDate = dayStart, durationMinutes = 420, loggedAt = now, trustTier = TrustTier.REPORTED))

        val digest = builder.build(context)
        val sleepLine = digest.lines().first { it.startsWith("SLEEP") }

        assertTrue(sleepLine.contains("target 8h"))
        assertTrue(sleepLine.contains("actual 7h"))
        assertTrue(sleepLine.endsWith("[reported]"))
    }
}
