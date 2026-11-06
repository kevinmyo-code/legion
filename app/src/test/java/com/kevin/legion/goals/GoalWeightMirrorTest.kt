package com.kevin.legion.goals

import com.kevin.legion.data.local.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** fat-loss ticket 14 (G1): which goals go to the engine profile as a goal weight, in lbs. */
class GoalWeightMirrorTest {

    private fun goal(metric: String?, value: Double?, unit: String?) =
        Goal(lineageId = 1, aspect = "bio", statement = "a synthetic goal", targetValue = value, unit = unit, metricKey = metric)

    @Test
    fun `a bodyweight_lbs goal goes as is`() {
        assertEquals(212.0, GoalController.weightGoalLbs(goal("bodyweight_lbs", 212.0, "lbs"))!!, 0.001)
    }

    @Test
    fun `kg goals are converted to lbs`() {
        assertEquals(220.462, GoalController.weightGoalLbs(goal("bodyweight_kg", 100.0, "kg"))!!, 0.001)
        assertEquals(220.462, GoalController.weightGoalLbs(goal("bodyweight_lbs", 100.0, "kg"))!!, 0.001)
    }

    @Test
    fun `other goals and goals with no number are not weight goals`() {
        assertNull(GoalController.weightGoalLbs(goal("savings_balance_cents", 500_000.0, "cents")))
        assertNull(GoalController.weightGoalLbs(goal("bodyweight_lbs", null, null)))
        assertNull(GoalController.weightGoalLbs(goal(null, 200.0, "lbs")))
    }
}
