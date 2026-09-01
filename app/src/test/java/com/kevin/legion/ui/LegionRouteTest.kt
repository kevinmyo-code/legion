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
 * **2026-09-01 calendar-home cutover** (Kevin, verbatim, [LegionRoute.CALENDAR]'s own doc comment):
 * three top-level tabs now, [LegionRoute.CALENDAR]/[LegionRoute.METERS]/[LegionRoute.SETTINGS],
 * superseding the five-tab shape these pins used to check (itself a supersession of a four-tab
 * shape, and before that the reverted cutover-5 pager-as-HOME flip - see [LegionRoute]'s class doc
 * for the full history). [LegionRoute.TODAY]/[LegionRoute.MONEY]/[LegionRoute.BODY]/
 * [LegionRoute.FLEET]/[LegionRoute.NOTES] are demoted off [LegionRoute.TOP_LEVEL], not deleted -
 * every seeded aspect's "OPEN FULL SCREEN" button must still resolve to a route this file actually
 * declares, which is what the aspect-legacy-route tests below still check regardless of which
 * routes are tabs.
 */
class LegionRouteTest {

    @Test
    fun `CALENDAR and METERS are the top-level tabs, the five demoted routes are not`() {
        assertTrue(LegionRoute.TOP_LEVEL.contains(LegionRoute.CALENDAR))
        assertTrue(LegionRoute.TOP_LEVEL.contains(LegionRoute.METERS))
        assertTrue(LegionRoute.TOP_LEVEL.contains(LegionRoute.SETTINGS))
        assertEquals(3, LegionRoute.TOP_LEVEL.size)
        for (demoted in listOf(LegionRoute.TODAY, LegionRoute.MONEY, LegionRoute.BODY, LegionRoute.FLEET, LegionRoute.NOTES)) {
            assertTrue("$demoted must stay a real route, just not a tab", !LegionRoute.TOP_LEVEL.contains(demoted))
        }
    }

    @Test
    fun `topLevelOf resolves CALENDAR and METERS, not a demoted route`() {
        assertEquals(LegionRoute.CALENDAR, LegionRoute.topLevelOf(LegionRoute.CALENDAR))
        assertEquals(LegionRoute.METERS, LegionRoute.topLevelOf(LegionRoute.METERS))
        // TODAY is a real, standalone route, not a CALENDAR/METERS sub-route (no "calendar/" or
        // "meters/" prefix) - it correctly lights no tab at all now, the same shape DASHBOARD and
        // DRIVING already lit nothing under the five-tab shape.
        assertNull(LegionRoute.topLevelOf(LegionRoute.TODAY))
        assertNull(LegionRoute.topLevelOf(LegionRoute.MONEY))
    }

    @Test
    fun `label reads Calendar and Meters for the two new tabs`() {
        assertEquals("Calendar", LegionRoute.label(LegionRoute.CALENDAR))
        assertEquals("Meters", LegionRoute.label(LegionRoute.METERS))
        assertEquals("Setup", LegionRoute.label(LegionRoute.SETTINGS))
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
