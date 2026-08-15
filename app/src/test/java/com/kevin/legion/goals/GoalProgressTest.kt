package com.kevin.legion.goals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [GoalProgress.accumulationProgress] - quant-viz ticket 08's own verification
 * checkbox ("null/zero/negative target -> null, exact fraction"). Plain JUnit; [GoalProgress] takes
 * no Android/Room dependency for this function, so no Robolectric is needed here (the DB-touching
 * [GoalProgress.savingsBalanceCents] half is exercised indirectly through
 * `CredDigestBuilderTest`'s existing Robolectric coverage - that query moved, its behaviour did not).
 */
class GoalProgressTest {

    @Test
    fun `a null target returns null - nothing to divide by`() {
        assertNull(GoalProgress.accumulationProgress(currentValue = 100.0, targetValue = null))
    }

    @Test
    fun `a zero target returns null, not an infinite or NaN fraction`() {
        assertNull(GoalProgress.accumulationProgress(currentValue = 100.0, targetValue = 0.0))
    }

    @Test
    fun `a negative target returns null - not a shape this function tries to make sense of`() {
        assertNull(GoalProgress.accumulationProgress(currentValue = 100.0, targetValue = -50.0))
    }

    @Test
    fun `an exact fraction below target`() {
        assertEquals(0.5f, GoalProgress.accumulationProgress(currentValue = 15000.0, targetValue = 30000.0)!!, 0.0001f)
    }

    @Test
    fun `zero progress is a valid, exact zero fraction, not null`() {
        assertEquals(0f, GoalProgress.accumulationProgress(currentValue = 0.0, targetValue = 30000.0)!!, 0.0001f)
    }

    @Test
    fun `exceeding the target returns a fraction above 1 - clamping is the caller's (DeckMeter's) job, not this function's`() {
        val progress = GoalProgress.accumulationProgress(currentValue = 45000.0, targetValue = 30000.0)!!
        assertEquals(1.5f, progress, 0.0001f)
    }
}
