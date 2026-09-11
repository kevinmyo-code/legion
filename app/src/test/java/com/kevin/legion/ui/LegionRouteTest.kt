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
 * [LegionRoute.HOME]/[LegionRoute.METERS]/[LegionRoute.SETTINGS] as three tabs the same day
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
 * [LegionRoute.HOME] instead.
 *
 * **RENAMED 2026-09-10: `LegionRoute.CALENDAR` is [LegionRoute.HOME]** (Kevin: *"just everything on
 * home page (rename it from calendar)"*). Every assertion below that named the old constant names
 * the new one; none of them changed meaning, because the tab being renamed is not the tab changing
 * behaviour. The genuinely new coverage is [LegionRoute.LEGACY_DEEP_LINK_ROUTES] at the bottom of
 * this file - the route STRING changed too, and a notification posted by an older build outlives
 * the build that posted it.
 *
 * **AMENDED 2026-09-10, later the same day (ticket 03b): the tab assertions are gone entirely.**
 * `TOP_LEVEL`, `topLevelOf` and `label` were deleted when METERS retired and the tab row went with
 * it, so four tests here had nothing left to assert. They were replaced rather than removed - see
 * the block comment where they used to be, and the two tests that stand in their place, which pin
 * the property that outlived them: **demoting a destination is not deleting it.**
 */
class LegionRouteTest {

    // ---------------------------------------------------------------- there are no tabs any more
    //
    // **Four tests were deleted here on 2026-09-10 (one-home ticket 03b)**: they asserted that HOME
    // and METERS were the two top-level tabs, that `topLevelOf` resolved each and returned null
    // inside `settings/`, and that `label` read "Home"/"Meters" with a fallthrough for SETTINGS.
    // `TOP_LEVEL`, `topLevelOf` and `label` no longer exist, so those tests could not be rewritten -
    // there is nothing left to assert about a tab row that is gone. See `LegionRoute.kt`'s tombstone
    // where they used to live; it keeps the two on-device facts they encoded (the `tab/` prefix
    // match, and "Setup" wrapping at 720px) since those live nowhere else now.
    //
    // What replaces them is the property that actually matters after the deletion, and it is not
    // about tabs at all.

    @Test
    fun `retiring the tab row did not delete a single destination behind it`() {
        // The real risk in 03b was never the row. It was that "retire METERS" would be read as
        // "delete what METERS reached" - and every one of these is a live EXTRA_ROUTE target or a
        // drill-down HOME's meter rows tap through to. They were demoted from tabs on 2026-09-01 and
        // demoted again to nothing on 2026-09-10; **demoted is not deleted**, and a route deleted
        // here fails at a notification tap rather than at compile time.
        val mustSurvive = listOf(
            LegionRoute.HOME, LegionRoute.BODY, LegionRoute.MONEY, LegionRoute.MONEY_PANTRY,
            LegionRoute.FLEET, LegionRoute.FLEET_PLACES, LegionRoute.CHECKLISTS,
            LegionRoute.DASHBOARD, LegionRoute.SETTINGS, LegionRoute.ASK, LegionRoute.NEWS,
        )
        for (route in mustSurvive) {
            assertTrue("a route must be a non-empty string, '$route' is not", route.isNotBlank())
        }
        // Distinct, because two constants collapsing onto one string is a silent mis-navigation
        // rather than a crash - the failure mode that has no error message at all.
        assertEquals("two routes share a string", mustSurvive.size, mustSurvive.toSet().size)
    }

    @Test
    fun `ASK is a route of its own, which is what keeps show_generated_view a hands path`() {
        // Ticket 01 put the picker on its own route rather than in Settings or inline on HOME.
        // `ui/ask/GeneratedViewHandsPathTest.kt` is what enforces ADR 0035 itself; this only pins
        // that the route exists and is not accidentally an alias of HOME.
        assertTrue(LegionRoute.ASK.isNotBlank())
        assertTrue("ASK must not collapse onto HOME", LegionRoute.ASK != LegionRoute.HOME)
    }

    @Test
    fun `NEWS is a route of its own too, same reasoning as ASK`() {
        // one-home ticket 07, ticket 06 resolution point 4: the feed is a row on HOME opening its
        // own route, never a tab and never a pane welded onto HOME's own scroll.
        assertTrue(LegionRoute.NEWS.isNotBlank())
        assertTrue("NEWS must not collapse onto HOME", LegionRoute.NEWS != LegionRoute.HOME)
        assertTrue("NEWS must not collapse onto ASK", LegionRoute.NEWS != LegionRoute.ASK)
    }

    @Test
    fun `every seeded aspect's legacy route, when present, is a real LegionRoute constant`() {
        // LegionRoute.NOTES dropped out of `known` one-today ticket 10 slice C, 2026-09-05 (the
        // constant is deleted); LegionRoute.HOME is added in its place - the Notes aspect's own
        // legacy route (`ui/widgets/WidgetPagerScreen.kt`'s `legacyRouteForAspect`) is repointed
        // there now. It was `LegionRoute.CALENDAR` when repointed; same constant, renamed
        // 2026-09-10.
        val known = setOf(
            LegionRoute.FLEET, LegionRoute.MONEY, LegionRoute.MONEY_PANTRY,
            LegionRoute.HOME, LegionRoute.FLEET_PLACES,
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

    // ------------------------------------------------------- the route string changed, not just the name

    @Test
    fun `a deep link carrying the old calendar route lands on HOME rather than crashing`() {
        // The 2026-09-10 rename moved the route STRING from "calendar" to "home". A reminder
        // notification carries whatever string the build that POSTED it had, and is tapped by
        // whichever build is installed by then - `navController.navigate` throws
        // IllegalArgumentException for a destination the graph does not contain, so an unresolved
        // "calendar" is a crash on a notification tap, not a mis-navigation.
        assertEquals(LegionRoute.HOME, LegionRoute.resolveDeepLink("calendar"))
    }

    @Test
    fun `notes, today and meters all resolve, and meters is there for a different reason`() {
        // NOTES deleted 2026-09-05 (one-today 10 slice C), TODAY deleted 2026-09-01 (ticket 07).
        // Both had their live callers repointed; neither had anything covering a notification
        // ALREADY in the shade. Found while adding the map, so fixed with it.
        assertEquals(LegionRoute.HOME, LegionRoute.resolveDeepLink("notes"))
        assertEquals(LegionRoute.HOME, LegionRoute.resolveDeepLink("today"))
        // METERS (deleted 2026-09-10) is here on a different argument and it is worth keeping
        // straight: no notification ever named it, because it was a TAB rather than an alarm
        // target. A tab is something you can be sitting on when the process is killed, and
        // Navigation restores its back stack from saved state - so a saved stack naming a deleted
        // destination is the same crash by a road that needs no stale notification at all.
        assertEquals(LegionRoute.HOME, LegionRoute.resolveDeepLink("meters"))
    }

    @Test
    fun `a live route passes through resolveDeepLink untouched`() {
        for (route in listOf(LegionRoute.HOME, LegionRoute.ASK, LegionRoute.FLEET_PLACES, LegionRoute.MONEY_PANTRY_IMPORT)) {
            assertEquals(route, LegionRoute.resolveDeepLink(route))
        }
    }

    @Test
    fun `an unknown route is passed through, not defaulted to HOME`() {
        // Deliberate. Landing an unrecognised route on the home screen would hide a bug behind a
        // plausible-looking screen; the map is for routes known to have existed and known where
        // they went. Anything else is a defect and should behave as one.
        assertEquals("some-route-nobody-declared", LegionRoute.resolveDeepLink("some-route-nobody-declared"))
    }

    @Test
    fun `no route extra at all resolves to nothing, which is the ordinary launcher start`() {
        assertNull(LegionRoute.resolveDeepLink(null))
    }

    @Test
    fun `every legacy route maps to a route that still exists`() {
        // The map's whole purpose is defeated if it points at a destination that was itself later
        // deleted - that would trade one crash for another. Pinned against the declared constants.
        val live = setOf(
            LegionRoute.HOME, LegionRoute.ASK, LegionRoute.DASHBOARD, LegionRoute.BODY,
            LegionRoute.MONEY, LegionRoute.FLEET, LegionRoute.SETTINGS,
        )
        for ((legacy, target) in LegionRoute.LEGACY_DEEP_LINK_ROUTES) {
            assertTrue("'$legacy' maps to '$target', which is not a live route", target in live)
        }
        // And a legacy key must never be a live route itself, or the map would silently redirect
        // a working destination.
        for (legacy in LegionRoute.LEGACY_DEEP_LINK_ROUTES.keys) {
            assertTrue("'$legacy' is both legacy and live", legacy !in live)
        }
    }
}
