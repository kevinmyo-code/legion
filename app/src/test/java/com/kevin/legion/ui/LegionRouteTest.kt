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
 * Cutover 5 (`docs/architecture/cutover5-2026-08-24.md`): navigation-completeness pins for the
 * pager-becomes-home flip. Two things this suite can check mechanically (per this cutover's own
 * doc, "what the ruling table claims for composable routes"): [LegionRoute.DASHBOARD] is genuinely
 * the new HOME target everywhere the shell derives one from, and every seeded aspect's "OPEN FULL
 * SCREEN" button resolves to a route this file actually declares - a typo'd or renamed route
 * string here would otherwise silently strand that button pointing at a route the [androidx.navigation.NavHost]
 * never registers, the exact "reachable, but only in theory" failure this cutover exists to rule out.
 */
class LegionRouteTest {

    @Test
    fun `DASHBOARD replaced TODAY as the top-level HOME tab`() {
        assertTrue(LegionRoute.TOP_LEVEL.contains(LegionRoute.DASHBOARD))
        assertTrue("TODAY must stay a real route, just not a tab", !LegionRoute.TOP_LEVEL.contains(LegionRoute.TODAY))
    }

    @Test
    fun `topLevelOf resolves DASHBOARD and its own sub-routes, not TODAY`() {
        assertEquals(LegionRoute.DASHBOARD, LegionRoute.topLevelOf(LegionRoute.DASHBOARD))
        // TODAY is a real, standalone route now, not a DASHBOARD sub-route (no "dashboard/" prefix) -
        // it correctly lights no tab at all, the same shape SETTINGS' own sub-routes light SETTINGS
        // and DRIVING lights nothing.
        assertNull(LegionRoute.topLevelOf(LegionRoute.TODAY))
    }

    @Test
    fun `label reads Home for the new tab`() {
        assertEquals("Home", LegionRoute.label(LegionRoute.DASHBOARD))
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
