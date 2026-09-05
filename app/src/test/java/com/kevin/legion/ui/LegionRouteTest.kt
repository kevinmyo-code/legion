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
 * **CORRECTED 2026-09-01: two top-level tabs, not three.** The calendar-home cutover landed
 * [LegionRoute.CALENDAR]/[LegionRoute.METERS]/[LegionRoute.SETTINGS] as three tabs the same day
 * this suite's own doc comment used to describe; [SETTINGS] came off [LegionRoute.TOP_LEVEL] again
 * hours later (Kevin, on seeing it running: "setup is being duplicated. keep the top right corner
 * one and drop the one beside meters") - [LegionRoute.TOP_LEVEL]'s own doc comment has the full
 * account, and [StatusLine]'s SETUP stamp is now the only way into `settings/`.
 * [LegionRoute.MONEY]/[LegionRoute.BODY]/[LegionRoute.FLEET] are demoted off
 * [LegionRoute.TOP_LEVEL], not deleted - every seeded aspect's "OPEN FULL SCREEN" button must
 * still resolve to a route this file actually declares, which is what the aspect-legacy-route
 * tests below still check regardless of which routes are tabs.
 * **`LegionRoute.TODAY` itself is gone, not merely demoted** - one-today ticket 07 (2026-09-01)
 * deleted `ui/TodayScreen.kt` and the `TODAY` constant once every survivor it carried was rehomed
 * (see [LegionRoute]'s class doc), so this suite no longer asserts anything about it.
 * **`LegionRoute.NOTES` is gone too, same shape, one-today ticket 10 slice C (2026-09-05)** -
 * `ui/NotesScreen.kt` deleted once its own survivor (a reminder's edit affordance) was rehomed
 * onto `ui/CalendarScreen.kt`'s day view; the Notes aspect's legacy route below now resolves to
 * [LegionRoute.CALENDAR] instead.
 */
class LegionRouteTest {

    @Test
    fun `CALENDAR and METERS are the only top-level tabs, SETTINGS and the three demoted routes are not`() {
        assertTrue(LegionRoute.TOP_LEVEL.contains(LegionRoute.CALENDAR))
        assertTrue(LegionRoute.TOP_LEVEL.contains(LegionRoute.METERS))
        assertEquals(2, LegionRoute.TOP_LEVEL.size)
        for (demoted in listOf(LegionRoute.SETTINGS, LegionRoute.MONEY, LegionRoute.BODY, LegionRoute.FLEET)) {
            assertTrue("$demoted must stay a real route, just not a tab", !LegionRoute.TOP_LEVEL.contains(demoted))
        }
    }

    @Test
    fun `topLevelOf resolves CALENDAR and METERS, not a demoted route`() {
        assertEquals(LegionRoute.CALENDAR, LegionRoute.topLevelOf(LegionRoute.CALENDAR))
        assertEquals(LegionRoute.METERS, LegionRoute.topLevelOf(LegionRoute.METERS))
        // MONEY is a real, standalone route, not a CALENDAR/METERS sub-route (no "calendar/" or
        // "meters/" prefix) - it correctly lights no tab at all now, the same shape DASHBOARD and
        // DRIVING already lit nothing under the five-tab shape (and TODAY did too, before one-today
        // ticket 07 deleted it outright).
        assertNull(LegionRoute.topLevelOf(LegionRoute.MONEY))
    }

    @Test
    fun `topLevelOf(settings-key) is null, and that is intended, not a defect`() {
        // CORRECTED 2026-09-01: SETTINGS came off TOP_LEVEL (see this suite's own class doc), so a
        // settings sub-route lighting NO tab is now the correct answer, not the 2026-08-02 defect
        // `topLevelOf`'s own doc comment records (a real tab's own sub-route going dark). SETTINGS
        // is simply not one of the tabs any more; [StatusLine]'s SETUP stamp is the only way in and
        // does not depend on a tab being lit.
        assertNull(LegionRoute.topLevelOf(LegionRoute.SETTINGS_KEY))
    }

    @Test
    fun `label reads Calendar and Meters for the two tabs, and falls through for SETTINGS`() {
        assertEquals("Calendar", LegionRoute.label(LegionRoute.CALENDAR))
        assertEquals("Meters", LegionRoute.label(LegionRoute.METERS))
        // CORRECTED 2026-09-01: label() no longer special-cases SETTINGS ("Setup") - the branch was
        // unreachable from its only production caller ([LegionTabRow], which iterates TOP_LEVEL
        // alone) once SETTINGS came off that list, so it was dropped rather than kept dead. This
        // pins the `else -> route` fallback it now shares with every other unlisted route.
        assertEquals(LegionRoute.SETTINGS, LegionRoute.label(LegionRoute.SETTINGS))
    }

    @Test
    fun `every seeded aspect's legacy route, when present, is a real LegionRoute constant`() {
        // LegionRoute.NOTES dropped out of `known` one-today ticket 10 slice C, 2026-09-05 (the
        // constant is deleted); LegionRoute.CALENDAR is added in its place - the Notes aspect's own
        // legacy route (`ui/widgets/WidgetPagerScreen.kt`'s `legacyRouteForAspect`) is repointed
        // there now.
        val known = setOf(
            LegionRoute.FLEET, LegionRoute.MONEY, LegionRoute.MONEY_PANTRY,
            LegionRoute.CALENDAR, LegionRoute.FLEET_PLACES,
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
