package com.kevin.legion.advisor

import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlanItem
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
        val day = GoalChecklist.forToday(mealTarget = null, sleepTarget = null, workoutItems = listOf(workoutItem("Squat")))
        assertTrue(day.hasPlan)
        assertEquals(1, day.items.size)
    }

    // --- the full plan: every line present, workouts sorted for a stable diff -------------------

    @Test
    fun `a full plan produces one line per target plus one per exercise, exercises sorted`() {
        val day = GoalChecklist.forToday(
            mealTarget = mealTarget(kcal = 2300, protein = 180.0),
            sleepTarget = sleepTarget(minutes = 480),
            workoutItems = listOf(workoutItem("Squat", 9), workoutItem("Bench Press", 9)),
        )

        assertTrue(day.hasPlan)
        assertEquals(4, day.items.size)
        assertEquals("Hit 2300 kcal / 180g protein", day.items[0])
        assertEquals("Sleep 8h", day.items[1])
        // sorted case-insensitively - "Bench Press" before "Squat" regardless of input order
        assertEquals("Bench Press: 9 sets this week", day.items[2])
        assertEquals("Squat: 9 sets this week", day.items[3])
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
