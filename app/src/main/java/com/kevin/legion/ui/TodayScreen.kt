package com.kevin.legion.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ProactiveRaiseRow
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MealController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.sitrep.SitrepBuilder
import com.kevin.legion.sitrep.SitrepModule
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.ui.common.QuarantineTag
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.fleet.buildDueRows
import com.kevin.legion.ui.goals.GoalChecklistPanel
import com.kevin.legion.ui.media.MediaMiniBar
import com.kevin.legion.ui.notes.AgendaCalendarNotice
import com.kevin.legion.ui.notes.CalendarNotLinkedRow
import com.kevin.legion.ui.notes.buildAgendaCalendarNotice
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.world.AreaCard
import com.kevin.legion.util.clockTime
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.weather.WeatherController
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * `today` tab, the deck's HOME surface. **Rebuilt into a command center, command-center ticket 01
 * (`.scratch/command-center/issues/01-home-command-center.md`), superseding mission-control ticket
 * 16's calorie-led shape below.** Kevin, charting the map: *"its a command center of my daily
 * life. things like news, email, todos, workouts to do, alerts, location intelligence etc should
 * all be there instead."* The calorie pane LOSES the hero slot and becomes one tile among several -
 * see the map's rulings section for why this overrides ticket 16's own framing of INTAKE as "the
 * thing checked most often" (still true; it is simply no longer the thing HOME leads with).
 *
 * **The hierarchy (the ticket's own numbered list, built verbatim):**
 *  1. **HERO: TODAY** - the next calendar event with its clock (never the whole day's list - see
 *     [nextAgendaEntry]'s own doc comment for why the full multi-item AGENDA pane this superseded
 *     is gone from HOME specifically, not gone from the app: it is still one tap away on Notes,
 *     which already renders it via its LISTS | CALENDAR toggle), today's plan checklist
 *     ([GoalChecklistPanel], `compact = true`, unchanged from ticket 16's build), and ALERTS -
 *     everything the assistant currently wants Kevin to know, now with a third source: recent,
 *     undeclined lines from the proactive raise history, RENDERED only (see [buildAlertRows]'s own
 *     doc comment on [TodayUiState.recentRaises] - reading that table has no side effect and
 *     triggers no speech, the same "never re-speak what a raise already said" rule the map's own
 *     binding states).
 *  2. **CONTEXT STRIP** - a weather line ([weatherLine]) over [AreaCard] (area name + AirNow AQI,
 *     command-center ticket 08's own build, consumed here wholesale rather than re-implemented -
 *     see that file's own class doc for why it is on-demand, in-memory, never a background poll).
 *  3. **TILES**, each opening its full surface: the newsletters digest ([NewsDigestCard] - see
 *     its doc comment for why it never auto-fetches; the package/flight tiles that once led this
 *     row were retired by Kevin the day they shipped), INTAKE/BIO/LOG and CRED/FLEET
 *     (ticket 16's own half-tile row, re-ranked down from the top of the screen rather than
 *     rebuilt - INTAKE demotes into this row using [buildIntakeTile], the exact half-tile shape
 *     [BodyScreen] already uses for its own demoted INTAKE tile, never a second builder), and
 *     [MediaMiniBar] (ticket 04's own build - renders nothing when nothing is playing).
 *
 * **Silent domains keep full-size tiles with worded empty states, grid position never moves**
 * (ticket 16's binding, carried forward unchanged by this rewrite) - CLAUDE.md §4's wording
 * discipline applied to the whole screen.
 *
 * **Tap-through** (ticket 16's table, extended by this ticket's two new tiles): INTAKE/BIO tap
 * through [onOpenBody]; CRED and the ALARM/quarantine ALERTS rows tap through
 * `onOpenCategory(null)`; FLEET taps through [onOpenFleet]; LOG and the checklist tap through
 * [onOpenNotes]/[onOpenBody] respectively; the Gemini-key ALERTS row taps through
 * [onOpenKeySettings]; a goal exception's row taps through whichever of the above owns
 * [Goal.aspect] ([alertTargetForAspect]); a raised-history row taps through whichever tab owns its
 * [com.kevin.legion.service.ProactiveCategory] ([alertTargetForRaiseCategory]); [MediaMiniBar] taps
 * through [onOpenMedia] (command-center ticket 04's own media control panel, nested under
 * `settings/spotify/media` - see [LegionRoute.SETTINGS_SPOTIFY_MEDIA]'s own doc comment for why);
 * a lone "DASHBOARD" row at the top of the list taps through [onOpenDashboard] to the widget
 * pager, this screen's own opt-in hands path to it since the 2026-08-25 revert of cutover 5's home
 * flip (see [LegionRoute.DASHBOARD]'s own doc comment).
 * [AreaCard]/[NewsDigestCard] have no tap-through of their own: each IS
 * its own full surface already (a `DeckPane` with its own refresh affordance), so there is no
 * deeper screen to open - "each tile opening its full surface" is satisfied by the tile already
 * being that surface, not a summary of one.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill, same shape as
 * [LedgerScreen]/[FleetScreen]: [TodayScreen] is the state holder (reads controllers, no writes),
 * [TodayContent] is plain UI state and is what the `@Preview`s below exercise.
 */
data class TodayUiState(
    val loading: Boolean = true,
    val mealGap: DailyMealGap = DailyMealGap.NotLogged,
    /** Distinguishes D27's "not logged" reasons: no [com.kevin.legion.data.local.MealTarget] set at all, versus a target that exists but today has no [com.kevin.legion.data.local.MealLog] rows - [MealController.dayGap] alone cannot tell these apart (see [DailyMealGap.NotLogged]'s doc comment), so this screen queries the target separately. */
    val hasMealTarget: Boolean = false,
    /** BIO tile (ticket 16): [TodayGapResolvers.buildBioTile]'s already-formatted hero/caption. */
    val bioTile: BioTileData = BioTileData(hero = "NOT LOGGED", caption = "no weigh-ins yet"),
    /** Null while the month hasn't loaded yet - same contract [com.kevin.legion.ui.LedgerUiState.budgetVsActual] uses. CRED tile content is derived from this at render time by `buildCredTile`. */
    val budget: BudgetVsActual? = null,
    /** CRED tile balance line (2026-08-18, Kevin: "how much I've used so far and what's the
     * balance"): every account [LedgerController.accountBalances] has ever seen, [groupAccountBalances]-
     * collapsed the SAME way [LedgerScreen]'s own BALANCES surface collapses them so a card split
     * across two accountIds never shows twice. [buildCredBalanceLine] resolves which one (if any)
     * matches [nominatedAccountId] and what to print. */
    val ledgerBalances: List<com.kevin.legion.ledger.AccountBalance> = emptyList(),
    /** CRED tile balance line: which account [LedgerNominatedAccountPreferences] currently points
     * at - null means the driver has never nominated one. */
    val nominatedAccountId: String? = null,
    val maintenanceRows: List<DueRowView> = emptyList(),
    /** FLEET tile (ticket 09): items with no anchor at all - [buildFleetTile] must know this to stop
     * reading OK while the schedule has unanchored items, see that function's own doc comment. */
    val maintenanceUnknownCount: Int = 0,
    /** LOG tile (ticket 16): open-task count, reminders overdue, and whether any
     * [com.kevin.legion.data.local.ListItem] has ever existed - see
     * [TodayGapResolvers.buildLogTile]'s doc for exactly what "silent" means here. */
    val openTaskCount: Int = 0,
    val logHasAnyItems: Boolean = false,
    /** Today's timed items, one-off and recurring, PLUS today's Google Calendar events (ticket 13)
     * merged in by [com.kevin.legion.ui.notes.mergeAgenda] (now reached via
     * [com.kevin.legion.ui.agenda.buildDayAgenda]), sorted ascending - see [AgendaEntry]'s doc
     * comment. Command-center
     * ticket 01: HOME reads only the FIRST still-current entry off this list now
     * ([nextAgendaEntry]) rather than rendering every one of them - the full list is still built
     * here (Notes' own load reads it independently), the HERO pane just stopped listing it in full. */
    val agendaEntries: List<AgendaEntry> = emptyList(),
    /** Ticket 12's "reported, never silent" MISSED backlog - carried into AGENDA's summary line AND
     * the LOG tile (ticket 16), same wording [notesSummaryMessage] already produced pre-cyberdeck. */
    val notesMissedCount: Int = 0,
    /** Ticket 13 point 7: whether `READ_CALENDAR` is currently granted - [buildAgendaCalendarNotice]
     * turns this, not [agendaEntries]' emptiness alone, into whatever the AGENDA pane says about its
     * own Google coverage. Defaults true so a screen that has not finished its first load never
     * flashes a false "not linked" prompt before the real check runs. */
    val calendarPermissionGranted: Boolean = true,
    /** Context strip (command-center ticket 01): [WeatherController.current] at load time -
     * `null` until the first successful Open-Meteo fetch, rendered by [weatherLine] as its own
     * honest sentence rather than a blank line. */
    val weather: WeatherController.WeatherInfo? = null,
    /** The instant this state was built - the HERO pane's own clock ([clockTime]) and the anchor
     * [nextAgendaEntry] filters against, so both read off the SAME "now" a fresh load captured
     * rather than two different calls to [System.currentTimeMillis] drifting apart mid-composition. */
    val nowMs: Long = System.currentTimeMillis(),
)

@Composable
fun TodayScreen(
    onOpenNotes: () -> Unit = {},
    onOpenCategory: (String?) -> Unit = {},
    onOpenBody: () -> Unit = {},
    onOpenFleet: () -> Unit = {},
    /** Ticket 16: the Gemini-key ALERTS advisory row's tap-through - `settings/key`, the same
     * screen [KeyScreen] already renders. Defaults to a no-op so every pre-ticket-16 caller (there
     * was exactly one, [MainActivity]) keeps compiling until it wires the real navigation, same
     * posture every other `onOpen*` default on this screen already follows. */
    onOpenKeySettings: () -> Unit = {},
    /** Command-center ticket 01: [MediaMiniBar]'s own tap-through - the media control panel
     * (`settings/spotify/media`, ticket 04's build). Same "defaults to a no-op" posture as
     * [onOpenKeySettings] above. */
    onOpenMedia: () -> Unit = {},
    /** The pager's opt-in hands path (2026-08-25 revert of cutover 5's home flip) -
     * `LegionRoute.DASHBOARD`, mirroring how the pager's own now-removed "CLASSIC" button used to
     * point back here during the brief window it was HOME. Same "defaults to a no-op" posture. */
    onOpenDashboard: () -> Unit = {},
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(TodayUiState()) }
    // One-today ticket 01 cut the live `CalendarContract` read this reloaded after a permission
    // grant for - appointments now live in the local `events` table, always readable, so there is
    // no permission round trip left to re-check after. Kept as a plain reload hook (bumped nowhere
    // today) rather than deleted outright, matching InboxScreen's own reloadNonce shape.
    var reloadNonce by remember { mutableStateOf(0) }

    LaunchedEffect(reloadNonce) {
        val now = System.currentTimeMillis()
        val db = CarDatabase.getDatabase(context)

        // INTAKE: today's meal gap, plus a direct target read so the empty
        // state can tell "no target set" from "target set, nothing logged
        // yet" apart - see hasMealTarget's doc comment above.
        val mealTarget = db.mealTargetDao().currentTarget(dayStartEpoch(now))
        val mealGap = MealController.dayGap(context, now)

        // BIO tile (ticket 16): latest bodyweight plus a 4-week-ago trend comparison - the same
        // BodyweightLogDao reads HomeDigestBuilder.bioHeadline makes for the advisor digest, see
        // buildBioTile's own doc comment for why the trend math is restated rather than shared.
        val bioLookbackStart = now - 28L * 24 * 60 * 60 * 1000
        val bioWindowEnd = now - 14L * 24 * 60 * 60 * 1000
        val bioLatest = db.bodyweightLogDao().mostRecent()
        val bioLookback = db.bodyweightLogDao().forWindow(bioLookbackStart, bioWindowEnd)
        val bioTile = buildBioTile(bioLatest, bioLookback)

        // CRED tile: the US entity's current-month budget-versus-actual - the exact figure
        // ui.LedgerScreen's own budget section reads.
        val budget = LedgerController.budgetVsActual(context, LedgerEntity.US, YearMonth.now())

        // CRED tile balance line (2026-08-18, Kevin): the nominated account's own printed balance,
        // grouped the same way ui.LedgerScreen's BALANCES surface groups state.balances before
        // rendering (see LedgerController.groupAccountBalances's doc comment on why grouping is a
        // render-site concern, never accountBalances()'s own job).
        val ledgerBalances = LedgerController.accountBalances(context)
        val nominatedAccountId = com.kevin.legion.ledger.LedgerNominatedAccountPreferences.nominatedAccountId.value

        // FLEET tile: the active vehicle's maintenance schedule, same rows ui.FleetScreen's DUE
        // block builds from the same MaintenanceItem list - see buildDueRows's own doc for the
        // overdue-first ordering.
        val vehicle = VehicleController.currentVehicle(context)
        val currentMileage = VehicleController.currentMileage(vehicle)
        val items: List<MaintenanceItem> = com.kevin.legion.vehicle.FleetEngineStore.getForVehicle(context, vehicle.obdMac)

        // LOG tile: open-task count mirrors HomeDigestBuilder.logHeadline's own filter
        // (undone, unscheduled, non-recurring) - see buildLogTile's own doc for why this is
        // restated here rather than calling that digest builder.
        // Cutover 1: rewired off ListItemDao onto NotesController, which is now engine-backed.
        val allActiveItems = com.kevin.legion.notes.NotesController.allItems(context)
        val openTaskCount = allActiveItems.count { !it.done && it.startsAt == null && it.repeatKind == null }
        val logHasAnyItems = allActiveItems.isNotEmpty()
        val notesMissedCount = NotesController.missedItems(context).size

        // AGENDA: today's window in the DEVICE zone (a reminder is a real instant the driver
        // picked - see `ui/notes/NotesResolvers.kt`'s doc comment on why this is the
        // shortDate/compactDate family, never documentDate's UTC convention). This is the exact
        // pair of NotesController reads the pre-cyberdeck NOTES row already made; the only change
        // is keeping the item text and resolved instant instead of collapsing straight to a count
        // (see AgendaEntry's doc comment) - no new query. Command-center ticket 01: HOME's HERO
        // pane only ever shows the first still-current entry off this list ([nextAgendaEntry]),
        // but the list itself is still built in full here - the full day is one query, "next" is
        // a render-time filter over it, never a second, narrower query.
        // **Extracted into `ui/agenda/DayAgenda.kt`'s [buildDayAgenda]** (this file, NotesScreen's
        // "today" build, and NotesScreen's "month" build restated the same
        // NotesController.timedItemsInWindow/allRecurringItems + Recurrence.occurrencesInWindow +
        // db.eventDao().activeByKindInWindow(EventKind.APPOINTMENT, ...) triple verbatim in three
        // places - the SAME reasons Kotlin top-level `private` biting `AgendaRow`/this pane
        // elsewhere in this file already document. All three now call the one shared builder.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // The local table is always readable - there is no permission to be refused any more - so
        // `calendarPermissionGranted` is always true post-cut; kept as a field for
        // [buildAgendaCalendarNotice]'s worded-empty-vs-unreadable split, which still reads
        // correctly for the one case left that matters (a genuinely empty window).
        val calendarPermissionGranted = true
        val agendaEntries = com.kevin.legion.ui.agenda.buildDayAgenda(context, today, zone)

        // ALERTS (ticket 16, extended by command-center ticket 01): every currently-quarantined
        // ledger document (CLAUDE.md §4), the Gemini key's presence, every overdue active goal, and
        // now the recent, undeclined proactive-raise history - see buildAlertRows's own doc comment
        // for exactly why these four and not the other two ticket 04 named (a Drive sync failure
        // and an active vehicle DTC, still absent for the same "would be inventing state" reason).

        // Context strip (command-center ticket 01): the same WeatherController the foreground
        // service and the sitrep already read - `refresh()` is a no-op past its own 30-minute TTL
        // and returns the cached value with no GPS fix, so this never blocks HOME's load on a
        // fresh network round trip. `current()` alone (no refresh) would risk a stale null on a
        // screen opened before the service's own slow loop has fired once; calling `refresh()`
        // here is what makes a fresh install's first HOME view actually show weather instead of
        // waiting on the service.
        val weather = WeatherController.refresh()

        state = TodayUiState(
            loading = false,
            mealGap = mealGap,
            hasMealTarget = mealTarget != null,
            bioTile = bioTile,
            budget = budget,
            ledgerBalances = ledgerBalances,
            nominatedAccountId = nominatedAccountId,
            maintenanceRows = buildDueRows(items, currentMileage, vehicle.odometerBaseline == 0, now),
            maintenanceUnknownCount = items.count { VehicleController.isUnknown(it) },
            openTaskCount = openTaskCount,
            logHasAnyItems = logHasAnyItems,
            agendaEntries = agendaEntries,
            notesMissedCount = notesMissedCount,
            calendarPermissionGranted = calendarPermissionGranted,
            weather = weather,
            nowMs = now,
        )
    }

    TodayContent(
        state, onOpenNotes, onOpenCategory, onOpenBody, onOpenFleet, onOpenKeySettings, onOpenMedia,
        onOpenDashboard = onOpenDashboard,
        // One-today ticket 01: nothing left to grant - the local `events` table needs no runtime
        // permission - so this stays the default no-op. [CalendarNotLinkedRow] can no longer render
        // (calendarPermissionGranted is always true post-cut) but the plumbing is left in place
        // rather than torn out screen-by-screen; `buildAgendaCalendarNotice`'s own doc comment still
        // covers the one distinction that still matters (unreadable vs. genuinely empty).
    )
}

/** How far back HOME's ALERTS pane looks for a raise still worth showing (command-center ticket
 * 01) - a day, the same rough "still today's business" window the rest of this screen already
 * reasons in local-day terms (AGENDA's own `dayStart`/`dayEnd`). Not tied to the proactive engine's
 * own daily-cap window on purpose: those are two separate concepts (how many times it may SPEAK
 * versus how long a spoken line stays worth RE-SHOWING on a screen), and conflating them would make
 * a future change to one silently move the other. */
/** [com.kevin.legion.ui.TodayGapResolvers.capAlertRows]'s own five-row cap already bounds what
 * actually renders; this only bounds how much the query itself has to sort, generously above that
 * so a genuinely busy day never trims a real row before the cap gets a chance to word the overflow. */

/** Plain UI: [state] plus callbacks, no controller/DB reference - see the file doc comment. */
@Composable
fun TodayContent(
    state: TodayUiState,
    onOpenNotes: () -> Unit = {},
    onOpenCategory: (String?) -> Unit = {},
    onOpenBody: () -> Unit = {},
    onOpenFleet: () -> Unit = {},
    onOpenKeySettings: () -> Unit = {},
    onOpenMedia: () -> Unit = {},
    onOpenDashboard: () -> Unit = {},
    onRequestCalendarPermission: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.loading) {
            Text("LOADING...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
        } else {
            TodayListing(state, onOpenNotes, onOpenCategory, onOpenBody, onOpenFleet, onOpenKeySettings, onOpenMedia, onOpenDashboard, onRequestCalendarPermission)
        }
    }
}

@Composable
private fun TodayListing(
    state: TodayUiState,
    onOpenNotes: () -> Unit,
    onOpenCategory: (String?) -> Unit,
    onOpenBody: () -> Unit,
    onOpenFleet: () -> Unit,
    onOpenKeySettings: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenDashboard: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    // Resolves an ALERTS row's AlertTarget to the one real navigation callback it means - kept as
    // one small `when` here rather than threading five callbacks down into TodayGapResolvers.kt,
    // which stays Compose-free (this file's own long-standing "pure builder, thin wrapper" split).
    val onAlertTap: (AlertTarget) -> Unit = { target ->
        when (target) {
            AlertTarget.CRED -> onOpenCategory(null)
            AlertTarget.KEY -> onOpenKeySettings()
            AlertTarget.BIO -> onOpenBody()
            AlertTarget.LOG -> onOpenNotes()
            AlertTarget.FLEET -> onOpenFleet()
            AlertTarget.NONE -> {}
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        // The pager's own opt-in hands path (2026-08-25 revert of cutover 5's home flip) - a
        // single row, top of the list, so it never competes with the HERO pane's own whole-pane
        // click target (onOpenNotes) just below. Mirrors where the pager's own now-removed
        // "CLASSIC" button used to sit (its header row) without needing this screen to grow a
        // second header row of its own.
        item(key = "dashboard-link") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                DeckButton(text = "DASHBOARD", onClick = onOpenDashboard)
            }
        }

        // ================================================================ HERO: TODAY

        // ------------------------------------------------------------ HERO 1/3: next event + clock
        item(key = "hero-today") {
            val calendarNotice = buildAgendaCalendarNotice(state.calendarPermissionGranted, state.agendaEntries.size)
            val nextEntry = nextAgendaEntry(state.agendaEntries, state.nowMs)
            DeckPane(header = "Today", headerAccent = clockTime(state.nowMs), modifier = Modifier.clickable(onClick = onOpenNotes)) {
                Text(
                    notesSummaryMessage(state.agendaEntries.size, state.notesMissedCount),
                    style = LegionType.stamp,
                    // A missed reminder is a gap in your own log, not a failed ingest gate - amber
                    // (data/advisory), never sem.quarantined. ALERTS below reserves red.
                    color = if (state.notesMissedCount > 0) MaterialTheme.colorScheme.primary else sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                when {
                    // Unreadable: permission refused - never rendered as a clear day.
                    calendarNotice.message != null -> CalendarNotLinkedRow(calendarNotice.message, onGrant = onRequestCalendarPermission)
                    // Empty: permission granted, genuinely nothing on the calendar today.
                    calendarNotice.showNothingScheduled -> DeckRow(label = "Next", value = "NOTHING SCHEDULED")
                    // A real next thing.
                    nextEntry != null -> AgendaRow(nextEntry)
                    // A third, distinct fact: the day had things on it and every one of them has
                    // already passed - not the same sentence as an empty day (nextAgendaEntry's own
                    // doc comment).
                    else -> Text(
                        "Nothing left today.",
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ------------------------------------------------------------ HERO 2/3: today's plan checklist
        item(key = "checklist-pane") {
            GoalChecklistPanel(
                compact = true,
                modifier = Modifier.padding(top = 9.dp).clickable(onClick = onOpenBody),
            )
        }

        // The Alerts pane that stood here was retired by Kevin the day the command center
        // shipped (2026-08-22: "alerts tab in home is useless. retire it. delete"). What it
        // showed still surfaces where it is actionable: quarantined files on the Money
        // screen, the key state in Settings, overdue goals on the goals panel, and a raise
        // that mattered was already SPOKEN by the proactive bus - a second, silent listing
        // of things already said or shown elsewhere answered no question. The raise-history
        // DAO read and buildAlertRows survive for the audit surfaces that still use them.

        // ================================================================ CONTEXT STRIP

        // Weather line + AreaCard (area name + AirNow AQI) - "where am I, and what's it like"
        // (command-center ticket 01's point 2). The weather line is a plain Text, not a DeckPane of
        // its own - it is genuinely a STRIP, one sentence, sitting directly above the fuller AreaCard
        // rather than duplicating that card's own frame for a single line of text.
        item(key = "context-strip-weather") {
            Text(
                weatherLine(state.weather),
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(top = 9.dp, start = 12.dp, end = 12.dp, bottom = 2.dp),
            )
        }
        item(key = "context-strip-area") {
            AreaCard()
        }

        // ================================================================ TILES

        // The package and flight tiles that stood here were retired hours after shipping
        // (Kevin, 2026-08-22: "package and flight tabs in home are useless. just retire it
        // delete"). The voice tools and their shared LiveToolbox functions are untouched -
        // asking "where is my package" still works; a permanent tile for an occasional
        // question answered no daily need.

        // ------------------------------------------------------------ newsletters digest (tap-only)
        item(key = "tile-newsletters") {
            NewsDigestCard(modifier = Modifier.padding(top = 9.dp))
        }

        // ------------------------------------------------------------ INTAKE / BIO / LOG (HALF tiles)
        // INTAKE demotes into this row (command-center ticket 01) using the exact half-tile shape
        // BodyScreen's own demoted INTAKE tile already uses ([buildIntakeTile]) - never a second
        // builder for the same figure.
        item(key = "tile-row-intake-bio-log") {
            val intakeTile = buildIntakeTile(state.mealGap, state.hasMealTarget)
            val logTile = buildLogTile(state.openTaskCount, state.notesMissedCount, state.logHasAnyItems)
            EqualHeightRow(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalGap = 9.dp) {
                HalfTile(
                    header = "Intake",
                    hero = intakeTile.hero,
                    caption = intakeTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenBody),
                )
                HalfTile(
                    header = "Bio",
                    hero = state.bioTile.hero,
                    caption = state.bioTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenBody),
                )
                HalfTile(
                    header = "Log",
                    hero = logTile.hero,
                    caption = logTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenNotes),
                )
            }
        }

        // ------------------------------------------------------------ CRED / FLEET (HALF tiles, "money snapshot" + "fleet status")
        item(key = "tile-row-cred-fleet") {
            val monthLabel = ledgerSweepMonthLabel(YearMonth.now())
            val credTile = buildCredTile(state.budget, monthLabel)
            // The nominated account's own balance (2026-08-18, Kevin: "no need for the line
            // graph. just how much I've used so far and what's the balance") - see
            // buildCredBalanceLine's own doc comment for every branch. Declared HERE, beside
            // credTile, rather than inside the tile's `extra` lambda, because the tile's own
            // `secondHero`/`secondCaption` parameters read it too.
            val credBalanceLine = buildCredBalanceLine(
                com.kevin.legion.ledger.groupAccountBalances(state.ledgerBalances),
                state.nominatedAccountId,
            )
            val fleetTile = buildFleetTile(state.maintenanceRows, state.maintenanceUnknownCount)
            // Equal-height tiles (ticket 05's grammar treats HALF as ONE shape, not two shapes
            // that happen to sit side by side) via EqualHeightRow, NOT `Row(...).height(IntrinsicSize.Min)`
            // - that was the first attempt and it crashed the app on-device (dropbox-caught):
            // [DeckPane] wraps [BoxWithConstraints] for its label-pill sizing, BoxWithConstraints is
            // built on SubcomposeLayout, and Compose hard-refuses intrinsic-measurement queries
            // against a SubcomposeLayout ("Asking for intrinsic measurements of SubcomposeLayout
            // layouts is not supported" - IllegalStateException, every launch, every time). See
            // EqualHeightRow's own doc comment for the two-real-measure-passes workaround.
            EqualHeightRow(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalGap = 9.dp) {
                HalfTile(
                    header = "Cred",
                    hero = credTile.hero,
                    caption = credTile.caption,
                    modifier = Modifier.clickable(onClick = { onOpenCategory(null) }),
                    // Two figures, two subtitles, and nothing else (Kevin, 2026-08-18). Only a real
                    // balance earns the second hero slot; every advisory state renders as a
                    // sentence in `extra` instead - see that block's own comment.
                    secondHero = credBalanceLine.primary.takeIf { !credBalanceLine.isAdvisory },
                    secondCaption = credBalanceLine.secondary?.takeIf { !credBalanceLine.isAdvisory },
                ) {
                    // An advisory ("NO ACCOUNT NOMINATED", "<id> NOT FOUND", "no balance ever
                    // printed") is a SENTENCE, not a figure, so it stays stamp-sized down here
                    // rather than being forced through the hero slot - a 20-character string in
                    // displayMedium is exactly the overflow HalfTileHero's own doc warns about.
                    // The real figure goes through `secondHero` above; see the branch that sets it.
                    if (credBalanceLine.isAdvisory) {
                        Text(
                            credBalanceLine.primary,
                            style = LegionType.stamp,
                            color = LocalLegionSemantics.current.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        if (credBalanceLine.secondary != null) {
                            Text(
                                credBalanceLine.secondary,
                                style = LegionType.stamp,
                                color = LocalLegionSemantics.current.faint,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
                HalfTile(
                    header = "Fleet",
                    hero = fleetTile.hero,
                    caption = fleetTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenFleet),
                )
            }
        }

        // ------------------------------------------------------------ media mini-bar
        // Renders nothing when nothing is playing (MediaMiniBar's own early return) - HOME shows
        // what IS true about the day, and "nothing playing" earns no tile of its own.
        item(key = "tile-media") {
            MediaMiniBar(onOpenMedia = onOpenMedia)
        }
    }
}

/**
 * Newsletters digest tile (command-center ticket 01's own build - no pre-existing composable
 * covered this, unlike every other tile on this screen). Wraps [SitrepBuilder.build] scoped to
 * [SitrepModule.NEWS] alone - the exact machinery the scheduled sitrep already uses for its own
 * NEWS section (`SitrepBuilder`'s own class doc: read-through, background Gmail fetch permitted
 * only inside a sitrep the user scheduled or explicitly asked for), never a second summarization
 * path.
 *
 * **Deliberately the one tile on this screen with NO auto-fetch.** Every other mail-derived card
 * ([AreaCard]) fetches once on first compose, which the ticket still
 * counts as "on demand" (opening the screen is the demand). Newsletters is different by the
 * ticket's own explicit instruction ("On-demand only (a tap)") - a newsletter check folds several
 * message bodies into one prompt and pays for a real LLM call, where the others are one metadata
 * search; the tap is what keeps that cost tied to an actual ask rather than every visit to HOME.
 *
 * In-memory only (`remember`, no Room row, no cache file) - navigating away and back starts blank
 * again - refresh is a user act, never a background poll.
 *
 * **No setup required (command-center ticket 12, Kevin: "take from my gmail > summarize").** This
 * card used to only ever say "not configured" for anyone who had never curated
 * [com.kevin.legion.sitrep.SitrepSettings.newsletterSenders] by hand - which was everyone, since
 * nothing in the app ever prompted for that list. [SitrepBuilder.build] now falls back to a
 * no-config Gmail search when the list is empty (`SitrepBuilder.NO_CONFIG_NEWSLETTER_QUERY`), so
 * this card needs no change of its own to benefit - it already only displays whatever sentence
 * came back.
 */
@Composable
private fun NewsDigestCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var state by remember { mutableStateOf<NewsDigestState>(NewsDigestState.Idle) }

    fun check() {
        state = NewsDigestState.Loading
        scope.launch {
            // SitrepBuilder.build already returns every real outcome as its own worded sentence
            // (NewsOutcome's four failure/empty branches plus the happy path) - this card never
            // has to re-derive success/failure, only display what came back.
            val text = SitrepBuilder.build(context, setOf(SitrepModule.NEWS))
            state = NewsDigestState.Ready(text, System.currentTimeMillis())
        }
    }

    DeckPane(header = "Newsletters", modifier = modifier) {
        when (val s = state) {
            is NewsDigestState.Idle -> {
                Text(
                    "Not checked this session - a check reads newsletter-shaped mail from your Gmail and summarizes it.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                TextButton(onClick = { check() }) { Text("CHECK NEWSLETTERS") }
            }
            is NewsDigestState.Loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text("Checking your newsletters...", style = LegionType.stamp, color = sem.faint)
            }
            is NewsDigestState.Ready -> {
                Text(s.text, style = MaterialTheme.typography.bodySmall, color = sem.data)
                Text(
                    "fetched ${clockTime(s.fetchedAtMs)}",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { check() }) { Text("CHECK AGAIN") }
            }
        }
    }
}

/** [NewsDigestCard]'s own three states - a sealed type for the same reason every other on-demand
 * card on this screen uses one (`AreaCard.kt`'s `AreaCardState`): "not yet asked", "asked, waiting", and "asked, got an answer" are three
 * different facts a nullable string cannot keep apart. */
private sealed class NewsDigestState {
    object Idle : NewsDigestState()
    object Loading : NewsDigestState()
    data class Ready(val text: String, val fetchedAtMs: Long) : NewsDigestState()
}

/**
 * One AGENDA row (ticket 13 point 4): a [AgendaSource.GOOGLE] entry carries a `CAL` tag so its
 * source reads in WORDS on the row itself, never by colour alone - [DeckRow]'s `value` slot is
 * already amber for every row regardless of source (ticket 03's fixed amber-mono value colour), so
 * colour alone could never have carried this distinction anyway. A [AgendaSource.LOCAL] row stays
 * tagless, this pane's existing silent-is-strong posture. Command-center ticket 01: also reused
 * verbatim for the HERO pane's single next-event row - the same shape for one row as it always was
 * for many.
 */
@Composable
private fun AgendaRow(entry: AgendaEntry) {
    DeckRow(
        label = entry.label,
        value = if (entry.allDay) "ALL DAY" else clockTime(entry.timeMs),
        tag = if (entry.source == AgendaSource.GOOGLE) {
            { DeckTag("CAL", DeckTagStyle.OUTLINE_MUTED) }
        } else {
            null
        },
    )
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Today: loading", widthDp = 360, heightDp = 800)
@Composable
private fun PreviewTodayLoading() = LegionTheme {
    TodayContent(TodayUiState(loading = true))
}

@Preview(name = "Today: everything empty (fresh install)", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewTodayAllEmpty() = LegionTheme {
    TodayContent(
        TodayUiState(
            loading = false,
            mealGap = DailyMealGap.NotLogged,
            hasMealTarget = false,
            bioTile = BioTileData(hero = "NOT LOGGED", caption = "no weigh-ins yet"),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = YearMonth.now(),
                lines = emptyList(),
                uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                coverage = emptyList(),
                excludedOwnAccountMovements = com.kevin.legion.ledger.ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            maintenanceRows = emptyList(),
            openTaskCount = 0,
            logHasAnyItems = false,
            agendaEntries = emptyList(),
            weather = null,
        ),
    )
}

@Preview(name = "Today: logged, mixed tiles, alerts", widthDp = 360, heightDp = 1000)
@Composable
private fun PreviewTodayMixed() = LegionTheme {
    TodayContent(
        TodayUiState(
            loading = false,
            mealGap = DailyMealGap.Logged(
                com.kevin.legion.plan.PlanGap(
                    target = com.kevin.legion.meals.MacroTotals(2200, 150.0, 220.0, 70.0),
                    actual = com.kevin.legion.meals.MacroTotals(1650, 90.0, 160.0, 55.0),
                    gap = com.kevin.legion.meals.MacroTotals(550, 60.0, 60.0, 15.0),
                    tier = com.kevin.legion.plan.TrustTier.REPORTED,
                ),
            ),
            hasMealTarget = true,
            bioTile = BioTileData(hero = "82.4", caption = "KG - DOWN 4WK"),
            ledgerBalances = listOf(
                com.kevin.legion.ledger.AccountBalance(
                    accountId = "BOFA-CHECKING",
                    currency = com.kevin.legion.data.local.LedgerCurrency.USD,
                    balanceCents = 381_200L,
                    asOfMs = PREVIEW_CRED_AS_OF_MS,
                ),
            ),
            nominatedAccountId = "BOFA-CHECKING",
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = YearMonth.now(),
                lines = listOf(
                    com.kevin.legion.ledger.BudgetLine(
                        category = "Groceries",
                        gap = com.kevin.legion.plan.PlanGap(target = 60_000L, actual = 41_200L, gap = 18_800L, tier = com.kevin.legion.plan.TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                ),
                uncategorized = UncategorizedSpend(spentCents = 3_412L, hasProvisionalRows = false),
                coverage = emptyList(),
                excludedOwnAccountMovements = com.kevin.legion.ledger.ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            maintenanceRows = listOf(
                DueRowView("Oil Change", "OVERDUE", "every 5,000 mi - last at 130,200", overdue = true),
                DueRowView("Tire Rotation", "in 3 mo", "every 6 mo - last Feb 2026", overdue = false),
            ),
            openTaskCount = 4,
            logHasAnyItems = true,
            agendaEntries = listOf(
                AgendaEntry("Pick up dry cleaning", PREVIEW_NOW_MS + 3_600_000L, allDay = false),
                AgendaEntry("Kevin's birthday", PREVIEW_NOW_MS, allDay = true),
            ),
            notesMissedCount = 1,
            weather = WeatherController.WeatherInfo(tempF = 68, description = "partly cloudy", caution = false),
            nowMs = PREVIEW_NOW_MS,
        ),
    )
}

/** A fixed instant for the mixed preview's clock/next-event/raise timestamps, so the preview
 * renders the same way every time rather than drifting with whatever moment it happens to compose. */
private val PREVIEW_NOW_MS = LocalDate.of(2026, 8, 22).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** A fixed UTC-midnight instant (2026-08-12) for the CRED tile's "as of" previews - [com.kevin.legion.util.documentDateCompact] reads document dates in UTC, matching [com.kevin.legion.ui.ledger.LedgerRows]'s own `PREVIEW_AS_OF_MS`. */
private val PREVIEW_CRED_AS_OF_MS = java.time.LocalDate.of(2026, 8, 12).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

/** Base state PreviewTodayMixed already builds, minus the CRED balance line, reused by the four
 * CRED-balance-state previews below so each one only has to vary [TodayUiState.ledgerBalances] /
 * [TodayUiState.nominatedAccountId] - see [buildCredBalanceLine]'s own doc comment for what each
 * state means. */
private fun previewCredBaseState() = TodayUiState(
    loading = false,
    mealGap = DailyMealGap.NotLogged,
    hasMealTarget = true,
    bioTile = BioTileData(hero = "82.4", caption = "KG - DOWN 4WK"),
    budget = BudgetVsActual(
        entity = LedgerEntity.US,
        month = YearMonth.now(),
        lines = emptyList(),
        uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
        coverage = emptyList(),
        excludedOwnAccountMovements = com.kevin.legion.ledger.ExcludedOwnAccountMovements(0, 0L, emptyList()),
    ),
    maintenanceRows = emptyList(),
    openTaskCount = 0,
    logHasAnyItems = false,
    agendaEntries = emptyList(),
)

@Preview(name = "Today: CRED balance - nothing nominated", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewCredBalanceNotNominated() = LegionTheme {
    TodayContent(previewCredBaseState().copy(nominatedAccountId = null))
}

@Preview(name = "Today: CRED balance - nominated account not found", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewCredBalanceNotFound() = LegionTheme {
    TodayContent(
        previewCredBaseState().copy(
            nominatedAccountId = "BOFA-CHECKING",
            ledgerBalances = listOf(
                com.kevin.legion.ledger.AccountBalance(
                    accountId = "DBS-CHECKING",
                    currency = com.kevin.legion.data.local.LedgerCurrency.SGD,
                    balanceCents = 216_582L,
                    asOfMs = PREVIEW_CRED_AS_OF_MS,
                ),
            ),
        ),
    )
}

@Preview(name = "Today: CRED balance - no balance ever printed (BofA card)", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewCredBalanceNeverPrinted() = LegionTheme {
    TodayContent(
        previewCredBaseState().copy(
            nominatedAccountId = "BOFA ****4471",
            ledgerBalances = listOf(
                com.kevin.legion.ledger.AccountBalance(
                    accountId = "BOFA ****4471",
                    currency = com.kevin.legion.data.local.LedgerCurrency.USD,
                    balanceCents = null,
                    asOfMs = null,
                ),
            ),
        ),
    )
}

@Preview(name = "Today: CRED balance - real figure, no as-of date", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewCredBalanceNoAsOf() = LegionTheme {
    TodayContent(
        previewCredBaseState().copy(
            nominatedAccountId = "BOFA-CHECKING",
            ledgerBalances = listOf(
                com.kevin.legion.ledger.AccountBalance(
                    accountId = "BOFA-CHECKING",
                    currency = com.kevin.legion.data.local.LedgerCurrency.USD,
                    balanceCents = 381_200L,
                    asOfMs = null,
                ),
            ),
        ),
    )
}
