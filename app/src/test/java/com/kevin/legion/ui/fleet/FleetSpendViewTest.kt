package com.kevin.legion.ui.fleet

import com.kevin.legion.vehicle.FleetSpendController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [buildFleetSpendView] - ticket 11 §4's wording rules (CLAUDE.md §4 rule
 * 6's coverage disclosure, the cost-per-mile refusal passthrough, "money stays mono" formatting,
 * the >= 2-years trend gate). No Room, no Android dependency, plain JVM test - same posture as
 * [FleetRowsTest].
 */
class FleetSpendViewTest {

    @Test
    fun `zero records with a cost reads as a worded absence, never a bare dollar figure`() {
        val view = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(totalCents = 0L, recordsWithCost = 0, totalRecords = 2),
            perMile = FleetSpendController.CostPerMile.Refused("no reason needed for this test"),
            byType = emptyList(),
            byYear = emptyList(),
        )
        assertEquals("No costs logged yet", view.totalText)
        assertEquals("0 of 2 service records have a cost logged.", view.coverageText)
    }

    @Test
    fun `a real total formats as dollars with grouped thousands`() {
        val view = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(totalCents = 123_456L, recordsWithCost = 3, totalRecords = 3),
            perMile = FleetSpendController.CostPerMile.Refused("n/a"),
            byType = emptyList(),
            byYear = emptyList(),
        )
        assertEquals("$1,234.56", view.totalText)
        assertEquals("3 of 3 service records have a cost logged.", view.coverageText)
    }

    @Test
    fun `singular wording for exactly one record`() {
        val view = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(totalCents = 0L, recordsWithCost = 0, totalRecords = 1),
            perMile = FleetSpendController.CostPerMile.Refused("n/a"),
            byType = emptyList(),
            byYear = emptyList(),
        )
        assertEquals("0 of 1 service record has a cost logged.", view.coverageText)
    }

    @Test
    fun `a refusal carries through as the value text and flags itself as a refusal`() {
        val view = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(0L, 0, 0),
            perMile = FleetSpendController.CostPerMile.Refused("Odometer hasn't been confirmed yet."),
            byType = emptyList(),
            byYear = emptyList(),
        )
        assertEquals("Odometer hasn't been confirmed yet.", view.perMileText)
        assertTrue(view.perMileIsRefusal)
    }

    @Test
    fun `a cost-per-mile off a JUST-CONFIRMED odometer renders bare - no caveat is owed`() {
        val view = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(10_000L, 1, 1),
            perMile = FleetSpendController.CostPerMile.Value(centsPerMile = 5.0, mileageCaveat = null),
            byType = emptyList(),
            byYear = emptyList(),
        )
        assertEquals("$0.05 / mi", view.perMileText)
        assertFalse(view.perMileIsRefusal)
    }

    /**
     * Review finding, 2026-08-15. Refusing at `odometerBaseline == 0` handled the loud case and
     * left the quiet one: a CONFIRMED-but-stale baseline still divides by an estimate that ticket
     * 03 measured at 5-15% low, one-directional, and the ratio rendered as a precise-looking
     * two-decimal figure with nothing said. Ticket 11's own words: cost per mile "inherits that
     * caveat and says so".
     */
    @Test
    fun `a cost-per-mile off an ESTIMATED odometer carries the caveat with the figure`() {
        val view = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(10_000L, 1, 1),
            perMile = FleetSpendController.CostPerMile.Value(
                centsPerMile = 5.0,
                mileageCaveat = "estimated, last confirmed 3 days ago",
            ),
            byType = emptyList(),
            byYear = emptyList(),
        )
        assertEquals("$0.05 / mi (estimated, last confirmed 3 days ago)", view.perMileText)
        assertFalse("A caveated figure is still a figure, not a refusal", view.perMileIsRefusal)
    }

    @Test
    fun `spend by type carries both chart bars and exact text rows in the same order`() {
        val view = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(6_599L, 2, 2),
            perMile = FleetSpendController.CostPerMile.Refused("n/a"),
            byType = listOf("Oil Change" to 4599L, "Air Filter" to 2000L),
            byYear = emptyList(),
        )
        assertEquals(2, view.byType.size)
        assertEquals("Oil Change", view.byType[0].label)
        assertEquals("$45.99", view.byType[0].valueLabel)
        assertEquals(listOf("Oil Change" to "$45.99", "Air Filter" to "$20.00"), view.byTypeRows)
    }

    @Test
    fun `spend by year trend is unavailable below two years, exactly as ticket 11 asks`() {
        val oneYear = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(3_000L, 1, 1),
            perMile = FleetSpendController.CostPerMile.Refused("n/a"),
            byType = emptyList(),
            byYear = listOf(2026 to 3_000L),
        )
        assertFalse(oneYear.yearTrendAvailable)

        val twoYears = buildFleetSpendView(
            total = FleetSpendController.SpendTotal(6_500L, 2, 2),
            perMile = FleetSpendController.CostPerMile.Refused("n/a"),
            byType = emptyList(),
            byYear = listOf(2025 to 3_500L, 2026 to 3_000L),
        )
        assertTrue(twoYears.yearTrendAvailable)
        assertEquals(listOf("2025" to "$35.00", "2026" to "$30.00"), twoYears.byYearRows)
    }
}
