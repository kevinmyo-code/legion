package com.kevin.legion.ui

/**
 * Every route in the single-activity shell. **Five top-level tabs as of the 2026-08-07 Today-panel
 * brief** (was four - ticket 07's original resolution §5 shape is superseded below, not amended in
 * place, so the history stays readable): Today is now the START DESTINATION, Money absorbed the
 * old `ledger/` route wholesale plus pantry's import flow (a grocery receipt is a purchase), and
 * pantry's own read screen moved under Money as a reachable sub-route rather than a tab of its
 * own. Body is entirely new - workouts/meals/bodyweight had a backend and no UI before this.
 *
 * ```
 * today/
 * money/       + money/import        <- was ledger/import
 *              + money/pantry        <- was the `pantry` tab (PantryScreen), now a sub-route
 *              + money/pantry/import <- was pantry/import
 * body/
 * fleet/       + fleet/places        <- was SavedPlacesActivity
 *              + fleet/cars          <- the car roster / active-car picker
 *              + fleet/telemetry     <- recorded obd_samples history
 * settings/    + settings/key
 *              + settings/companions <- companion profile picker (Part 2, 2026-08-02)
 *              + settings/drive-sync <- Connect Google Drive (2026-08-03)
 *              + settings/spotify    <- Connect Spotify (2026-08-12)
 * ```
 *
 * Plain string constants rather than a sealed class with typed args: nothing
 * here takes a navigation argument, so a type-safe route hierarchy would be
 * ceremony with no payoff. Revisit if a sub-route ever needs one (e.g. "open
 * this specific saved place").
 */
object LegionRoute {
    const val TODAY = "today"

    const val BODY = "body"

    /**
     * The sixth top-level destination (`.scratch/notes-lists-calendar/issues/07-where-it-lives.md`'s
     * Answer, 2026-08-07): "one new destination, 'Notes', with the calendar as a view inside it."
     * No sub-routes of its own - the LISTS | CALENDAR toggle and the list-of-lists -> single-list
     * drill-down are internal Compose state inside [com.kevin.legion.ui.NotesScreen], not nav-graph
     * destinations, so this domain never needed the argument-carrying route this file's own doc
     * comment says nothing here has needed yet.
     */
    const val NOTES = "notes"

    const val FLEET = "fleet"
    const val FLEET_PLACES = "fleet/places"
    /** The car roster and the explicit active-car picker (2026-08-04) - see [CarsScreen]. */
    const val FLEET_CARS = "fleet/cars"
    /** Recorded `obd_samples` history for the active car (2026-08-04) - see [TelemetryScreen]. */
    const val FLEET_TELEMETRY = "fleet/telemetry"

    /** Was `ledger` - renamed for the tab, the screen underneath is still [LedgerScreen] unchanged. */
    const val MONEY = "money"
    /** Was `ledger/import`. */
    const val MONEY_IMPORT = "money/import"
    /** Was the standalone `pantry` tab - a grocery receipt is a purchase, so it now lives under Money (2026-08-07 brief). Still [PantryScreen] unchanged, only the route and its tab moved. */
    const val MONEY_PANTRY = "money/pantry"
    /** Was `pantry/import`. */
    const val MONEY_PANTRY_IMPORT = "money/pantry/import"

    /**
     * The ticket-20 driving-mode glance screen (`.scratch/cyberdeck-ui/issues/11-driving-mode.md`'s
     * resolution). Deliberately NOT under [FLEET] as a `fleet/driving` sub-route: ticket 20's build
     * brief calls this "a destination outside the shell chrome" (no StatusLine, no hard-key row -
     * see [com.kevin.legion.ui.MainActivity]'s [LegionShell] branching on this route), and nesting
     * it under `fleet/` would still light the FLEET hard key via [topLevelOf]'s prefix match, which
     * is chrome this screen is explicitly built to not have. A top-level route with no hard key of
     * its own (same shape as [SETTINGS]) is the correct fit, not a fifth thing FLEET owns.
     */
    const val DRIVING = "driving"

    const val SETTINGS = "settings"
    const val SETTINGS_KEY = "settings/key"
    /** The companion profile picker (roster, create/edit/delete/switch) - Part 2 of the multi-companion feature. */
    const val SETTINGS_COMPANIONS = "settings/companions"
    /**
     * The GOOGLE row's destination (ticket 12,
     * `.scratch/google-account-integration/issues/12-google-grant-plumbing.md`) - one screen
     * showing the live status of all three Google grants (Drive/Calendar/Gmail), per ticket 06's
     * Answer §2. Distinct from [SETTINGS_DRIVE_SYNC], which still owns the actual Drive
     * connect/disconnect/consent round trip; this route's Drive line opens that one.
     */
    const val SETTINGS_GOOGLE = "settings/google"

    /** Connect Google Drive - the entry point that makes [com.kevin.legion.sync.SyncEngine] reachable (2026-08-03). */
    const val SETTINGS_DRIVE_SYNC = "settings/drive-sync"

    /**
     * The Android Auto probe harness's on-screen readout (`.scratch/android-auto/map.md`
     * wave 1) - see [com.kevin.legion.ui.CarProbeScreen] and
     * [com.kevin.legion.car.CarProbeLog]. A debug surface, not a driver-facing
     * feature, so it lives under Settings rather than getting a hard key or a
     * tab of its own.
     */
    const val SETTINGS_CAR_PROBE = "settings/car-probe"

    /**
     * Connect Spotify - the entry point that makes the whole `media/` Spotify tier reachable
     * (2026-08-12). Same shape as [SETTINGS_DRIVE_SYNC]: before it,
     * [com.kevin.legion.ai.CompanionProfile.saveSpotifyClientId],
     * [com.kevin.legion.media.SpotifyController.connect] and
     * [com.kevin.legion.media.SpotifyWebApi.beginAuthorization] had zero callers.
     * Also the destination [MainActivity] navigates to when the Spotify OAuth redirect lands.
     */
    const val SETTINGS_SPOTIFY = "settings/spotify"

    /** The six bottom-nav destinations, in display order (was five - Notes added 2026-08-07, ticket
     * 07). Assistant is NOT one of them - it's a mode, not a place (original resolution §5, still true). */
    val TOP_LEVEL = listOf(TODAY, MONEY, BODY, FLEET, NOTES, SETTINGS)

    /**
     * The top-level tab [route] belongs to, or null if it belongs to none.
     * A sub-route is its tab's route plus a `/` segment (`fleet/places` under
     * `fleet`), which is what makes the prefix test sufficient - and why the
     * `/` is part of the test rather than a bare `startsWith`, so a future
     * top-level `fleetsomething` could never be swallowed by `fleet`.
     *
     * The bottom bar's selected state is derived through here rather than by
     * exact route equality. With equality, every sub-route lit no tab at all:
     * open `settings/key` and the whole bar went dark, which reads as "you
     * have left the app's navigation" when you have not. Caught on the A17K
     * 2026-08-02.
     */
    fun topLevelOf(route: String?): String? =
        TOP_LEVEL.firstOrNull { route == it || route?.startsWith("$it/") == true }

    /** Short label for a top-level route's bottom-nav item. */
    fun label(route: String): String = when (route) {
        TODAY -> "Today"
        MONEY -> "Money"
        BODY -> "Body"
        FLEET -> "Fleet"
        NOTES -> "Notes"
        // "Setup", not "Settings": with five tabs, the longer word wrapped to
        // "Setting / s" in the bottom bar on a 720px-wide device (observed
        // 2026-08-07). Every other label is four or five characters, so this is
        // the one that had to give.
        SETTINGS -> "Setup"
        else -> route
    }
}
