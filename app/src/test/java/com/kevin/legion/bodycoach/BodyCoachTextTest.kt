package com.kevin.legion.bodycoach

import com.kevin.legion.finance.FinanceRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * fat-loss tickets 10 and 12: the tool results, the digest value and the saved-meal match. Under
 * Robolectric only for `org.json`, which is stubbed in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class BodyCoachTextTest {

    private fun meal(name: String, kcal: Int = 500) = SavedMealWire(id = name, name = name, caloriesKcal = kcal, proteinG = 35.0)

    private val meals = listOf(meal("Usual breakfast", 520), meal("Burrito bowl", 780), meal("Protein shake", 250))

    @Test
    fun `my usual breakfast matches the saved Usual breakfast`() {
        assertEquals("Usual breakfast", BodyCoachText.matchSavedMeal("log my usual breakfast", meals)?.name)
        assertEquals("Protein shake", BodyCoachText.matchSavedMeal("I had a protein shake", meals)?.name)
    }

    @Test
    fun `a different meal that merely contains a saved name is not silently the saved one`() {
        assertNull(BodyCoachText.matchSavedMeal("chicken burrito bowl with extra rice", meals))
        assertNull(BodyCoachText.matchSavedMeal("eggs and toast", meals))
        assertNull(BodyCoachText.matchSavedMeal("my usual", meals))
    }

    @Test
    fun `a marker word allows one overlapping saved meal, never a guess between two`() {
        assertEquals("Burrito bowl", BodyCoachText.matchSavedMeal("my usual bowl", meals)?.name)
        val two = listOf(meal("Usual chicken salad"), meal("Usual chicken wrap"))
        assertNull(BodyCoachText.matchSavedMeal("my usual chicken", two))
    }

    @Test
    fun `energy result says estimate, carries the basis and the accept hint`() {
        val e = EnergyWire(
            basis = "trend", basisDays = 14, basisWords = "from your trend, 14 days",
            maintenanceKcal = 2610, suggestedTargetKcal = 2100, proteinG = 170,
            proposal = ProposalWire(id = "abc", currentTargetKcal = 2250, proposedTargetKcal = 2100, words = "Lower it?"),
            words = "Maintenance about 2,610 kcal (estimate).",
        )
        val out = BodyCoachText.energyResult(FinanceRead.Fresh(e))
        assertTrue(out.getBoolean("estimate"))
        assertEquals("trend", out.getString("basis"))
        val hint = out.getJSONObject("pending_proposal").getString("how_to_accept")
        assertTrue(hint.contains("ask_engine") && hint.contains("abc") && hint.contains("yes"))
        assertFalse(out.has("note"))
    }

    @Test
    fun `missing inputs are named and an unreadable engine estimates nothing`() {
        val missing = BodyCoachText.energyResult(FinanceRead.Fresh(EnergyWire(missing = listOf("height", "sex"))))
        assertEquals("height", missing.getJSONArray("missing").getString(0))
        assertFalse(missing.has("suggested_target_kcal"))
        val down = BodyCoachText.energyResult(FinanceRead.Unavailable("The engine could not be reached."))
        assertFalse(down.getBoolean("success"))
        assertTrue(down.getString("message").contains("Nothing was estimated"))
    }

    @Test
    fun `a saved copy carries its age note`() {
        val read = FinanceRead.Saved(TrendWire(latestTrendLbs = 243.0, words = "w"), 0L, "Saved 2 hours ago.")
        assertEquals("Saved 2 hours ago.", BodyCoachText.trendResult(read).getString("note"))
    }

    @Test
    fun `the digest value names basis, target, protein and a pending proposal`() {
        val e = EnergyWire(
            basis = "formula", maintenanceKcal = 3000, suggestedTargetKcal = 2250, targetRatePct = 0.75,
            proteinG = 180, proteinBasis = "goal weight",
            proposal = ProposalWire(id = "x", currentTargetKcal = 2250, proposedTargetKcal = 2100),
        )
        val v = BodyCoachText.digestEnergy(e)
        assertTrue(v, v.contains("maintenance 3000kcal (from formula)"))
        assertTrue(v, v.contains("suggested 2250kcal"))
        assertTrue(v, v.contains("protein 180g (goal weight)"))
        assertTrue(v, v.contains("pending proposal 2250->2100kcal"))
    }
}
