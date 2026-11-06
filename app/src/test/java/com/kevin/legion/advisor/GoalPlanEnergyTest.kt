package com.kevin.legion.advisor

import com.kevin.legion.bodycoach.EnergyWire
import com.kevin.legion.finance.FinanceRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fat-loss ticket 11 (map decision 5): the plan generator never invents a calorie target. With the
 * engine's estimate the plan carries the engine's kcal and protein whatever the model wrote; with
 * none there is no meal target and a refusal asks for what is missing.
 */
class GoalPlanEnergyTest {

    private val modelPlan = GoalPlan(
        rationale = "A starting point.",
        mealTarget = GoalPlanMealTarget(caloriesKcal = 1900, proteinG = 140.0, carbsG = 180.0, fatG = 60.0),
        goals = listOf(GoalPlanGoal(aspect = "bio", statement = "get lean, keep muscle")),
    )

    private val estimate = EnergyWire(
        basis = "formula", maintenanceKcal = 2950, suggestedTargetKcal = 2200, proteinG = 175, targetRatePct = 0.75,
    )

    @Test
    fun `with an estimate the engine's kcal and protein replace the model's`() {
        val plan = GoalPlanAgent.applyEnergy(modelPlan, FinanceRead.Fresh(estimate))
        assertEquals(2200, plan.mealTarget!!.caloriesKcal)
        assertEquals(175.0, plan.mealTarget!!.proteinG, 0.0)
        assertEquals("carbs kept from the model", 180.0, plan.mealTarget!!.carbsG, 0.0)
        assertTrue(plan.refusals.isEmpty())
    }

    @Test
    fun `with no profile there is no meal target and the refusal names what is missing`() {
        val missing = FinanceRead.Fresh(EnergyWire(missing = listOf("height", "birth year", "activity level")))
        val plan = GoalPlanAgent.applyEnergy(modelPlan, missing)
        assertNull(plan.mealTarget)
        val refusal = plan.refusals.single()
        assertTrue(refusal, refusal.startsWith("meal target"))
        assertTrue(refusal, refusal.contains("height, birth year and activity level"))
        assertNotNull("the rest of the plan stands", plan.goals.singleOrNull())
    }

    @Test
    fun `an unreadable engine also means no meal target, said in words`() {
        val plan = GoalPlanAgent.applyEnergy(modelPlan, FinanceRead.Unavailable("The engine could not be reached."))
        assertNull(plan.mealTarget)
        assertTrue(plan.refusals.single().contains("could not be reached"))
    }

    @Test
    fun `the context block states the numbers or forbids a meal target`() {
        val with = GoalPlanAgent.energyContext(FinanceRead.Fresh(estimate))
        assertTrue(with, with.contains("target 2200 kcal") && with.contains("protein 175 g"))
        val without = GoalPlanAgent.energyContext(FinanceRead.Fresh(EnergyWire(missing = listOf("sex"))))
        assertTrue(without, without.contains("Do NOT propose a mealTarget"))
    }
}
