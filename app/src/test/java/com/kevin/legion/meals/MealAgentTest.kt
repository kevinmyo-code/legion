package com.kevin.legion.meals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises [MealAgent.parse] against canned model output - no network needed, same posture as
 * [com.kevin.legion.workouts.WorkoutPlanAgentTest]. Unlike
 * [com.kevin.legion.pantry.PantryReceiptAgentTest] there is no reconciliation arithmetic to
 * exercise here - see [MealAgent]'s doc comment for why - so these tests are purely about the
 * JSON shape surviving a round trip, including null macro fields.
 */
class MealAgentTest {

    @Test
    fun `happy path parses description and every macro`() {
        val raw = """
            {"description": "Chicken burrito bowl", "caloriesKcal": 650,
             "proteinG": 45.0, "carbsG": 60.0, "fatG": 20.0}
        """.trimIndent()

        val estimate = MealAgent.parse(raw, fallbackDescription = "burrito")
        assertNotNull(estimate)
        assertEquals("Chicken burrito bowl", estimate!!.description)
        assertEquals(650, estimate.caloriesKcal)
        assertEquals(45.0, estimate.proteinG!!, 0.001)
    }

    @Test
    fun `a null macro axis stays null rather than coerced to zero`() {
        val raw = """{"description": "Mystery smoothie", "caloriesKcal": null, "proteinG": 10.0, "carbsG": 30.0, "fatG": 2.0}"""
        val estimate = MealAgent.parse(raw, fallbackDescription = "smoothie")
        assertNotNull(estimate)
        assertNull(estimate!!.caloriesKcal)
        assertEquals(10.0, estimate.proteinG!!, 0.001)
    }

    @Test
    fun `a blank description falls back to what the driver said`() {
        val raw = """{"description": "", "caloriesKcal": 400, "proteinG": 20.0, "carbsG": 30.0, "fatG": 10.0}"""
        val estimate = MealAgent.parse(raw, fallbackDescription = "chicken and rice")
        assertEquals("chicken and rice", estimate!!.description)
    }

    @Test
    fun `malformed response returns null rather than a half-formed estimate`() {
        assertNull(MealAgent.parse("not json at all", fallbackDescription = "meal"))
    }
}
