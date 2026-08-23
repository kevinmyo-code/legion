package com.kevin.legion.advisor

import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlanItem
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage of [GoalChecklist.forToday] and [GoalChecklist.weeklySplit] - no Context,
 * no Room, matching [GoalPlanAgentTest]'s own posture. The empty-plan case is the one ticket 04
 * calls out by name: "an empty or unaccepted plan reads as 'no plan yet', never as zero progress"
 * must be something [GoalChecklistDay.hasPlan] can actually distinguish, not just something the
 * derivation happens to return an empty list for.
 *
 * **Rewritten for ticket 08** ("the checklist prescribes a day, and a ticked day logs itself"),
 * which replaced ticket 07's "one exercise, one day, the whole weekly total" model with a genuine
 * daily prescription - every exercise now runs on every one of [WorkoutPlanItem]'s parent
 * `WorkoutPlan.sessionsPerWeek` days, each showing that exercise's own SHARE of the weekly target.
 * See [GoalChecklist]'s own class doc for the full account of what changed and why.
 */
class GoalChecklistTest {

    private fun mealTarget(kcal: Int = 2300, protein: Double = 180.0) =
        MealTarget(caloriesKcal = kcal, proteinG = protein, carbsG = 220.0, fatG = 70.0, effectiveFromDateEpoch = 0L, updatedAt = 0L)

    private fun sleepTarget(minutes: Int = 480) =
        SleepTarget(targetMinutes = minutes, effectiveFromDateEpoch = 0L, updatedAt = 0L)

    private fun workoutItem(exercise: String, sets: Int = 12, reps: Int? = null) =
        WorkoutPlanItem(exercise = exercise, targetSetsPerWeek = sets, effectiveFromWeekEpoch = 0L, updatedAt = 0L, repsPerSet = reps)

    // --- weeklySplit: the property the ticket names explicitly -----------------------------------

    @Test
    fun `weeklySplit always sums back to the original total`() {
        for (total in 0..40) {
            for (sessions in 1..7) {
                val split = GoalChecklist.weeklySplit(total, sessions)
                assertEquals("total=$total sessions=$sessions", total, split.sum())
                assertEquals("one entry per session", sessions, split.size)
            }
        }
    }

    @Test
    fun `weeklySplit gives the remainder to the earliest entries, never fractional`() {
        // 12 sets over 4 sessions divides evenly - the ticket's own worked example.
        assertEquals(listOf(3, 3, 3, 3), GoalChecklist.weeklySplit(12, 4))

        // 13 over 4: one leftover set, and it goes to the FIRST (earliest) entry, not the last.
        assertEquals(listOf(4, 3, 3, 3), GoalChecklist.weeklySplit(13, 4))

        // 15 over 4: three leftover sets, the first three entries each absorb one extra.
        assertEquals(listOf(4, 4, 4, 3), GoalChecklist.weeklySplit(15, 4))

        // Every entry an integer - never "3.25 sets".
        GoalChecklist.weeklySplit(13, 4).forEach { assertTrue(it >= 0) }
    }

    @Test
    fun `weeklySplit with one session puts the whole total on that one day`() {
        assertEquals(listOf(9), GoalChecklist.weeklySplit(9, 1))
    }

    // --- forToday: a daily SHARE, not the raw weekly total ----------------------------------------

    @Test
    fun `12 sets over 4 sessions renders 3 sets on each of the 4 assigned days`() {
        // assignedDays(4) = Monday, Tuesday, Thursday, Saturday (floor(i*7/4)) - every one of them
        // must show the same 3-set share for a plan with one exercise and no remainder.
        val item = workoutItem("Kettlebell swing", sets = 12)
        val expectedDays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)

        expectedDays.forEach { day ->
            val result = GoalChecklist.forToday(null, null, listOf(item), sessionsPerWeek = 4, today = day)
            assertEquals("3 sets - Kettlebell swing", result.items.single())
        }
        // The days NOT in the assigned set show no workout line at all - ticket 07's rest-day rule
        // still holds, it just now applies to whichever days the WHOLE PLAN doesn't train.
        DayOfWeek.values().filter { it !in expectedDays }.forEach { day ->
            val result = GoalChecklist.forToday(null, null, listOf(item), sessionsPerWeek = 4, today = day)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test
    fun `a reps-per-set prescription renders sets x reps, a null one renders sets-only`() {
        val withReps = workoutItem("Kettlebell swing", sets = 12, reps = 10)
        val withoutReps = workoutItem("Squat", sets = 12, reps = null)

        val result = GoalChecklist.forToday(null, null, listOf(withReps, withoutReps), sessionsPerWeek = 4, today = DayOfWeek.MONDAY)

        // Sorted alphabetically: "Kettlebell swing" before "Squat".
        assertEquals(
            listOf("3 sets x 10 reps - Kettlebell swing", "3 sets - Squat"),
            result.items,
        )
    }

    @Test
    fun `sessionsPerWeek null falls back to every day, never silently showing nothing`() {
        // No WorkoutPlan row on file at all - forToday must still show SOMETHING rather than
        // silently dropping a real, accepted WorkoutPlanItem for lack of a session count.
        val item = workoutItem("Pushups", sets = 7)
        DayOfWeek.values().forEach { day ->
            val result = GoalChecklist.forToday(null, null, listOf(item), sessionsPerWeek = null, today = day)
            assertEquals(1, result.items.size)
        }
    }

    @Test
    fun `sessionsPerWeek is clamped to seven even if a plan somehow claims more`() {
        val item = workoutItem("Pushups", sets = 7)
        val nine = GoalChecklist.forToday(null, null, listOf(item), sessionsPerWeek = 9, today = DayOfWeek.MONDAY)
        val seven = GoalChecklist.forToday(null, null, listOf(item), sessionsPerWeek = 7, today = DayOfWeek.MONDAY)
        assertEquals(seven.items, nine.items)
    }

    // --- the empty-plan case: "no plan yet", never zero -------------------------------------------

    @Test
    fun `nothing accepted reads as no plan, not as zero items`() {
        val day = GoalChecklist.forToday(mealTarget = null, sleepTarget = null, workoutItems = emptyList())

        assertFalse("no target/workout row anywhere means no plan has been accepted", day.hasPlan)
        assertTrue(day.items.isEmpty())
    }

    // --- hasPlan tracks existence of ANY of the three, independent of item count ----------------

    @Test
    fun `a meal target alone is still a plan`() {
        val day = GoalChecklist.forToday(mealTarget = mealTarget(), sleepTarget = null, workoutItems = emptyList())
        assertTrue(day.hasPlan)
        assertEquals(1, day.items.size)
    }

    @Test
    fun `a sleep target alone is still a plan`() {
        val day = GoalChecklist.forToday(mealTarget = null, sleepTarget = sleepTarget(), workoutItems = emptyList())
        assertTrue(day.hasPlan)
        assertEquals(1, day.items.size)
    }

    @Test
    fun `workout items alone are still a plan`() {
        val day = GoalChecklist.forToday(mealTarget = null, sleepTarget = null, workoutItems = listOf(workoutItem("Squat")))
        assertTrue(day.hasPlan)
        assertEquals(1, day.items.size)
    }

    // --- the full plan: every line present, workouts sorted for a stable diff -------------------

    @Test
    fun `a full plan on an assigned day shows meal, sleep, and every exercise's share`() {
        val day = GoalChecklist.forToday(
            mealTarget = mealTarget(kcal = 2300, protein = 180.0),
            sleepTarget = sleepTarget(minutes = 480),
            workoutItems = listOf(workoutItem("Squat", 9), workoutItem("Bench Press", 9)),
            sessionsPerWeek = 7,
            today = DayOfWeek.MONDAY,
        )

        assertTrue(day.hasPlan)
        // meal + sleep + BOTH exercises - sessionsPerWeek=7 means every exercise shows every day,
        // unlike ticket 07's "one exercise, one day" model this replaced.
        assertEquals(4, day.items.size)
        assertEquals("Hit 2300 kcal / 180g protein", day.items[0])
        assertEquals("Sleep 8h", day.items[1])
        // Sorted alphabetically (case-insensitive): Bench Press before Squat.
        assertTrue(day.items[2].endsWith("Bench Press"))
        assertTrue(day.items[3].endsWith("Squat"))
    }

    // --- number formatting: whole numbers print clean, fractional ones keep one decimal --------

    @Test
    fun `fractional protein and sleep hours print with one decimal`() {
        val day = GoalChecklist.forToday(
            mealTarget = mealTarget(kcal = 2300, protein = 172.5),
            sleepTarget = sleepTarget(minutes = 450), // 7.5h
            workoutItems = emptyList(),
        )
        assertEquals("Hit 2300 kcal / 172.5g protein", day.items[0])
        assertEquals("Sleep 7.5h", day.items[1])
    }

    @Test
    fun `whole-number protein and sleep hours print without a decimal`() {
        val day = GoalChecklist.forToday(
            mealTarget = mealTarget(kcal = 2300, protein = 180.0),
            sleepTarget = sleepTarget(minutes = 480), // 8h exactly
            workoutItems = emptyList(),
        )
        assertEquals("Hit 2300 kcal / 180g protein", day.items[0])
        assertEquals("Sleep 8h", day.items[1])
    }

    // --- stability: repeated calls with the same inputs (any order) derive identical text -------

    @Test
    fun `derivation is stable regardless of workout item input order`() {
        val a = GoalChecklist.forToday(null, null, listOf(workoutItem("Squat"), workoutItem("Bench Press"), workoutItem("Row")), sessionsPerWeek = 7)
        val b = GoalChecklist.forToday(null, null, listOf(workoutItem("Row"), workoutItem("Squat"), workoutItem("Bench Press")), sessionsPerWeek = 7)
        assertEquals(a.items, b.items)
    }

    // --- workoutLinesForDay: the structured half the end-of-day sweep matches against -----------

    @Test
    fun `workoutLinesForDay text matches forToday's own rendered lines exactly`() {
        val items = listOf(workoutItem("Squat", 9, reps = 5), workoutItem("Bench Press", 9))
        val forTodayLines = GoalChecklist.forToday(null, null, items, sessionsPerWeek = 7, today = DayOfWeek.WEDNESDAY).items
        val structuredLines = GoalChecklist.workoutLinesForDay(items, 7, DayOfWeek.WEDNESDAY).map { it.text }
        assertEquals(forTodayLines, structuredLines)
    }

    @Test
    fun `workoutLinesForDay returns the exercise and computed sets for matching back to a log`() {
        val items = listOf(workoutItem("Kettlebell swing", 12, reps = 10))
        val line = GoalChecklist.workoutLinesForDay(items, 4, DayOfWeek.MONDAY).single()
        assertEquals("Kettlebell swing", line.exercise)
        assertEquals(3, line.sets)
        assertEquals(10, line.reps)
    }
}
