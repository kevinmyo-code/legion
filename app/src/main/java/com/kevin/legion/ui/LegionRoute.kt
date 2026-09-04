package com.kevin.legion.ui

/**
 * Every route in the single-activity shell.
 *
 * **CORRECTED 2026-09-01, later the same day: down to TWO top-level tabs.** The "three top-level
 * tabs" paragraph immediately below is kept for its history, but [SETTINGS] came off [TOP_LEVEL]
 * again within hours of landing on it - Kevin, on seeing it running: *"setup is being duplicated.
 * keep the top right corner one and drop the one beside meters."* [TOP_LEVEL]'s own doc comment
 * has the full account.
 *
 * **Three top-level tabs as of the 2026-09-01 calendar-home cutover (Kevin, verbatim): "month grid
 * primary. tapping a day on the month opens up view B. C as another tab. retire the bottom headers
 * like cred fleet etc. those we tap through from view C the meters."** This SUPERSEDES the five-tab
 * shape below (itself a supersession of the original four - see that entry's own history, kept for
 * the same reason this one is): [CALENDAR] is now the start destination (a month grid, with the
 * selected-day agenda as internal Compose state - "view B" - rather than a nav argument, same
 * convention this file's own doc comment already establishes below), [METERS] is the new "C" tab -
 * a dashboard of at-a-glance meters that tap THROUGH to [BODY]/[MONEY]/[FLEET]/[NOTES]/etc, which
 * is what "those we tap through from view C" means - and [SETTINGS] is unchanged. [MONEY],
 * [BODY], [FLEET], [NOTES] and every sub-route under them are **NOT deleted** - they stay registered
 * [NavHost] destinations, reachable as drill-downs from [METERS] and by every existing deep link
 * ([EXTRA_ROUTE], [ReminderAlarmReceiver], `onOpenAlarm`); they simply stop being tabs a driver
 * lands on directly. `TODAY` itself, and the `ui/TodayScreen.kt` it named, were deleted 2026-09-01
 * (one-today ticket 07) once every survivor it carried had a rehomed caller - [CalendarScreen]'s day
 * view (the plan checklist), [MetersScreen] (weather/area/newsletters/media), a Settings row
 * (the DASHBOARD button), and [CALENDAR] itself (`onOpenAlarm`'s target).
 *
 * **Five top-level tabs, 2026-08-07 to 2026-09-01 (superseded above, kept for its own history)**
 * (was four - ticket 07's original resolution §5 shape is superseded below, not amended in
 * place, so the history stays readable): Today is now the START DESTINATION, Money absorbed the
 * old `ledger/` route wholesale plus pantry's import flow (a grocery receipt is a purchase), and
 * pantry's own read screen moved under Money as a reachable sub-route rather than a tab of its
 * own. Body is entirely new - workouts/meals/bodyweight had a backend and no UI before this.
 *
 * **Cutover 5 (`docs/architecture/cutover5-2026-08-24.md`) briefly made `dashboard/` the start
 * destination and HOME hard key's target; REVERTED 2026-08-25** (see that doc's postscript) -
 * Kevin field-tested the pager overnight and ruled "revert everything to classic". `today/` was the
 * start destination again, exactly as before cutover 5, until the 2026-09-01 calendar-home cutover
 * above made [CALENDAR] the start destination instead, and one-today ticket 07 deleted `today/`
 * outright once its survivors were all rehomed (see above). `dashboard/` (the widget pager) STAYS IN
 * THE CODEBASE, reachable as an opt-in surface via a "Dashboard" row in [SETTINGS] (moved off
 * `today/`'s own now-deleted "DASHBOARD" button by that same ticket) - it is not deleted, only
 * demoted, so the pager/widgets/engine screens stay a hands path (ADR 0035) and the seven on-device
 * grid-feel rounds are not orphaned.
 *
 * ```
 * calendar/    <- start destination (2026-09-01); month grid + day view, no sub-routes
 * meters/      <- the third tab; taps through to money/body/fleet/notes/pantry
 * dashboard/   <- the widget pager; opt-in, reached from a Settings row
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
     * The start destination as of the 2026-09-01 calendar-home cutover (Kevin, verbatim: "month
     * grid primary. tapping a day on the month opens up view B") - see [com.kevin.legion.ui.CalendarScreen].
     * No sub-routes: the selected day's agenda ("view B") is internal Compose state inside that
     * screen, same convention this file's own class doc establishes for [NOTES]'s LISTS | CALENDAR
     * toggle. Replaced `TODAY` as `startDestination` and as the shell's HOME target; `TODAY` and the
     * screen it named were deleted outright 2026-09-01 (one-today ticket 07), once its survivors
     * were all rehomed - see this file's class doc.
     */
    const val CALENDAR = "calendar"

    /**
     * The third tab ("C", Kevin verbatim: "C as another tab... those we tap through from view C the
     * meters"), 2026-09-01 - see [com.kevin.legion.ui.MetersScreen]. A dashboard of at-a-glance
     * meters that tap through to [BODY]/[MONEY]/[FLEET]/[NOTES]/[MONEY_PANTRY], the same
     * "every pane taps through to its module" rule the deleted `ui/TodayScreen.kt` already applied.
     * Built as a skeleton in the calendar-home ticket; one-today ticket 07 added weather/area/
     * newsletters/the media mini-bar, rehomed off that same deleted screen.
     */
    const val METERS = "meters"

    /**
     * The widget pager, `com.kevin.legion.ui.widgets.WidgetPagerRoot`, hosted as an ordinary
     * [composable] destination here rather than a second Activity. Cutover 5
     * (`docs/architecture/cutover5-2026-08-24.md`) briefly made this the [NavHost]'s
     * `startDestination` and the HOME hard key's target; **reverted 2026-08-25** after Kevin lived
     * with it overnight and ruled "revert everything to classic" - `today/` was the start destination
     * and HOME hard key's target again, until the 2026-09-01 calendar-home cutover made [CALENDAR]
     * both instead. This route is NOT deleted: it stays reachable as an opt-in surface from a
     * "Dashboard" row in [SETTINGS] (moved off `today/`'s own now-deleted "DASHBOARD" button by
     * one-today ticket 07, 2026-09-01, when that screen was deleted), so the pager/widgets/generic
     * engine screens remain a hands path (ADR 0035) rather than orphaned code.
     */
    const val DASHBOARD = "dashboard"

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

    // VOICE_NOTES: not added here. A concurrent session (same day, ticket 04) registered
    // SETTINGS_VOICE_NOTES -> ui/voicenotes/VoiceNotesScreen.kt below instead - see that
    // constant's own doc comment. A second top-level route to the same screen was drafted here
    // briefly and reverted rather than shipping two live paths to one screen with no ruling on
    // which is canonical.

    const val FLEET = "fleet"
    const val FLEET_PLACES = "fleet/places"
    /** The car roster and the explicit active-car picker (2026-08-04) - see [CarsScreen]. */
    const val FLEET_CARS = "fleet/cars"
    /** Recorded `obd_samples` history for the active car (2026-08-04) - see [TelemetryScreen]. */
    const val FLEET_TELEMETRY = "fleet/telemetry"

    /** Was `ledger` - renamed for the tab, the screen underneath is still [LedgerScreen] unchanged. */
    const val MONEY = "money"
    // MONEY_IMPORT ("money/import") deleted - backend-erp ticket 25 ("statement ingestion leaves
    // the phone entirely"). Bank statements are ingested by the web app now.
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

    // SETTINGS_BACKEND_MIGRATION ("settings/backend-migration") deleted 2026-09-03 (live-sync
    // ticket 05's own follow-up, Kevin: "yes auto wire it. no more backend migrations page.").
    // It was backend-erp Phase 4's hands path for PlacesReconcile/PantryReconcile/FleetReconcile
    // (and, until 2026-09-02, EventsReconcile) - the last three of the seven reconciles that were
    // still manual-only now all run automatically off MainActivity.onResume, the same way
    // LedgerReconcile/MaintenanceScheduleReconcile/ObdSampleReconcile/ConversationAuditReconcile
    // already did, so nothing on the retired screen was reachable anywhere else. See
    // `.scratch/live-sync/map.md` and `memory/library/decisions.md`'s 2026-09-03 entry.

    // SETTINGS_LEDGER_REINGEST_DRY_RUN ("settings/ledger-reingest-dry-run") deleted - backend-erp
    // ticket 25. That dry run checked whether historical LOCAL statement files could recover their
    // reconciliation anchors; with ingestion moved to the web app there is nothing local left to
    // dry-run against.

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
     * The hands path for the four voice-note tools (`start_voice_note`, `stop_voice_note`,
     * `read_voice_note`, `list_voice_notes`) - see [com.kevin.legion.ui.voicenotes.VoiceNotesScreen],
     * which shipped with that feature but was left unrouted, making those tools voice-only in
     * practice. ADR 0035 does not allow that, so it is registered here (2026-09-01). Same shape as
     * [SETTINGS_MEMORY]: a leaf, no sub-routes.
     *
     * **Reached from `ui/MetersScreen.kt`'s own RECORDINGS pane since 2026-09-04, not from
     * Settings.** Kevin: "hiding in settings. it needs a place on the home screen." The route
     * NAME still starts with `settings/` (renaming it would touch every reference for no behaviour
     * change, and the constant is not user-facing) but nothing under Settings navigates here
     * anymore - `ui/settings/DataPrivacyScreen.kt`'s own doc comment records the move on that side.
     * Recording itself is now also possible without leaving the pane (a RECORD/STOP control lives
     * on METERS directly); this route stays for the list, detail, rename and delete affordances a
     * home-screen pane has no room for.
     */
    const val SETTINGS_VOICE_NOTES = "settings/voice-notes"

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

    /** The TWO top-level destinations, in display order.
     *
     * **[SETTINGS] dropped off this list 2026-09-01** (Kevin, on seeing it running: *"setup is being
     * duplicated. keep the top right corner one and drop the one beside meters"*). It was reachable
     * two ways at once - a tab here AND [com.kevin.legion.ui.common.StatusLine]'s SETUP stamp, whose
     * own doc comment already called itself "the only way into" settings. The stamp wins: it costs
     * no width in a row whose whole point is sparseness, and it was there first. [SETTINGS] is very
     * much still a registered destination - it is simply not a tab.
     *
     * Was six earlier the same day (`TODAY`, [MONEY], [BODY], [FLEET], [NOTES], [SETTINGS]; before
     * that, the four-tab shape [CALENDAR]'s class doc still records), then three at the
     * calendar-home cutover. [MONEY]/[BODY]/[FLEET]/[NOTES] are not gone, only demoted - see each
     * one's own doc comment. `TODAY` is gone outright: one-today ticket 07 deleted the screen and
     * the constant once every survivor on it was rehomed.
     *
     * **A route not on this list lights no tab, and that is now correct rather than a bug.**
     * [topLevelOf] returns null inside `settings/`, so the row shows neither tab selected while you
     * are in Setup - which is true, because Setup is not one of them. The 2026-08-02 defect that
     * comment below records was the opposite case: a SUB-route of a real tab going dark.
     *
     * Assistant is still NOT one of them - it's a mode, not a place (original resolution §5, still
     * true). */
    val TOP_LEVEL = listOf(CALENDAR, METERS)

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

    /**
     * Short label for a top-level route's tab. **DASHBOARD/TODAY/MONEY/BODY/FLEET/NOTES branches
     * removed 2026-09-01** (calendar-home cutover) rather than kept dead: [LegionHardKeyRow], the
     * only caller besides this file's own tests, was deleted in the same cutover
     * ([com.kevin.legion.ui.MainActivity]'s three-way CALENDAR/METERS/SETTINGS switch reads none of
     * this function at all - see that switch's own comment), and nothing else in the tree ever
     * called [label] with one of those six constants. Grep-confirmed before deletion.
     *
     * **[SETTINGS] branch dropped too, same session (2026-09-01), for the same reason it dropped
     * off [TOP_LEVEL] above.** [LegionTabRow] is the only production caller of this function and it
     * iterates [TOP_LEVEL] alone, which no longer includes [SETTINGS] - so `label(SETTINGS)` was
     * grep-confirmed reachable only from this file's own test. `else -> route` covers it exactly
     * as it would any other unlisted route, which is the same fallback [SETTINGS_ASSISTANT] and
     * every other settings sub-route already got before this branch existed. The branch used to
     * return "Setup" rather than "Settings" - the longer word wrapped to "Setting / s" in the bottom
     * bar on a 720px-wide device (observed 2026-08-07, back when [LegionHardKeyRow] had five tabs)
     * - preserved here since that measurement lives nowhere else now that the branch is gone; the
     * SETUP stamp [com.kevin.legion.ui.common.StatusLine] renders is its own separate literal string,
     * unaffected by this deletion either way.
     */
    fun label(route: String): String = when (route) {
        CALENDAR -> "Calendar"
        METERS -> "Meters"
        else -> route
    }
}
