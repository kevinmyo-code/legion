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
 * Pure-function coverage of [GoalChecklist.forToday] - no Context, no Room, matching
 * [GoalPlanAgentTest]'s own posture. The empty-plan case is the one ticket 04 calls out by name:
 * "an empty or unaccepted plan reads as 'no plan yet', never as zero progress" must be something
 * [GoalChecklistDay.hasPlan] can actually distinguish, not just something the derivation happens
 * to return an empty list for.
 */
class GoalChecklistTest {

    private fun mealTarget(kcal: Int = 2300, protein: Double = 180.0) =
        MealTarget(caloriesKcal = kcal, proteinG = protein, carbsG = 220.0, fatG = 70.0, effectiveFromDateEpoch = 0L, updatedAt = 0L)

    private fun sleepTarget(minutes: Int = 480) =
        SleepTarget(targetMinutes = minutes, effectiveFromDateEpoch = 0L, updatedAt = 0L)

    private fun workoutItem(exercise: String, sets: Int = 9) =
        WorkoutPlanItem(exercise = exercise, targetSetsPerWeek = sets, effectiveFromWeekEpoch = 0L, updatedAt = 0L)

    // --- the empty-plan case: "no plan yet", never zero -----------------------------------------

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
        // A single session always lands on index 0 - Monday, whatever the total - so this stays
        // true against the default `today` (Monday) with no explicit day needed.
        val day = GoalChecklist.forToday(mealTarget = null, sleepTarget = null, workoutItems = listOf(workoutItem("Squat")))
        assertTrue(day.hasPlan)
        assertEquals(1, day.items.size)
    }

    // --- the full plan: every line present, workouts sorted for a stable diff -------------------

    @Test
    fun `a full plan, on the day its one scheduled exercise falls, shows meal, sleep, and that exercise`() {
        // Two exercises sort to Bench Press, Squat (case-insensitive alphabetical) - index 0
        // (Bench Press) lands on Monday, index 1 (Squat) lands on Thursday (dayForIndex(1, 2)).
        val day = GoalChecklist.forToday(
            mealTarget = mealTarget(kcal = 2300, protein = 180.0),
            sleepTarget = sleepTarget(minutes = 480),
            workoutItems = listOf(workoutItem("Squat", 9), workoutItem("Bench Press", 9)),
            today = DayOfWeek.MONDAY,
        )

        assertTrue(day.hasPlan)
        assertEquals(3, day.items.size)
        assertEquals("Hit 2300 kcal / 180g protein", day.items[0])
        assertEquals("Sleep 8h", day.items[1])
        assertEquals("Bench Press: 9 sets this week", day.items[2])
    }

    // --- ticket 07: a rest day shows no workout line at all, but nutrition/sleep still stand ----

    @Test
    fun `the same two-exercise plan on a day neither exercise is assigned shows no workout line`() {
        val day = GoalChecklist.forToday(
            mealTarget = mealTarget(kcal = 2300, protein = 180.0),
            sleepTarget = sleepTarget(minutes = 480),
            workoutItems = listOf(workoutItem("Squat", 9), workoutItem("Bench Press", 9)),
            today = DayOfWeek.TUESDAY, // neither Monday (Bench Press) nor Thursday (Squat)
        )

        assertEquals(2, day.items.size)
        assertTrue("a rest day must not print a 'rest' line either - nutrition/sleep stand alone", day.items.none { it.contains("sets this week") })
    }

    // --- ticket 07's own worked example: three sessions become Mon/Wed/Fri -----------------------

    @Test
    fun `three sessions spread across three distinct days - Monday, Wednesday, Friday`() {
        val items = listOf(workoutItem("Bench Press"), workoutItem("Row"), workoutItem("Squat"))

        val daysWithASession = DayOfWeek.values().filter { day ->
            GoalChecklist.forToday(null, null, items, today = day).items.isNotEmpty()
        }

        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), daysWithASession)
        // and each of those days carries exactly one session, never two stacked on the same day
        daysWithASession.forEach { day ->
            assertEquals(1, GoalChecklist.forToday(null, null, items, today = day).items.size)
        }
    }

    // --- ticket 07: a plan change reassigns days for the NEXT read, but this function has no way
    // to reach into the past - already-ticked history lives in GoalChecklistSync/NotesController,
    // never here. See GoalChecklistSyncTest for the Room-level proof that a past day's ticked item
    // survives a re-materialization under a changed plan. --------------------------------------

    @Test
    fun `a mid-week plan change reassigns which day an exercise falls on`() {
        // Squat is the last exercise alphabetically in both plans, but the SAME exercise lands on
        // a different day once the plan around it changes - a revision is a new spread, not an
        // edit of one line in place.
        val twoExercisePlan = listOf(workoutItem("Bench Press"), workoutItem("Squat"))
        val fourExercisePlan = listOf(workoutItem("Bench Press"), workoutItem("Pushups"), workoutItem("Row"), workoutItem("Squat"))

        fun squatsDay(plan: List<WorkoutPlanItem>): DayOfWeek =
            DayOfWeek.values().first { day -> GoalChecklist.forToday(null, null, plan, today = day).items.any { it.startsWith("Squat") } }

        val squatDayUnderTwoExercises = squatsDay(twoExercisePlan)
        val squatDayUnderFourExercises = squatsDay(fourExercisePlan)

        assertEquals(DayOfWeek.THURSDAY, squatDayUnderTwoExercises)
        assertEquals(DayOfWeek.SATURDAY, squatDayUnderFourExercises)
        assertTrue("the plan change must actually move the day, not coincidentally reuse it", squatDayUnderTwoExercises != squatDayUnderFourExercises)
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
        val a = GoalChecklist.forToday(null, null, listOf(workoutItem("Squat"), workoutItem("Bench Press"), workoutItem("Row")))
        val b = GoalChecklist.forToday(null, null, listOf(workoutItem("Row"), workoutItem("Squat"), workoutItem("Bench Press")))
        assertEquals(a.items, b.items)
    }
}
