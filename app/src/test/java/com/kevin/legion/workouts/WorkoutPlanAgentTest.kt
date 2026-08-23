package com.kevin.legion.workouts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [WorkoutPlanAgent.parse] against canned model output - no network needed, same
 * posture as [com.kevin.legion.pantry.PantryReceiptAgentTest] and
 * [com.kevin.legion.ledger.CategoryAgentTest] for the parts of an LLM-backed agent that ARE
 * unit-testable without a real Gemini call.
 */
class WorkoutPlanAgentTest {

    @Test
    fun `happy path parses sessions and exercises`() {
        val raw = """
            {"sessionsPerWeek": 3, "exercises": [
              {"name": "Squat", "targetSetsPerWeek": 12},
              {"name": "Bench Press", "targetSetsPerWeek": 9}
            ]}
        """.trimIndent()

        val draft = WorkoutPlanAgent.parse(raw)
        assertNotNull(draft)
        assertEquals(3, draft!!.sessionsPerWeek)
        assertEquals(12, draft.exercises["Squat"])
        assertEquals(9, draft.exercises["Bench Press"])
    }

    @Test
    fun `malformed response returns null rather than a half-formed plan`() {
        assertNull(WorkoutPlanAgent.parse("not json at all"))
    }

    @Test
    fun `zero sessionsPerWeek is rejected rather than silently accepted`() {
        val raw = """{"sessionsPerWeek": 0, "exercises": [{"name": "Squat", "targetSetsPerWeek": 12}]}"""
        assertNull(WorkoutPlanAgent.parse(raw))
    }

    @Test
    fun `empty exercises list is rejected rather than an empty plan being stored`() {
        val raw = """{"sessionsPerWeek": 3, "exercises": []}"""
        assertNull(WorkoutPlanAgent.parse(raw))
    }

    // --- goal-plans ticket 08: reps, without fabrication -------------------------------------------

    @Test
    fun `an exercise with a stated reps-per-set carries it in the separate reps map`() {
        val raw = """
            {"sessionsPerWeek": 4, "exercises": [
              {"name": "Kettlebell swing", "targetSetsPerWeek": 12, "repsPerSet": 10}
            ]}
        """.trimIndent()

        val draft = WorkoutPlanAgent.parse(raw)
        assertNotNull(draft)
        assertEquals(10, draft!!.repsPerSet["Kettlebell swing"])
    }

    @Test
    fun `an exercise with no repsPerSet field has no entry, never a fabricated zero`() {
        val raw = """{"sessionsPerWeek": 3, "exercises": [{"name": "Plank", "targetSetsPerWeek": 6}]}"""

        val draft = WorkoutPlanAgent.parse(raw)
        assertNotNull(draft)
        assertNull("an exercise the model gave no rep count for must have NO entry, not a guessed one", draft!!.repsPerSet["Plank"])
        assertTrue(draft.repsPerSet.isEmpty())
    }

    @Test
    fun `a zero or negative repsPerSet is treated the same as absent, never stored`() {
        val raw = """
            {"sessionsPerWeek": 3, "exercises": [
              {"name": "Plank", "targetSetsPerWeek": 6, "repsPerSet": 0}
            ]}
        """.trimIndent()

        val draft = WorkoutPlanAgent.parse(raw)
        assertNotNull(draft)
        assertNull(draft!!.repsPerSet["Plank"])
    }
}
