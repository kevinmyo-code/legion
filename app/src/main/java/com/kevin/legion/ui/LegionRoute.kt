package com.kevin.legion.ui

/**
 * Every route in the single-activity shell. **Five top-level tabs as of the 2026-08-07 Today-panel
 * brief** (was four - ticket 07's original resolution §5 shape is superseded below, not amended in
 * place, so the history stays readable): Today is now the START DESTINATION, Money absorbed the
 * old `ledger/` route wholesale plus pantry's import flow (a grocery receipt is a purchase), and
 * pantry's own read screen moved under Money as a reachable sub-route rather than a tab of its
 * own. Body is entirely new - workouts/meals/bodyweight had a backend and no UI before this.
 *
 * **Cutover 5 (`docs/architecture/cutover5-2026-08-24.md`): `dashboard/` is the new start
 * destination**, hosting the widget pager - `today/` stays a real, fully wired route (reached from
 * the pager's own HOME page) but is no longer the start destination or a hard key.
 *
 * ```
 * dashboard/   <- the widget pager, HOME hard key's target (was today/)
 * today/       <- still real; reached from dashboard/'s "CLASSIC" button
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
 *              + settings/playbooks  <- edit advisor doctrine per topic (2026-08-18)
 *              + settings/memory     <- view/delete what the assistant remembers (2026-08-18)
 * ```
 *
 * Plain string constants rather than a sealed class with typed args: nothing
 * here takes a navigation argument, so a type-safe route hierarchy would be
 * ceremony with no payoff. Revisit if a sub-route ever needs one (e.g. "open
 * this specific saved place").
 */
object LegionRoute {
    /**
     * The app's home as of cutover 5 (`docs/architecture/cutover5-2026-08-24.md`) - the widget
     * pager, `com.kevin.legion.ui.widgets.WidgetPagerRoot`, hosted as an ordinary [composable]
     * destination here rather than a second Activity. This is now the [NavHost]'s
     * `startDestination` and the HOME hard key's target; [TODAY] stays a real, reachable route
     * (the pager's own HOME page carries a "CLASSIC" button straight to it - see
     * [WidgetPagerRoot]'s `onOpenRoute` param) but is no longer a hard key or a [TOP_LEVEL] tab,
     * per ticket 22 point 4's own mapping (TODAY's panes are what [DefaultArrangementSeeder]'s
     * HOME arrangement is modelled on).
     */
    const val DASHBOARD = "dashboard"

    /**
     * Was the start destination and the HOME hard key's target before cutover 5. Still a real,
     * fully wired [composable] - deep links (`EXTRA_ROUTE`), [onOpenCategory]'s Money drilldown,
     * the key-settings advisory row, and the media mini-bar tap-through all still work exactly as
     * before - reached now from [DASHBOARD]'s own "CLASSIC" button rather than from a hard key.
     */
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

    /**
     * The five subscreens `settings/` split into (command-center ticket 02, 2026-08-22,
     * `.scratch/command-center/issues/02-settings-submenus.md`) - [SETTINGS] itself is now five
     * navigation rows and nothing else. See `ui/settings/AssistantSettingsScreen.kt` and its four
     * siblings for what landed on each.
     */
    const val SETTINGS_ASSISTANT = "settings/assistant"
    const val SETTINGS_PROACTIVE_SPEECH = "settings/proactive-speech"
    const val SETTINGS_CONNECTIONS = "settings/connections"
    const val SETTINGS_DATA_PRIVACY = "settings/data-privacy"
    const val SETTINGS_PERMISSIONS_DIAGNOSTICS = "settings/permissions-diagnostics"

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
     * The driver's own editor for [com.kevin.legion.advisor.PrimingTopic]'s four bodies of
     * doctrine (2026-08-18) - see [com.kevin.legion.ui.companions.PlaybookScreen]. The list-to-
     * editor drill-down inside it is internal Compose state, same posture [NOTES]'s own doc
     * comment states for its LISTS | CALENDAR toggle, so this is one route, not five.
     */
    const val SETTINGS_PLAYBOOKS = "settings/playbooks"

    /**
     * "What it remembers" (2026-08-18) - see [com.kevin.legion.ui.companions.MemoryScreen]. Reads
     * and deletes from both [com.kevin.legion.data.local.MemoryEntry] (explicit "remember X") and
     * [com.kevin.legion.data.local.CompanionMemory] (consolidated/reflected). No sub-routes: the
     * only interaction below the list is a delete, not a drill-down.
     */
    const val SETTINGS_MEMORY = "settings/memory"

    /**
     * The dial screen (command-center ticket 05, ADR 0035's hands path for `place_call`) - see
     * [com.kevin.legion.ui.phone.PhoneDialScreen]. Same shape as [SETTINGS_MEMORY]: no sub-routes,
     * every state below the top level (resolving, confirm read-back, called, failed, refused) is
     * internal Compose state inside the one screen, not a nav-graph destination.
     */
    const val SETTINGS_PHONE = "settings/phone"

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

    /**
     * The media control panel (command-center ticket 04,
     * `.scratch/command-center/issues/04-media-panel.md`) - the hands path for the five music
     * voice tools (ADR 0035): now-playing, transport, volume, queue, search-and-play, and library
     * browse. Nested under Spotify's own settings route rather than promoted to a tab of its own -
     * `ui/media/MediaMiniBar.kt` (this ticket's other export) is what Home is meant to consume for
     * at-a-glance transport (command-center ticket 01), and the full panel is a drill-down from
     * wherever a driver already goes to manage Spotify - see [SETTINGS_SPOTIFY]'s own screen for
     * the entry-point row.
     */
    const val SETTINGS_SPOTIFY_MEDIA = "settings/spotify/media"

    /**
     * "What can I do" (command-center ticket 09,
     * `.scratch/command-center/issues/09-discovery-and-wiki.md`) - see
     * [com.kevin.legion.ui.help.VoiceGuideScreen]. Same shape as [SETTINGS_MEMORY]/[SETTINGS_PHONE]:
     * no sub-routes, the search filter and group expansion are internal Compose state, not
     * nav-graph destinations. Row lives on [SETTINGS_ASSISTANT] - see that screen's own comment for
     * why there rather than [SETTINGS_DATA_PRIVACY].
     */
    const val SETTINGS_HELP = "settings/help"

    /** The six bottom-nav destinations, in display order. **Cutover 5: [DASHBOARD] replaced
     * [TODAY] here** - HOME now lights on the widget pager, not the old Today panel (still a real
     * route, just no longer a tab of its own - see [TODAY]'s own doc comment). Assistant is NOT
     * one of them - it's a mode, not a place (original resolution §5, still true). */
    val TOP_LEVEL = listOf(DASHBOARD, MONEY, BODY, FLEET, NOTES, SETTINGS)

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
        DASHBOARD -> "Home"
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
