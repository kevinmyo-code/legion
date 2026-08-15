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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.calendar.CalendarProvider
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MealController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.Recurrence
import com.kevin.legion.notes.endFromItem
import com.kevin.legion.notes.ruleFromItem
import com.kevin.legion.ui.common.DeckMeter
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRange
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.ui.common.QuarantineTag
import com.kevin.legion.ui.common.bucketDailySumCents
import com.kevin.legion.ui.common.deckRangeStartMs
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.fleet.buildDueRows
import com.kevin.legion.ui.notes.AgendaCalendarNotice
import com.kevin.legion.ui.notes.CalendarNotLinkedRow
import com.kevin.legion.ui.notes.buildAgendaCalendarNotice
import com.kevin.legion.ui.notes.mergeAgenda
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import com.kevin.legion.vehicle.VehicleController
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * `today` tab, the deck's HOME surface. **Rebuilt mission-control ticket 16** to ticket 11's
 * corrected inventory, on ticket 05's tiling grammar: hero, then tiles, then full-width lists,
 * inside the two-column grid (328dp interior, 9dp gutter, 159dp half tile). Six panes, FIXED
 * order, always -
 *  - **INTAKE** (FULL, hero) - today's calorie gap ([MealController.dayGap]), unchanged from the
 *    pre-tiling screen: still the thing checked most often.
 *  - **BIO / CRED / FLEET / LOG** (HALF, one row of four) - **replaces SYSTEMS SWEEP**, which is
 *    DISSOLVED by this ticket, not kept as a pane with tiles inside it. One figure, one qualifier
 *    per tile, matching [com.kevin.legion.advisor.AdvisorAspect]'s own four-aspect vocabulary
 *    rather than the old sweep's SLEEP/TRAINING WK/LEDGER/FLEET rows - see
 *    `TodayGapResolvers.kt`'s "mission-control ticket 16: HALF tiles" section for exactly what
 *    each tile shows and why SLEEP/TRAINING WK are gone from HOME entirely (still live in
 *    [BodyScreen]'s own drilldown).
 *  - **AGENDA** (FULL) - today's timed items, one-off and recurring, unchanged in content from the
 *    pre-tiling screen.
 *  - **ALERTS** (FULL) - **"everything needing you"** (ticket 11 section 3): ALARM items
 *    (quarantined ledger documents), ADVISORY items (no Gemini key set) and goal exceptions
 *    (overdue active [Goal]s) in one pane, ALARM always first, capped at five with a worded
 *    overflow line. See `TodayGapResolvers.kt`'s `buildAlertRows`/`capAlertRows` for the pure
 *    ordering/cap logic and their own doc comments for exactly which advisory sources are wired
 *    and which are named but absent (a Drive sync failure and an active vehicle DTC both have no
 *    readable state anywhere in the app today - wiring either would be inventing state, which
 *    ticket 16's binding forbids).
 *
 * **Silent domains keep full-size tiles with worded empty states, grid position never moves**
 * (ticket 16's binding, restating the pre-tiling screen's own "stated, never hidden" rule one
 * level up onto layout) - CLAUDE.md §4's wording discipline applied to the whole screen.
 *
 * **Tap-through** (ticket 11's corrected table - the hard keys do NOT map the way their names
 * suggest, traced in `MainActivity.kt`'s route list: `LOG` is `notes`, `CRED` is `money`):
 * INTAKE and BIO tap through [onOpenBody]; CRED and the ALARM/quarantine ALERTS rows tap through
 * `onOpenCategory(null)` (money, unfiltered); FLEET taps through [onOpenFleet]; LOG and AGENDA tap
 * through [onOpenNotes]; the Gemini-key ALERTS row taps through [onOpenKeySettings]; a goal
 * exception's ALERTS row taps through whichever of the above owns [Goal.aspect]
 * ([alertTargetForAspect]).
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
    /** [dayElapsedFraction] at load time - the INTAKE hero's [DeckMeter] pace tick. */
    val paceFraction: Float = 0f,
    /** Quant-viz ticket 11: the INTAKE hero's 7-day kcal trend, under the [DeckMeter] - the EXACT
     * same [bucketMealKcalDaily] over the SAME [MealController.mealsInWindow] read [BodyScreen]'s
     * own INTAKE sparkline uses, never a second series definition. `null` slot = unlogged day, per
     * the chart kit's gap-never-zero invariant. */
    val intakeSparkline: List<Float?> = emptyList(),
    /** BIO tile (ticket 16): [TodayGapResolvers.buildBioTile]'s already-formatted hero/caption. */
    val bioTile: BioTileData = BioTileData(hero = "NOT LOGGED", caption = "no weigh-ins yet"),
    /** Null while the month hasn't loaded yet - same contract [com.kevin.legion.ui.LedgerUiState.budgetVsActual] uses. CRED tile content is derived from this at render time by `buildCredTile`, not stored pre-formatted, because it also feeds [ledgerCumulativeSparkline]'s own load. */
    val budget: BudgetVsActual? = null,
    /** Quant-viz ticket 11 item 2: CRED tile's month-to-date cumulative daily spend, truncated at
     * today. Built from [LedgerController.monthOperatingExpenses] folded through
     * [com.kevin.legion.ui.common.bucketDailySumCents] then [cumulativeDailySpendCents] - the SAME
     * [budget]'s own [BudgetVsActual.coverage] this screen already loaded, never a second coverage
     * fetch. */
    val ledgerCumulativeSparkline: List<Float?> = emptyList(),
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
     * merged in by [mergeAgenda], sorted ascending - see [AgendaEntry]'s doc comment. */
    val agendaEntries: List<AgendaEntry> = emptyList(),
    /** Ticket 12's "reported, never silent" MISSED backlog - carried into AGENDA's summary line AND
     * the LOG tile (ticket 16), same wording [notesSummaryMessage] already produced pre-cyberdeck. */
    val notesMissedCount: Int = 0,
    /** Ticket 13 point 7: whether `READ_CALENDAR` is currently granted - [buildAgendaCalendarNotice]
     * turns this, not [agendaEntries]' emptiness alone, into whatever the AGENDA pane says about its
     * own Google coverage. Defaults true so a screen that has not finished its first load never
     * flashes a false "not linked" prompt before the real check runs. */
    val calendarPermissionGranted: Boolean = true,
    /** ALERTS ALARM rows (ticket 16): every currently-quarantined ingested file (CLAUDE.md §4). The
     * FULL list, not just a count, because each one is now its own row - see
     * [TodayGapResolvers.buildAlertRows]. */
    val quarantinedFiles: List<IngestedFile> = emptyList(),
    /** ALERTS ADVISORY row (ticket 16): whether a usable Gemini key is set
     * ([GeminiKeyProvider.hasKey]) - "wire the advisory sources that already exist" is this
     * ticket's binding read literally; this is the one that makes a fresh install say so on HOME. */
    val hasGeminiKey: Boolean = true,
    /** ALERTS goal-exception rows (ticket 16): every ACTIVE [Goal] whose own stated
     * [Goal.deadlineEpoch] has already passed - filtered the identical way
     * [com.kevin.legion.advisor.digest.HomeDigestBuilder.exceptionsLine] filters for the advisor
     * digest ("`deadlineEpoch != null && deadlineEpoch < now`"), read independently here rather
     * than calling that digest builder (same "never call another builder's full logic" posture
     * [HomeDigestBuilder]'s own class doc states for itself). */
    val overdueGoals: List<Goal> = emptyList(),
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
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(TodayUiState()) }
    // Bumped after a permission grant so the effect below re-runs and picks up Google events on
    // the SAME load path a fresh screen open uses - same shape as InboxScreen's own reloadNonce.
    var reloadNonce by remember { mutableStateOf(0) }
    // Requests READ_CALENDAR and WRITE_CALENDAR together (ticket 14) rather than deferring the
    // write permission to Alfred's first voice-created event - that moment runs off
    // AriaForegroundService, which has no Activity to raise a permission dialog from. Asking for
    // both here, at the one screen that already asks for READ_CALENDAR, is "in context, not at
    // startup" applied to the write half too: a later voice write has a real chance of already
    // being granted instead of deterministically failing the first time, every time. See
    // AndroidManifest.xml's permission-block comment.
    val requestCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        reloadNonce++ // re-check regardless of the callback's own granted flags - a single source of truth
    }

    LaunchedEffect(reloadNonce) {
        val now = System.currentTimeMillis()
        val db = CarDatabase.getDatabase(context)

        // INTAKE: today's meal gap, plus a direct target read so the empty
        // state can tell "no target set" from "target set, nothing logged
        // yet" apart - see hasMealTarget's doc comment above.
        val mealTarget = db.mealTargetDao().currentTarget(dayStartEpoch(now))
        val mealGap = MealController.dayGap(context, now)

        // Quant-viz ticket 11: INTAKE hero sparkline - the SAME controller call + bucketing helper
        // BodyScreen's own panel uses (see TodayUiState's doc comment on this field) - no second
        // series definition, just a second consumer.
        val sevenDayStart = deckRangeStartMs(DeckRange.SEVEN_DAY, now)
        val intakeMeals = MealController.mealsInWindow(context, sevenDayStart, now)
        val intakeSparkline = bucketMealKcalDaily(intakeMeals, sevenDayStart, now).map { it?.toFloat() }

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

        // CRED tile sparkline (quant-viz ticket 11 item 2): month-to-date cumulative daily spend,
        // truncated at today - reuses budget's OWN BudgetVsActual.coverage rather than a second
        // coverage fetch, same reuse discipline com.kevin.legion.ui.ledger.categoryDailySpendBars
        // follows for the drilldown's identical chart. UTC throughout (never device zone) - txnDate
        // is stamped atStartOfDay(UTC) by every parser, exactly the convention that file's own doc
        // comment documents and MEMORY.md already records one prior "dates a day early" bug for
        // getting wrong.
        val month = YearMonth.now()
        val monthStartMs = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ledgerTxns = LedgerController.monthOperatingExpenses(context, LedgerEntity.US, month)
        val ledgerSamples = ledgerTxns.map { it.txnDate to abs(it.amountCents) }
        val ledgerCoveredRanges = budget.coverage.mapNotNull { c ->
            val from = c.coveredFromMs
            val to = c.coveredToMs
            if (from != null && to != null) from..to else null
        }
        // endMs = now, never the rest of the month - bucketDailySumCents/dailyBuckets stop at the
        // UTC calendar day containing `now`, which is exactly ticket 11 item 2's "days after today
        // are not rendered" without a separate truncation step.
        val ledgerDailyCents = bucketDailySumCents(ledgerSamples, monthStartMs, now, ledgerCoveredRanges, zone = ZoneOffset.UTC)
        val ledgerCumulativeSparkline = cumulativeDailySpendCents(ledgerDailyCents).map { it?.toFloat() }

        // FLEET tile: the active vehicle's maintenance schedule, same rows ui.FleetScreen's DUE
        // block builds from the same MaintenanceItem list - see buildDueRows's own doc for the
        // overdue-first ordering.
        val vehicle = VehicleController.currentVehicle(context)
        val currentMileage = VehicleController.currentMileage(vehicle)
        val items: List<MaintenanceItem> = db.maintenanceItemDao().getForVehicle(vehicle.obdMac)

        // LOG tile: open-task count mirrors HomeDigestBuilder.logHeadline's own filter
        // (undone, unscheduled, non-recurring) - see buildLogTile's own doc for why this is
        // restated here rather than calling that digest builder.
        val allActiveItems = db.listItemDao().allActive()
        val openTaskCount = allActiveItems.count { !it.done && it.startsAt == null && it.repeatKind == null }
        val logHasAnyItems = allActiveItems.isNotEmpty()
        val notesMissedCount = NotesController.missedItems(context).size

        // AGENDA: today's window in the DEVICE zone (a reminder is a real instant the driver
        // picked - see `ui/notes/NotesResolvers.kt`'s doc comment on why this is the
        // shortDate/compactDate family, never documentDate's UTC convention). This is the exact
        // pair of NotesController reads the pre-cyberdeck NOTES row already made; the only change
        // is keeping the item text and resolved instant instead of collapsing straight to a count
        // (see AgendaEntry's doc comment) - no new query.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val oneOff = NotesController.timedItemsInWindow(context, dayStart, dayEnd)
            .filter { !it.done }
            .mapNotNull { item -> item.startsAt?.let { AgendaEntry(item.text, it, item.allDay) } }
        val recurringToday = NotesController.allRecurringItems(context).flatMap { item ->
            val startsAt = item.startsAt
            val rule = startsAt?.let { ruleFromItem(item) }
            if (startsAt == null || rule == null) {
                emptyList()
            } else {
                val skips = NotesController.skippedDates(context, item)
                Recurrence.occurrencesInWindow(startsAt, rule, endFromItem(item), skips, dayStart, dayEnd)
                    .map { occMs -> AgendaEntry(item.text, occMs, item.allDay) }
            }
        }

        // AGENDA, Google half (ticket 13): the SAME [dayStart, dayEnd] window as the local reads
        // just above, so the merge in mergeAgenda is genuinely "one window, two sources" rather than
        // two different days pasted together. Empty (not an error) when READ_CALENDAR is refused -
        // buildAgendaCalendarNotice is what turns that into worded text, not this block.
        val calendarPermissionGranted = CalendarProvider.hasReadPermission(context)
        val googleEvents = if (calendarPermissionGranted) {
            CalendarProvider.eventsInWindow(context, dayStart, dayEnd)
        } else {
            emptyList()
        }

        // ALERTS (ticket 16): every currently-quarantined ledger document (CLAUDE.md §4), the
        // Gemini key's presence, and every overdue active goal - see buildAlertRows's own doc
        // comment for exactly why these three and not the other two ticket 04 names.
        val quarantinedFiles = LedgerController.quarantinedFiles(context)
        val hasGeminiKey = GeminiKeyProvider.hasKey()
        val overdueGoals = db.goalDao().allCurrentGoals().filter { it.deadlineEpoch != null && it.deadlineEpoch < now }

        state = TodayUiState(
            loading = false,
            mealGap = mealGap,
            hasMealTarget = mealTarget != null,
            paceFraction = dayElapsedFraction(now),
            intakeSparkline = intakeSparkline,
            bioTile = bioTile,
            budget = budget,
            ledgerCumulativeSparkline = ledgerCumulativeSparkline,
            maintenanceRows = buildDueRows(items, currentMileage, vehicle.odometerBaseline == 0, now),
            maintenanceUnknownCount = items.count { VehicleController.isUnknown(it) },
            openTaskCount = openTaskCount,
            logHasAnyItems = logHasAnyItems,
            agendaEntries = mergeAgenda(oneOff + recurringToday, googleEvents),
            notesMissedCount = notesMissedCount,
            calendarPermissionGranted = calendarPermissionGranted,
            quarantinedFiles = quarantinedFiles,
            hasGeminiKey = hasGeminiKey,
            overdueGoals = overdueGoals,
        )
    }

    TodayContent(
        state, onOpenNotes, onOpenCategory, onOpenBody, onOpenFleet, onOpenKeySettings,
        onRequestCalendarPermission = {
            requestCalendar.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        },
    )
}

/** Plain UI: [state] plus callbacks, no controller/DB reference - see the file doc comment. */
@Composable
fun TodayContent(
    state: TodayUiState,
    onOpenNotes: () -> Unit = {},
    onOpenCategory: (String?) -> Unit = {},
    onOpenBody: () -> Unit = {},
    onOpenFleet: () -> Unit = {},
    onOpenKeySettings: () -> Unit = {},
    onRequestCalendarPermission: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.loading) {
            Text("LOADING...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
        } else {
            TodayListing(state, onOpenNotes, onOpenCategory, onOpenBody, onOpenFleet, onOpenKeySettings, onRequestCalendarPermission)
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
        // ------------------------------------------------------------ INTAKE (FULL, hero)
        item(key = "intake-pane") {
            IntakePane(
                state.mealGap, state.hasMealTarget, state.paceFraction, state.intakeSparkline,
                modifier = Modifier.clickable(onClick = onOpenBody),
            )
        }

        // ------------------------------------------------------------ BIO / CRED / FLEET / LOG (HALF tiles)
        // SYSTEMS SWEEP is dissolved (ticket 11/16) - this row of four tiles is what replaces it,
        // never a pane wrapping tiles of its own. Fixed order, matching the panel table exactly:
        // BIO, CRED, FLEET, LOG - never reordered by attention (ticket 16's binding).
        item(key = "tile-row-bio-cred") {
            val monthLabel = ledgerSweepMonthLabel(YearMonth.now())
            val credTile = buildCredTile(state.budget, monthLabel)
            val fleetTile = buildFleetTile(state.maintenanceRows, state.maintenanceUnknownCount)
            val logTile = buildLogTile(state.openTaskCount, state.notesMissedCount, state.logHasAnyItems)
            // Equal-height tiles (ticket 05's grammar treats HALF as ONE shape, not two shapes
            // that happen to sit side by side) via EqualHeightRow, NOT `Row(...).height(IntrinsicSize.Min)`
            // - that was the first attempt and it crashed the app on-device (dropbox-caught):
            // [DeckPane] wraps [BoxWithConstraints] for its label-pill sizing, BoxWithConstraints is
            // built on SubcomposeLayout, and Compose hard-refuses intrinsic-measurement queries
            // against a SubcomposeLayout ("Asking for intrinsic measurements of SubcomposeLayout
            // layouts is not supported" - IllegalStateException, every launch, every time). See
            // EqualHeightRow's own doc comment for the two-real-measure-passes workaround.
            EqualHeightRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalGap = 9.dp) {
                HalfTile(
                    header = "Bio",
                    hero = state.bioTile.hero,
                    caption = state.bioTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenBody),
                )
                HalfTile(
                    header = "Cred",
                    hero = credTile.hero,
                    caption = credTile.caption,
                    modifier = Modifier.clickable(onClick = { onOpenCategory(null) }),
                ) {
                    // The LEDGER cumulative sparkline that already ships, moved onto the CRED tile
                    // wholesale (ticket 16) - suppressed when every day in the window is a gap, same
                    // guard IntakePane's own sparkline uses, rather than drawing an empty canvas.
                    if (state.ledgerCumulativeSparkline.any { it != null }) {
                        DeckSparkline(state.ledgerCumulativeSparkline, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    // The uncategorised bucket is NOT in the hero figure above (Kevin, 2026-08-15) -
                    // stated here in the tile's own `extra` slot rather than appended to `caption`,
                    // which is one ellipsised line and would silently swallow half the sentence. See
                    // buildCredTile's doc comment.
                    val budget = state.budget
                    if (budget != null && budget.uncategorized.spentCents > 0L) {
                        Text(
                            "${compactMoneyHero(budget.uncategorized.spentCents, budget.entity.currency)} UNCATEGORISED, NOT COUNTED",
                            style = LegionType.stamp,
                            color = LocalLegionSemantics.current.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            EqualHeightRow(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalGap = 9.dp) {
                HalfTile(
                    header = "Fleet",
                    hero = fleetTile.hero,
                    caption = fleetTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenFleet),
                )
                HalfTile(
                    header = "Log",
                    hero = logTile.hero,
                    caption = logTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenNotes),
                )
            }
        }

        // ------------------------------------------------------------ AGENDA (FULL)
        item(key = "agenda-pane") {
            val calendarNotice = buildAgendaCalendarNotice(state.calendarPermissionGranted, state.agendaEntries.size)
            // No explicit top padding here - DeckPane already reserves 8dp of its own for the
            // label pill (see that composable's own doc comment), which is the entire pane-to-pane
            // gap the pre-tiling screen ever had. Adding a second 8dp on top of it would double the
            // gap against every other pane transition on this screen (caught on-device: measured
            // 16.5dp instead of the intended ~8dp before this fix).
            DeckPane(
                header = "Agenda",
                modifier = Modifier.clickable(onClick = onOpenNotes),
            ) {
                Text(
                    notesSummaryMessage(state.agendaEntries.size, state.notesMissedCount),
                    style = LegionType.stamp,
                    // A missed reminder is a gap in your own log, not a failed
                    // ingest gate - amber (data/advisory), never sem.quarantined.
                    // Ticket 03 answer #1 reserves red exclusively for the ALERTS
                    // pane below.
                    color = if (state.notesMissedCount > 0) MaterialTheme.colorScheme.primary else sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                // Ticket 13 point 7: permission state is worded independently of the row list's own
                // emptiness - see AgendaCalendarNotice's doc comment for why the two can never be
                // collapsed into one check without risking exactly the "nothing on" false read.
                if (calendarNotice.message != null) {
                    CalendarNotLinkedRow(calendarNotice.message, onGrant = onRequestCalendarPermission)
                }
                if (calendarNotice.showNothingScheduled) {
                    DeckRow(label = "Today", value = "NOTHING SCHEDULED")
                }
                state.agendaEntries.forEach { entry ->
                    AgendaRow(entry)
                }
            }
        }

        // ------------------------------------------------------------ ALERTS (FULL, "everything needing you")
        item(key = "alerts-pane") {
            val rows = buildAlertRows(state.quarantinedFiles, state.hasGeminiKey, state.overdueGoals)
            // Same "no extra top padding" reasoning as the AGENDA pane above.
            DeckPane(header = "Alerts") {
                if (rows.isEmpty()) {
                    Text(
                        "0 ALERTS · ALL SYSTEMS NOMINAL",
                        style = LegionType.stamp,
                        color = sem.credit,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                } else {
                    val capped = capAlertRows(rows)
                    capped.visible.forEach { row ->
                        DeckRow(
                            label = row.label,
                            value = row.value,
                            tag = {
                                // ALARM reads through QuarantineTag - the app's only red, ticket 04's
                                // inverted-chrome pill - never DeckTag: ADVISORY is the amber "act on
                                // this" tier ticket 04 answer §3 already ships as INVERTED_AMBER.
                                if (row.tier == AlertTier.ALARM) {
                                    QuarantineTag(row.tagText)
                                } else {
                                    DeckTag(row.tagText, DeckTagStyle.INVERTED_AMBER)
                                }
                            },
                            modifier = Modifier.clickable(onClick = { onAlertTap(row.target) }),
                        )
                    }
                    if (capped.overflowCount > 0) {
                        Text(
                            "AND ${capped.overflowCount} MORE",
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The INTAKE hero: the day's calorie gap blown up to a [DeckPane] of its own, above the tile row,
 * always in the same place. `NOT LOGGED` never `0` - D27's rule, carried into the deck register:
 * [DailyMealGap.NotLogged] has no [com.kevin.legion.meals.MacroTotals] living inside it (see that
 * sealed class's own doc comment), so there is no number this branch could render even by
 * accident.
 */
@Composable
private fun IntakePane(
    mealGap: DailyMealGap,
    hasMealTarget: Boolean,
    paceFraction: Float,
    intakeSparkline: List<Float?> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    DeckPane(header = "Intake", modifier = modifier) {
        when (mealGap) {
            DailyMealGap.NotLogged -> {
                Text(
                    if (hasMealTarget) "NOT LOGGED" else "NO TARGET SET",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    if (hasMealTarget) {
                        "Say \"log a meal\" and describe what you ate."
                    } else {
                        "No calorie target set yet - say \"set my daily calorie target\" to start tracking."
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            is DailyMealGap.Logged -> {
                val macros = mealGap.gap.actual
                val target = mealGap.gap.target
                val fraction = if (target.caloriesKcal > 0) macros.caloriesKcal.toFloat() / target.caloriesKcal else 0f
                Text(
                    "${macros.caloriesKcal}",
                    style = MaterialTheme.typography.displayLarge,
                    // A logged calorie count is a VALUE, not a highlight - ticket 01's "mint is
                    // every value, amber is every highlight" contract, the same class of bug
                    // ticket 15 fixed in the chart kit. The NotLogged branch above stays amber
                    // (an empty-state word, not a reading) per the coordinator's own call.
                    color = sem.data,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    "OF ${target.caloriesKcal} KCAL",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                DeckMeter(
                    fraction = fraction,
                    paceFraction = paceFraction,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // Quant-viz ticket 11: 7-day kcal trend under the meter - the SAME series
                // BodyScreen's INTAKE sparkline reads (see TodayUiState.intakeSparkline's doc
                // comment). Suppressed when every day in the window is a gap, same guard
                // BodyScreen's own panel uses, rather than drawing an empty canvas.
                if (intakeSparkline.any { it != null }) {
                    DeckSparkline(intakeSparkline, modifier = Modifier.padding(horizontal = 12.dp))
                }
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // CLAUDE.md §4 rule 5: a receipt/meal log never states its own
                    // macro breakdown - these are LLM guesses from what the driver
                    // described, so both the words and the tag say "estimate".
                    Text("ESTIMATED MACROS", style = LegionType.stamp, color = sem.faint)
                    DeckTag("EST", DeckTagStyle.OUTLINE_MUTED)
                }
                Text(
                    "${macros.proteinG.toInt()}G PROTEIN · ${macros.carbsG.toInt()}G CARBS · ${macros.fatG.toInt()}G FAT",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * One AGENDA row (ticket 13 point 4): a [AgendaSource.GOOGLE] entry carries a `CAL` tag so its
 * source reads in WORDS on the row itself, never by colour alone - [DeckRow]'s `value` slot is
 * already amber for every row regardless of source (ticket 03's fixed amber-mono value colour), so
 * colour alone could never have carried this distinction anyway. A [AgendaSource.LOCAL] row stays
 * tagless, this pane's existing silent-is-strong posture.
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
            paceFraction = 0.4f,
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
            quarantinedFiles = emptyList(),
            hasGeminiKey = false, // the fresh-install case ticket 16 exists to surface
            overdueGoals = emptyList(),
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
            paceFraction = 0.62f,
            intakeSparkline = listOf(2100f, null, 1980f, 2250f, 1650f, null, null),
            bioTile = BioTileData(hero = "82.4", caption = "KG - DOWN 4WK"),
            ledgerCumulativeSparkline = listOf(4120f, 9840f, null, 18_800f, 24_100f, 24_100f, 41_200f),
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
                AgendaEntry("Pick up dry cleaning", System.currentTimeMillis(), allDay = false),
                AgendaEntry("Kevin's birthday", System.currentTimeMillis(), allDay = true),
            ),
            notesMissedCount = 1,
            quarantinedFiles = listOf(
                IngestedFile(
                    driveFileId = "preview-1",
                    treeUri = null,
                    displayName = "eStmt_2025-11-05.pdf",
                    sizeBytes = 40_000L,
                    lastModified = System.currentTimeMillis(),
                    contentSha256 = null,
                    state = com.kevin.legion.data.local.IngestState.QUARANTINED,
                    quarantineReason = "Lines summed to 4,182.19 but the statement says 4,180.00.",
                    firstSeenAt = System.currentTimeMillis(),
                    lastAttemptAt = System.currentTimeMillis(),
                ),
            ),
            hasGeminiKey = true,
            overdueGoals = emptyList(),
        ),
    )
}
