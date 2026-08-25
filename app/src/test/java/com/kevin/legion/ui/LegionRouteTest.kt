package com.kevin.legion.ui

import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import com.kevin.legion.engine.places.PlacesAspectSeeder
import com.kevin.legion.ui.widgets.legacyRouteForAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutover 5 (`docs/architecture/cutover5-2026-08-24.md`) briefly made the widget pager HOME;
 * **reverted 2026-08-25** (see that doc's postscript) after Kevin field-tested it overnight and
 * ruled "revert everything to classic". These pins now check the REVERTED shape: [LegionRoute.TODAY]
 * is HOME again everywhere the shell derives a target, [LegionRoute.DASHBOARD] stays a real,
 * reachable, non-tab route (the pager is demoted, not deleted - reachable from TODAY's own
 * "DASHBOARD" button), and every seeded aspect's "OPEN FULL SCREEN" button still resolves to a
 * route this file actually declares - a typo'd or renamed route string here would otherwise
 * silently strand that button pointing at a route the [androidx.navigation.NavHost] never
 * registers, the exact "reachable, but only in theory" failure this suite exists to rule out.
 */
class LegionRouteTest {

    @Test
    fun `TODAY is the top-level HOME tab again, DASHBOARD is not`() {
        assertTrue(LegionRoute.TOP_LEVEL.contains(LegionRoute.TODAY))
        assertTrue("DASHBOARD must stay a real route, just not a tab", !LegionRoute.TOP_LEVEL.contains(LegionRoute.DASHBOARD))
    }

    @Test
    fun `topLevelOf resolves TODAY and its own sub-routes, not DASHBOARD`() {
        assertEquals(LegionRoute.TODAY, LegionRoute.topLevelOf(LegionRoute.TODAY))
        // DASHBOARD is a real, standalone route, not a TODAY sub-route (no "today/" prefix) - it
        // correctly lights no tab at all, the same shape SETTINGS' own sub-routes light SETTINGS
        // and DRIVING lights nothing.
        assertNull(LegionRoute.topLevelOf(LegionRoute.DASHBOARD))
    }

    @Test
    fun `label reads Today for the HOME tab, Dashboard for the opt-in pager`() {
        assertEquals("Today", LegionRoute.label(LegionRoute.TODAY))
        assertEquals("Dashboard", LegionRoute.label(LegionRoute.DASHBOARD))
    }

    @Test
    fun `every seeded aspect's legacy route, when present, is a real LegionRoute constant`() {
        val known = setOf(
            LegionRoute.FLEET, LegionRoute.MONEY, LegionRoute.MONEY_PANTRY,
            LegionRoute.NOTES, LegionRoute.FLEET_PLACES,
        )
        val names = listOf(
            FleetAspectSeeder.ASPECT_NAME, LedgerAspectSeeder.ASPECT_NAME, PantryAspectSeeder.ASPECT_NAME,
            NotesAspectSeeder.ASPECT_NAME, PlacesAspectSeeder.ASPECT_NAME, DatesAspectSeeder.ASPECT_NAME,
        )
        for (name in names) {
            val route = legacyRouteForAspect(name)
            if (route != null) assertTrue("'$route' for aspect '$name' must be a declared LegionRoute", route in known)
        }
        // Dates is the one seeded aspect this cutover's own doc names as genuinely new - no legacy
        // screen existed for it before the engine, so it correctly carries no legacy route.
        assertNull(legacyRouteForAspect(DatesAspectSeeder.ASPECT_NAME))
    }

    @Test
    fun `an unrecognised aspect name carries no legacy route`() {
        assertNull(legacyRouteForAspect("Some New Aspect A Driver Just Created"))
    }
}
