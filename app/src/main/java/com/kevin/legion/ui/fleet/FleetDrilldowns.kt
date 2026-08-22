package com.kevin.legion.ui.fleet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.DailyDriveLog
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.MonthlyRecap
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.YearlyWrapped
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckDialog
import com.kevin.legion.ui.common.DeckLineChart
import com.kevin.legion.ui.common.DeckMeter
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.Temp
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.DiagnosticAgent
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.MpgTrust
import com.kevin.legion.vehicle.VehicleController.WriteOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * FLEET's in-screen drilldowns (ticket 18, merging FLEET + TELEMETRY per ticket 09's resolution;
 * widened by ticket 09's maintenance rebuild into a three-deep chain - MAINTENANCE -> FULL SCHEDULE
 * -> ITEM DETAIL - alongside RECAPS/DRIVES/OIL). Same shape as
 * [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]: internal Compose state in
 * [com.kevin.legion.ui.FleetScreen] selects one of these (or the UPLINK drilldown, which reuses
 * [com.kevin.legion.ui.TelemetryScreen] wholesale rather than a third file here - see that screen's
 * own doc comment), never a nav-graph route with an argument (`LegionRoute` deliberately carries
 * none). Every screen here is display-only: state/rows in, a back callback out, no controller or DAO
 * reference, matching every other drilldown/content split in this codebase. [ItemDetailScreen] and
 * [FullScheduleScreen]'s CONFIRM ALL are the two places on this map that actually WRITE - both do it
 * by calling a passed-in suspend lambda the state holder implements
 * (`ui/fleet/MaintenanceWrites.kt`), never by touching `VehicleController`/`CarDatabase` directly
 * from this file.
 */

/**
 * MAINTENANCE's drilldown - **triage, ticket 09's rebuild.** Overdue-first (every row
 * [com.kevin.legion.ui.fleet.buildDueRows] already built for the panel itself - ticket 18's "reuse
 * the exact rows/ordering FleetScreen already builds", read twice off the same list rather than a
 * second query), then upcoming. What it must NOT do, per ticket 09's own charting, is what it did
 * before this rebuild: silently drop every item with no anchor at all. They are not due - they
 * genuinely are not - so they are **counted, not listed**, via [UnknownCountRow]: one tappable line
 * straight into [FullScheduleScreen] filtered to them, which is where they stop being invisible
 * without pretending to be urgent, and where the natural backfill prompt lives.
 *
 * **The two old dead ghost lines are gone.** `"N service records on file, no screen yet"` and the
 * build-sheet line both pointed nowhere; ticket 09's build brief is explicit that both are removed
 * on this rebuild rather than carried forward as dead pointers on a rebuilt surface. RECAPS and OIL
 * ANALYSES are unchanged.
 */
@Composable
fun MaintenanceDrilldownScreen(
    dueRows: List<DueRowView>,
    /** Items with no anchor at all ([VehicleController.unknownItems]) - counted here, listed on [FullScheduleScreen]. */
    unknownCount: Int,
    recapCount: Int,
    /**
     * Every [MonthlyRecap] on file, newest-first - the SAME list [recapCount]
     * is `.size` of (quant-viz ticket 12: "no new DB queries"), reused here to
     * draw the RECAPS row's inline miles-driven sparkline via
     * [buildRecapMonthSlots]/[recapMonthPoints]. `FleetScreen`'s own
     * [FleetUiState.monthlyRecaps] doc explains why this is not a second read.
     */
    monthlyRecaps: List<MonthlyRecap>,
    /** OIL row's headline (quant-viz ticket 06): `OilAnalysisDao.getAll(vehicleId).size`. */
    oilAnalysisCount: Int,
    onOpenUnknown: () -> Unit,
    /**
     * Straight into [FullScheduleScreen], unfiltered - not asked for in words by ticket 09's own
     * text, added here so ADD ITEM and CONFIRM ALL stay reachable even when [unknownCount] is zero
     * (every item already anchored). [UnknownCountRow] is the FILTERED entry point; this is the
     * general one. Flagged in the build report as a reasoned addition beyond the ticket's literal
     * text, not something the ticket resolution stated outright.
     */
    onOpenFullSchedule: () -> Unit,
    onOpenRecaps: () -> Unit,
    onOpenOilAnalysis: () -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "MAINTENANCE",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                DeckButton(text = "FULL SCHEDULE", onClick = onOpenFullSchedule)
            }
            Hairline()
            // A flat LazyColumn even when dueRows is empty (not a top-level
            // if/else the way this screen used to branch) - the RECAPS/OIL
            // rows below the schedule are reachable regardless of whether
            // there is a due schedule to show, and gating the whole list on
            // dueRows would have hidden them on an otherwise-empty car.
            LazyColumn(Modifier.fillMaxSize()) {
                if (dueRows.isEmpty() && unknownCount == 0) {
                    item(key = "no-schedule") {
                        Text(
                            "No maintenance schedule yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(dueRows, key = { "due-${it.label}" }) { row ->
                        // sub on its own line, never concatenated into value: DeckRow's value is
                        // unweighted TextOverflow.Visible on purpose ("a value getting clipped is
                        // a worse failure than a label running long"), so a long composed string
                        // starves the weighted label to 0dp AND pushes its own tail past the
                        // screen clip - both texts vanish. Observed on-device 2026-08-13 (QA,
                        // quant-viz pass) on a real 3,000mi-interval row.
                        Column {
                            DeckRow(
                                label = row.label,
                                value = row.value,
                                // OVERDUE and GUESS (ticket 06) can both apply to the same row - a
                                // seeded interval does not stop being a guess just because it is
                                // also overdue - so both tags render together rather than one
                                // silently winning. [GUESS] is INVERTED_AMBER, the same weight as
                                // OVERDUE, per ticket 06's "same ladder as the ledger's
                                // UNRECONCILED treatment" (DeckTagStyle's own doc: INVERTED_AMBER
                                // is "an advisory on data").
                                tag = if (row.overdue || row.isGuess) {
                                    {
                                        if (row.overdue) DeckTag("OVERDUE", DeckTagStyle.INVERTED_AMBER)
                                        if (row.overdue && row.isGuess) Spacer(Modifier.width(4.dp))
                                        if (row.isGuess) DeckTag("GUESS", DeckTagStyle.INVERTED_AMBER)
                                    }
                                } else {
                                    null
                                },
                            )
                            Text(
                                row.sub,
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            // Restored (quant-viz ticket 05 part C shipped this, commit 7c6a5ca;
                            // mission-control's ticket 09/16 rebuild moved the due list from
                            // FleetScreen's MaintenancePane into this LazyColumn and the meter did
                            // not travel with it - see .scratch/quant-viz/issues/17. null only when
                            // the item has no interval on file to divide by (DueRowView.fraction's
                            // own doc), same guard the original call used.
                            if (row.fraction != null) {
                                DeckMeter(row.fraction, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                            }
                        }
                    }
                    if (unknownCount > 0) {
                        item(key = "unknown-row") { UnknownCountRow(unknownCount, onOpenUnknown) }
                    }
                }
                item(key = "recaps-row") {
                    // RECAPS pane strip (quant-viz ticket 12): the row stays
                    // "where the recap count renders" per the ticket's own
                    // wording, with a glanceable miles-driven sparkline added
                    // beneath it once there are enough months to trend - below
                    // two recaps this is exactly the same count-only row as
                    // before, matching RecapDrilldownScreen's own "trend
                    // appears after two monthly recaps" posture stated in the
                    // same words here rather than a silently empty chart.
                    Column(Modifier.clickable(onClick = onOpenRecaps)) {
                        DeckRow(label = "Recaps", value = recapCount.toString())
                        if (monthlyRecaps.size >= 2) {
                            val slots = buildRecapMonthSlots(monthlyRecaps)
                            DeckSparkline(
                                recapMonthPoints(slots) { it.milesDriven }.map { it?.y },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            Text(
                                "miles",
                                style = LegionType.stamp,
                                color = sem.ghost,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
                item(key = "oil-row") {
                    DeckRow(
                        label = "Oil Analyses",
                        value = oilAnalysisCount.toString(),
                        modifier = Modifier.clickable(onClick = onOpenOilAnalysis),
                    )
                }
            }
        }
    }
}

/**
 * The one line ticket 09 asks for: `"7 items with no history - see full schedule"`, tappable,
 * straight into [FullScheduleScreen] filtered to the unknown-anchor group. Not a [DeckRow] - this is
 * a single sentence, not a label/value pair - but it still carries a real 48dp touch target
 * (mission-control's "anything custom carries... a 48dp target") via [heightIn], and reads in the
 * app's actionable link colour ([MaterialTheme.colorScheme.primary]) rather than [LegionSemantics.ghost]
 * (the old dead-line colour) - this line goes somewhere, the two lines it replaces did not.
 */
@Composable
private fun UnknownCountRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count item${if (count == 1) "" else "s"} with no history - see full schedule",
            style = LegionType.stamp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * FULL SCHEDULE - ticket 09's second surface: the complete, editable inventory. Every non-deleted
 * item ([buildScheduleRows]), grouped OVERDUE / UPCOMING / NO HISTORY, each row carrying a `[GUESS]`
 * tag when [ScheduleRowView.isGuess] (ticket 06). ADD ITEM and CONFIRM ALL both live here (ticket
 * 09's own binding - "Add, and the confirm-all flow, live here"), not on the triage screen above.
 *
 * [filterUnknownOnly] is how MAINTENANCE's [UnknownCountRow] arrives here - "straight into the full
 * schedule filtered to them" (ticket 09) - showing only the NO HISTORY group and its own count,
 * rather than a second screen shape. Reachable unfiltered too, from `FleetScreen`'s own MAINTENANCE
 * panel or (once wired) a FULL SCHEDULE entry point of its own.
 *
 * **Display-only, matching this file's own convention**: [items]/[currentMileage]/[now] in, and
 * every write crosses out through [onOpenItem] (which hands off to [ItemDetailScreen], the ONLY
 * place a write actually happens for a single item) or [onConfirmAll] (a plain suspend lambda the
 * state holder in `ui/FleetScreen.kt` implements - see `ui/fleet/MaintenanceWrites.kt`'s file doc
 * for why the write dispatch itself lives outside this file entirely).
 */
@Composable
fun FullScheduleScreen(
    items: List<MaintenanceItem>,
    currentMileage: Int,
    /**
     * `vehicle.odometerBaseline == 0` - see [FleetUiState.odometerUnset]'s doc for why this is
     * carried separately from [currentMileage] rather than re-derived from it (`currentMileage > 0`
     * is not the same signal - senior-dev review fix, mission-control ticket 09 follow-up).
     */
    odometerUnset: Boolean,
    now: Long,
    filterUnknownOnly: Boolean,
    onOpenItem: (String) -> Unit,
    onAddItem: () -> Unit,
    onConfirmAll: suspend (List<MaintenanceItem>) -> List<WriteOutcome>,
    /** Ticket 11 §3's entry point: "reached from the full-schedule screen" - the SERVICE HISTORY
     * button below, sitting beside ADD ITEM rather than a third row, since both are this screen's
     * only two navigational (non-item) actions. */
    onOpenServiceHistory: () -> Unit,
    /**
     * Ticket 14's second entry point ("the full schedule screen, where an empty schedule is
     * visible"). Only offered when [items] is empty - a schedule that already has rows is edited
     * item by item or re-populated from SPECS, not from a button that would otherwise sit here on
     * every visit. Defaults to a no-op so every pre-existing caller/preview keeps compiling.
     */
    onOpenPopulate: () -> Unit = {},
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()

    // A local mirror, same fix [ItemDetailScreen] already applies below and for the same reason:
    // CONFIRM ALL's write lands via `onConfirmAll`, but `items` is a prop that only refreshes after
    // this screen is actually LEFT (`FleetScreen`'s reloadKey only bumps on the way out) - without
    // this mirror, `rows`/`confirmable` stay derived off the pre-confirm `items` for the rest of this
    // visit, so a just-confirmed row would keep showing `[GUESS]` and CONFIRM ALL's own count would
    // keep counting it. Senior-dev review fix, mission-control ticket 09 follow-up: "this is the
    // surface built specifically so a tag cannot lie."
    var currentItems by remember(items) { mutableStateOf(items) }

    val rows = remember(currentItems, currentMileage, odometerUnset, now) { buildScheduleRows(currentItems, currentMileage, odometerUnset, now) }
    val confirmable = remember(currentItems) { confirmableItems(currentItems) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmStatus by remember { mutableStateOf<String?>(null) }

    val overdueRows = rows.filter { it.group == ScheduleGroup.OVERDUE }
    val upcomingRows = rows.filter { it.group == ScheduleGroup.UPCOMING }
    val unknownRows = rows.filter { it.group == ScheduleGroup.UNKNOWN }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "FULL SCHEDULE",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (filterUnknownOnly) {
                Text(
                    "Filtered to items with no history.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            // TWO ROWS, not one. Three DeckButtons packed into a single Row have no `weight()` and
            // no horizontal scroll, so when all three are present the third gets squeezed to its
            // minimum intrinsic width and Compose wraps its label one character per line - CONFIRM
            // ALL (1) rendered as a vertical stack of single letters on the device.
            //
            // Observed on the real phone 2026-08-15, at 384dp, in the three-button state. The
            // ticket-11 review flagged exactly this as an unverified overflow risk and named the
            // three-button case specifically; it was right, and only installing showed it. Unit
            // tests cannot see a layout, and Compose previews have never rendered on this project.
            //
            // The primary two keep the first row. CONFIRM ALL takes its own full-width row when it
            // is present, which also suits it: it is the only one of the three that carries a count
            // and opens a review dialog.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                DeckButton(text = "ADD ITEM", onClick = onAddItem)
                Spacer(Modifier.width(8.dp))
                DeckButton(text = "SERVICE HISTORY", onClick = onOpenServiceHistory)
            }
            if (!filterUnknownOnly && confirmable.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp)) {
                    DeckButton(text = "CONFIRM ALL (${confirmable.size})", onClick = { showConfirmDialog = true })
                }
            }
            if (confirmStatus != null) {
                Text(confirmStatus!!, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            Hairline()
            LazyColumn(Modifier.fillMaxSize()) {
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.padding(12.dp)) {
                            // Ticket 14's own wording: a new car's empty schedule says so and
                            // offers the fix right there, rather than a bare "no schedule yet."
                            Text(
                                "No schedule yet - populate it from the factory recommendation?",
                                style = MaterialTheme.typography.bodySmall,
                                color = sem.faint,
                            )
                            Spacer(Modifier.height(8.dp))
                            DeckButton(text = "POPULATE SCHEDULE", onClick = onOpenPopulate)
                        }
                    }
                }
                if (!filterUnknownOnly && overdueRows.isNotEmpty()) {
                    item(key = "overdue-header") { SectionHeader("OVERDUE", overdueRows.size.toString()) }
                    items(overdueRows, key = { "overdue-${it.serviceName}" }) { ScheduleRow(it, onOpenItem) }
                }
                if (!filterUnknownOnly && upcomingRows.isNotEmpty()) {
                    item(key = "upcoming-header") { SectionHeader("UPCOMING", upcomingRows.size.toString()) }
                    items(upcomingRows, key = { "upcoming-${it.serviceName}" }) { ScheduleRow(it, onOpenItem) }
                }
                if (unknownRows.isNotEmpty()) {
                    item(key = "unknown-header") { SectionHeader("NO HISTORY", unknownRows.size.toString()) }
                    items(unknownRows, key = { "unknown-${it.serviceName}" }) { ScheduleRow(it, onOpenItem) }
                }
                item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // CONFIRM ALL (ticket 06 decision 2): lists every value about to be blessed BEFORE blessing it -
    // a plain accept-all was declined specifically because 3,000 looked exactly as authoritative as
    // a number Kevin typed, and this dialog is what keeps that from becoming a rubber stamp.
    if (showConfirmDialog) {
        DeckDialog(title = "Confirm All", onDismissRequest = { showConfirmDialog = false }) {
            // The heading no longer claims LEGION guessed all of these (ticket 18): once isGuessTag
            // widened past SEEDED, this list can also hold LOOKUP rows, which came from a factory
            // lookup the driver reviewed. Saying "guessed by LEGION" over those would be false, and
            // the one thing this dialog exists to prevent is a value looking more or less
            // authoritative than it is. What they genuinely share is that the driver has not
            // confirmed them, so that is what the heading says.
            Text(
                "These ${confirmable.size} intervals have not been confirmed by you. Confirming accepts them exactly as shown:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            confirmable.forEach { mi ->
                // Per-row provenance, because the two kinds are NOT equally weak and a bulk confirm
                // is exactly where that difference would otherwise vanish. A LOOKUP row came from a
                // lookup shown to be unstable across runs (ticket 18); a SEEDED one was never
                // reviewed at all.
                val origin = provenanceWords(mi)?.let { " ($it)" } ?: ""
                Text(
                    "${mi.serviceName} - ${intervalWords(mi) ?: "no interval"}$origin",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Row(Modifier.padding(top = 12.dp)) {
                DeckButton(text = "CANCEL", onClick = { showConfirmDialog = false })
                Spacer(Modifier.width(8.dp))
                DeckButton(
                    text = "CONFIRM",
                    onClick = {
                        scope.launch {
                            val outcomes = onConfirmAll(confirmable)
                            // outcomes is index-aligned with confirmable - writeConfirmAll's own doc
                            // states it's a plain `items.map { ... }` over the SAME list passed in, so
                            // zipping here is safe. Only the successfully-confirmed rows are patched -
                            // one item disappearing mid-loop (writeConfirmAll's own doc: "a concurrent
                            // delete") fails only that row's WriteOutcome and leaves it as-is here too,
                            // matching CLAUDE.md's "nothing partial reads as more than it is."
                            val confirmedNames = confirmable.zip(outcomes)
                                .filter { (_, outcome) -> outcome.success }
                                .map { (item, _) -> item.serviceName }
                                .toSet()
                            currentItems = currentItems.map {
                                if (it.serviceName in confirmedNames) it.copy(intervalSource = "CONFIRMED") else it
                            }
                            val failed = outcomes.count { !it.success }
                            confirmStatus = if (failed == 0) {
                                "Confirmed ${outcomes.size} item${if (outcomes.size == 1) "" else "s"}."
                            } else {
                                "$failed of ${outcomes.size} did not confirm - " + outcomes.first { !it.success }.message
                            }
                            showConfirmDialog = false
                        }
                    },
                )
            }
        }
    }
}

/**
 * One row of [FullScheduleScreen] - same tag-combining rule [MaintenanceDrilldownScreen]'s due rows
 * use, and the same restored meter (quant-viz ticket 05 part C / .scratch/quant-viz/issues/17):
 * [ScheduleRowView.fraction] is `null` for the UNKNOWN group (nothing to meter, per
 * [ScheduleRowView]'s own `toScheduleRow` doc) and non-null for OVERDUE/UPCOMING, same guard as
 * [MaintenanceDrilldownScreen]'s due list.
 */
@Composable
private fun ScheduleRow(row: ScheduleRowView, onOpenItem: (String) -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.clickable { onOpenItem(row.serviceName) }) {
        DeckRow(
            label = row.serviceName,
            value = row.value,
            tag = if (row.group == ScheduleGroup.OVERDUE || row.isGuess) {
                {
                    if (row.group == ScheduleGroup.OVERDUE) DeckTag("OVERDUE", DeckTagStyle.INVERTED_AMBER)
                    if (row.group == ScheduleGroup.OVERDUE && row.isGuess) Spacer(Modifier.width(4.dp))
                    if (row.isGuess) DeckTag("GUESS", DeckTagStyle.INVERTED_AMBER)
                }
            } else {
                null
            },
        )
        Text(row.sub, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp))
        if (row.fraction != null) {
            DeckMeter(row.fraction, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }
}

/**
 * ITEM DETAIL - ticket 09's third surface, where every action lives: the interval (editable, both
 * axes), the anchor (ticket 07's three-way picker), the `[GUESS]` tag and its confirm affordance
 * (ticket 06), every [ServiceRecord] matching this item (read-only - ticket 11 owns the full history
 * screen and cost), and DELETE. [item] is `null` for ADD ITEM - the SAME form, per ticket 09's own
 * binding ("ADD ITEM uses the same detail form").
 *
 * **Every write below goes through one of the four passed-in suspend lambdas and surfaces
 * `success == false` in [statusText]'s words** - CLAUDE.md's "law" from ticket 05, restated for the
 * screen layer: a write that silently swallows a failed outcome re-creates the exact defect this
 * whole map exists to close. None of the four lambdas is called anywhere but here or
 * [FullScheduleScreen]'s CONFIRM ALL dialog - see `ui/fleet/MaintenanceWrites.kt` for what each one
 * actually does.
 *
 * [checkDuplicate] is [item]-add-only: a pure `(typed name) -> colliding existing name, or null`
 * comparison the state holder closes over [VehicleController.looksLikeExistingItem] and the current
 * roster to build, so this file never references the controller directly (matching every other
 * drilldown/content split in the app - see this file's own top doc comment). Ticket 07 decision 2:
 * storage is verbatim, detection is comparator-only - a collision warns, it never rewrites what
 * Kevin typed.
 */
@Composable
fun ItemDetailScreen(
    item: MaintenanceItem?,
    serviceHistory: List<ServiceRecord>,
    checkDuplicate: (String) -> String?,
    onSetInterval: suspend (serviceName: String, miles: Int?, months: Int?) -> WriteOutcome,
    /**
     * Ticket 07 (command-center) addendum: [costCents] is the optional cost step - see
     * `ui/fleet/MaintenanceWrites.kt`'s own updated doc on [writeSetAnchor] for the full reasoning
     * (why this was anchor-only before, why that omission was never actually a documented decision,
     * and why it writes a direct [com.kevin.legion.data.local.ServiceRecordDao.insert] rather than
     * routing through [com.kevin.legion.vehicle.VehicleController.logServiceDirect]). `null` means
     * "no cost logged" - skipping it stays legal on every anchor mode, DONE_AT included.
     */
    onSetAnchor: suspend (serviceName: String, mode: AnchorMode, mileage: Int?, date: Long?, costCents: Long?) -> WriteOutcome,
    onDelete: suspend (serviceName: String) -> WriteOutcome,
    onAddItem: suspend (name: String, miles: Int?, months: Int?, mode: AnchorMode, mileage: Int?, date: Long?) -> WriteOutcome,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()
    val isAdd = item == null

    // A local mirror, updated after every successful write so the screen reflects the new state
    // immediately (the [GUESS] tag clearing on confirm, a new anchor showing) without waiting for
    // `FleetScreen`'s own reload, which only fires once this screen is actually left - see
    // `ui/FleetScreen.kt`'s own drilldown-return-refreshes-the-parent handling.
    var current by remember(item) { mutableStateOf(item) }

    var nameText by remember(item) { mutableStateOf(item?.serviceName.orEmpty()) }
    var duplicateAcknowledged by remember { mutableStateOf(false) }

    var milesText by remember(item) { mutableStateOf(item?.intervalMiles?.toString().orEmpty()) }
    var monthsText by remember(item) { mutableStateOf(item?.intervalMonths?.toString().orEmpty()) }

    var anchorMode by remember(item) {
        mutableStateOf(
            when {
                item == null -> AnchorMode.DONT_KNOW
                item.neverDone -> AnchorMode.NEVER_DONE
                item.lastDoneMileage != null || item.lastDoneDate != null -> AnchorMode.DONE_AT
                else -> AnchorMode.DONT_KNOW
            },
        )
    }
    var anchorMileageText by remember(item) { mutableStateOf(item?.lastDoneMileage?.toString().orEmpty()) }
    var anchorDateText by remember(item) {
        mutableStateOf(item?.lastDoneDate?.let { LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()).toString() }.orEmpty())
    }
    // Ticket 07 (command-center) addendum: the optional cost step, DONE_AT only - always starts
    // blank (never pre-filled from a PAST service_records row, which this screen does not search
    // for; a blank field here means "not logging a cost with THIS save", not "no cost exists").
    var anchorCostText by remember(item) { mutableStateOf("") }

    var statusText by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val collision = if (isAdd) checkDuplicate(nameText) else null

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                if (isAdd) "ADD ITEM" else current?.serviceName.orEmpty().uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                if (statusText != null) {
                    Text(statusText!!, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(vertical = 6.dp))
                }

                if (isAdd) {
                    DeckTextField(value = nameText, onValueChange = { nameText = it; duplicateAcknowledged = false }, label = "Name")
                    if (collision != null) {
                        Text(
                            "This looks like $collision - already on the schedule.",
                            style = LegionType.stamp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                } else if (current?.let { isGuessTag(it) } == true) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        DeckTag("GUESS", DeckTagStyle.INVERTED_AMBER)
                        Spacer(Modifier.width(8.dp))
                        // The TAG stays coarse ("unconfirmed"), the SENTENCE is precise - same split
                        // as CONFIRM ALL's dialog, and for the same reason. Hardcoding "LEGION
                        // guessed this" here was false for a LOOKUP row the moment ticket 18 widened
                        // isGuessTag past SEEDED: a factory-lookup value the driver reviewed and
                        // accepted was never LEGION's guess. Ticket 18 fixed that laundering in one
                        // direction (a lookup value reading as driver-stated); this is the same lie
                        // told the other way round, and it was still here.
                        // `current` is a delegated property, so it cannot smart-cast - read it
                        // through ?.let the same way this branch's own isGuessTag guard above does.
                        val origin = current?.let { provenanceWords(it) }
                        Text(
                            when (origin) {
                                "from a factory lookup" -> "This came from a factory lookup - you haven't confirmed it."
                                else -> "LEGION guessed this interval - you haven't confirmed it."
                            },
                            style = LegionType.stamp,
                            color = sem.faint,
                        )
                    }
                    DeckButton(
                        text = "CONFIRM AS-IS",
                        onClick = {
                            val target = current ?: return@DeckButton
                            scope.launch {
                                val outcome = onSetInterval(target.serviceName, target.intervalMiles, target.intervalMonths)
                                statusText = outcome.message
                                if (outcome.success) current = target.copy(intervalSource = "CONFIRMED")
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                SectionHeader("INTERVAL")
                DeckTextField(
                    value = milesText,
                    onValueChange = { milesText = it },
                    label = "Miles",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                DeckTextField(
                    value = monthsText,
                    onValueChange = { monthsText = it },
                    label = "Months",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                if (!isAdd) {
                    DeckButton(
                        text = "SAVE INTERVAL",
                        onClick = {
                            val target = current ?: return@DeckButton
                            val miles = milesText.trim().toIntOrNull()
                            val months = monthsText.trim().toIntOrNull()
                            scope.launch {
                                val outcome = onSetInterval(target.serviceName, miles, months)
                                statusText = outcome.message
                                if (outcome.success) current = target.copy(intervalMiles = miles, intervalMonths = months, intervalSource = "CONFIRMED")
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                SectionHeader("ANCHOR")
                Column(Modifier.selectableGroup()) {
                    DeckRadio(selected = anchorMode == AnchorMode.NEVER_DONE, onClick = { anchorMode = AnchorMode.NEVER_DONE }, label = "Never done on this car")
                    DeckRadio(selected = anchorMode == AnchorMode.DONT_KNOW, onClick = { anchorMode = AnchorMode.DONT_KNOW }, label = "Don't know")
                    DeckRadio(selected = anchorMode == AnchorMode.DONE_AT, onClick = { anchorMode = AnchorMode.DONE_AT }, label = "Done at...")
                }
                if (anchorMode == AnchorMode.DONE_AT) {
                    DeckTextField(
                        value = anchorMileageText,
                        onValueChange = { anchorMileageText = it },
                        label = "Mileage",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(8.dp))
                    DeckTextField(value = anchorDateText, onValueChange = { anchorDateText = it }, label = "Date (YYYY-MM-DD)")
                    Spacer(Modifier.height(8.dp))
                    // Ticket 07 (command-center) addendum: the optional cost step - "skipping cost
                    // stays legal" (ticket's own words), so this field is never required to SAVE
                    // ANCHOR; a blank field below writes no service_records row at all, same as
                    // today. See MaintenanceWrites.kt's writeSetAnchor doc for the full reasoning.
                    DeckTextField(
                        value = anchorCostText,
                        onValueChange = { anchorCostText = it },
                        label = "Cost (dollars) - optional",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (!isAdd) {
                    DeckButton(
                        text = "SAVE ANCHOR",
                        onClick = {
                            val target = current ?: return@DeckButton
                            val mileage = anchorMileageText.trim().toIntOrNull()
                            val date = parseAnchorDate(anchorDateText)
                            // Cents first, precise, then handed straight through - CLAUDE.md §4
                            // rule 3. A non-blank cost that fails to parse REFUSES the save with a
                            // stated reason rather than silently saving the anchor with no cost -
                            // same "a typo must never look like a deliberate skip" posture
                            // EditServiceRecordDialog's own cost field already applies.
                            val trimmedCost = anchorCostText.trim()
                            val costCents: Long? = if (trimmedCost.isEmpty()) {
                                null
                            } else {
                                val dollars = trimmedCost.toDoubleOrNull()
                                if (dollars == null || dollars < 0.0) {
                                    statusText = "Cost needs to be a number, e.g. 45.99 - leave it blank to skip it."
                                    return@DeckButton
                                }
                                Math.round(dollars * 100.0)
                            }
                            scope.launch {
                                val outcome = onSetAnchor(target.serviceName, anchorMode, mileage, date, costCents)
                                statusText = outcome.message
                                if (outcome.success) {
                                    if (anchorMode == AnchorMode.DONE_AT && costCents != null) anchorCostText = ""
                                    current = target.copy(
                                        neverDone = anchorMode == AnchorMode.NEVER_DONE,
                                        lastDoneMileage = if (anchorMode == AnchorMode.DONE_AT) mileage else null,
                                        lastDoneDate = if (anchorMode == AnchorMode.DONE_AT) date else null,
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))

                    SectionHeader("SERVICE HISTORY", serviceHistory.size.toString())
                    if (serviceHistory.isEmpty()) {
                        Text(
                            "No service records logged for this item yet.",
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    } else {
                        // The SAME row [ServiceHistoryScreen] uses (ticket 11 §3: "one list
                        // implementation, two entry points") - read-only here (no onClick), per
                        // this screen's own doc comment: edit/delete live on the full history
                        // screen only. showServiceName = false - this screen's own header already
                        // names the service, so repeating it on every row would be noise.
                        serviceHistory.forEach { record -> ServiceHistoryRow(record, showServiceName = false) }
                    }
                    Spacer(Modifier.height(16.dp))

                    DeckButton(
                        text = if (confirmingDelete) "TAP AGAIN TO DELETE" else "DELETE",
                        destructive = true,
                        confirming = confirmingDelete,
                        onClick = {
                            if (!confirmingDelete) {
                                confirmingDelete = true
                            } else {
                                val target = current ?: return@DeckButton
                                scope.launch {
                                    val outcome = onDelete(target.serviceName)
                                    statusText = outcome.message
                                    if (outcome.success) onBack()
                                }
                            }
                        },
                    )
                } else {
                    DeckButton(
                        text = if (collision != null && !duplicateAcknowledged) "ADD ANYWAY" else "ADD ITEM",
                        onClick = {
                            if (collision != null && !duplicateAcknowledged) {
                                duplicateAcknowledged = true
                                return@DeckButton
                            }
                            val miles = milesText.trim().toIntOrNull()
                            val months = monthsText.trim().toIntOrNull()
                            val mileage = anchorMileageText.trim().toIntOrNull()
                            val date = parseAnchorDate(anchorDateText)
                            scope.launch {
                                val outcome = onAddItem(nameText, miles, months, anchorMode, mileage, date)
                                statusText = outcome.message
                                if (outcome.success) onBack()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** `"2026-08-15"` -> local-midnight epoch millis, matching [MaintenanceItem.lastDoneDate]'s own local-midnight convention (`playbook-coding.md`'s "Date handling and zone conversions"). `null` on anything that does not parse - never a thrown exception reaching the UI. */
private fun parseAnchorDate(text: String): Long? =
    runCatching { LocalDate.parse(text.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

/**
 * RECAPS drilldown (quant-viz ticket 05): [YearlyWrapped]'s latest year (when
 * one has generated - see [YearlyWrappedPane]'s doc) pinned at the top, then
 * two [DeckLineChart] month-by-month trends (miles driven, avg MPG) built by
 * [buildRecapMonthSlots]/[recapMonthPoints], then the full [MonthlyRecap] list
 * newest-first. Reached only from [MaintenanceDrilldownScreen]'s RECAPS row -
 * this drilldown has no panel row of its own on `FleetScreen`, so its own
 * `onBack` returns the caller to MAINTENANCE, not to the FLEET panel list
 * (`FleetScreen`'s drilldown-selection `when` wires that, not this file).
 *
 * Below two recaps the trend charts are meaningless (a two-point "trend" is
 * just two numbers), so ticket 05 asks for the list alone plus a stated
 * reason rather than a chart with nothing to show - the same "say it in
 * words rather than draw an empty/degenerate chart" posture
 * [DrivesPane]'s MPG-sparkline branch in `FleetScreen.kt` already uses.
 */
@Composable
fun RecapDrilldownScreen(recapsNewestFirst: List<MonthlyRecap>, yearlyWrapped: YearlyWrapped?, onBack: () -> Unit) {
    val sem = LocalLegionSemantics.current
    // Which recap's narrative is expanded, if any - "tapping a row may show
    // narrative in a plain text block" (ticket 05), one at a time so opening a
    // second row's narrative closes the first rather than stacking an
    // unbounded amount of prose into the list.
    var expandedRecapId by remember { mutableStateOf<Long?>(null) }
    val slots = if (recapsNewestFirst.size >= 2) buildRecapMonthSlots(recapsNewestFirst) else emptyList()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "RECAPS",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            LazyColumn(Modifier.fillMaxSize()) {
                // A generated-but-absent Wrapped (no year has rolled over yet)
                // is not a gap to announce - it simply omits the pane, per
                // ticket 05 part B.
                if (yearlyWrapped != null) {
                    item(key = "wrapped-pane") { YearlyWrappedPane(yearlyWrapped) }
                }
                if (recapsNewestFirst.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No monthly recaps generated yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    if (recapsNewestFirst.size < 2) {
                        item(key = "trend-sentence") {
                            Text(
                                "trend appears after two monthly recaps",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        item(key = "miles-chart") {
                            Text(
                                "MILES DRIVEN",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            DeckLineChart(
                                series = recapMonthPoints(slots) { it.milesDriven },
                                yLabel = { "%.0f mi".format(it) },
                                xLabels = recapMonthXLabels(slots),
                            )
                        }
                        item(key = "mpg-chart") {
                            // Ticket 09 (`.scratch/drive-ui/issues/09-mpg-scale-bug.md` - see
                            // MpgTrust's own doc): the chart is replaced with a stated reason rather
                            // than simply vanishing, same "say it in words rather than draw nothing"
                            // posture this screen already uses for a below-two-recaps trend (above).
                            if (MpgTrust.SHOW_MPG) {
                                Text(
                                    "AVG MPG",
                                    style = LegionType.stamp,
                                    color = sem.faint,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                                DeckLineChart(
                                    series = recapMonthPoints(slots) { it.avgMpg },
                                    yLabel = { "%.1f".format(it) },
                                    xLabels = recapMonthXLabels(slots),
                                )
                            } else {
                                Text(
                                    MpgTrust.WITHHELD_STAMP,
                                    style = LegionType.stamp,
                                    color = sem.faint,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    items(recapsNewestFirst, key = { it.id }) { recap ->
                        RecapRow(
                            recap = recap,
                            expanded = expandedRecapId == recap.id,
                            onToggle = { expandedRecapId = if (expandedRecapId == recap.id) null else recap.id },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The pinned header at the top of [RecapDrilldownScreen] (ticket 05 part B):
 * plain text [DeckRow]s over [wrapped]'s own fields, `avgMpg` `null` read as
 * "-" per the ticket's explicit call - [longestDriveMiles] gets the same
 * treatment for the same reason ("nothing invented for a field the source
 * data left null") even though the ticket text only names AVG MPG, since both
 * fields carry the identical nullable-Double shape.
 */
@Composable
private fun YearlyWrappedPane(wrapped: YearlyWrapped) {
    DeckPane(header = "Yearly Wrapped") {
        DeckRow(label = "Year", value = wrapped.year.toString())
        DeckRow(label = "Miles", value = "%.0f mi".format(wrapped.milesDriven))
        DeckRow(label = "Drives", value = wrapped.driveCount.toString())
        // Ticket 09 (`.scratch/drive-ui/issues/09-mpg-scale-bug.md` - see MpgTrust's own doc): a
        // real avgMpg value is never rendered while suppressed, distinct from the "-" a genuinely
        // absent value gets, so a driver can tell "withheld" apart from "no data logged".
        DeckRow(
            label = "Avg Mpg",
            value = if (!MpgTrust.SHOW_MPG) MpgTrust.WITHHELD_ROW_VALUE
                else wrapped.avgMpg?.let { "%.1f".format(it) } ?: "-",
        )
        DeckRow(label = "Longest", value = wrapped.longestDriveMiles?.let { "%.0f mi".format(it) } ?: "-")
        DeckRow(label = "Codes", value = wrapped.codeEventCount.toString())
        DeckRow(label = "Services", value = wrapped.serviceCount.toString())
        Text(
            wrapped.narrative,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * One [MonthlyRecap] in the newest-first list: headline row (month/year,
 * miles driven, an OUTLINE_MUTED [DeckTag] when [MonthlyRecap.notable] -
 * informational per ticket 03's ladder, not an advisory or an armed/ok
 * state), a drive/code/service-count subtitle, and - tapping the row -
 * [MonthlyRecap.narrative] as read-only prose (ticket 05: "tapping a row may
 * show narrative in a plain text block").
 */
@Composable
private fun RecapRow(recap: MonthlyRecap, expanded: Boolean, onToggle: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        DeckRow(
            label = "${monthAbbrevForRecap(recap.month)} ${recap.year}",
            value = "%.0f mi".format(recap.milesDriven),
            tag = if (recap.notable) { { DeckTag("NOTABLE", DeckTagStyle.OUTLINE_MUTED) } } else null,
        )
        Text(
            "${recap.driveCount} drives · ${recap.codeEventCount} codes · ${recap.serviceCount} services",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        if (expanded) {
            Text(
                recap.narrative,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** "JAN" for [RecapRow]'s headline - same short-name shape as [FleetRows.kt]'s private `monthAbbrev`, kept separate since that one is `private` to its file. */
private fun monthAbbrevForRecap(month: Int): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).uppercase()

/**
 * DRIVES' drilldown: the raw [DailyDriveLog] rows [com.kevin.legion.data.local.DailyDriveLogDao.getRecent]
 * already returns (same reuse posture as [MaintenanceDrilldownScreen]) - date, miles, MPG when the
 * day has one, and the day's own AI narrative. A day with `driveCount == 0` still renders (never
 * filtered out here, unlike [buildLastDriveSummary]'s "last drive" reading) because a drilldown's
 * job is the full log, not just the headline fact.
 */
@Composable
fun DriveHistoryDrilldownScreen(logsNewestFirst: List<DailyDriveLog>, onBack: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "DRIVE HISTORY",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            if (logsNewestFirst.isEmpty()) {
                Text(
                    "No drive logs yet. One appears the first time an OBD adapter records a finished drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(logsNewestFirst, key = { it.id }) { log ->
                        DriveLogRow(log)
                        Hairline()
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveLogRow(log: DailyDriveLog) {
    val sem = LocalLegionSemantics.current
    val dayMs = java.time.LocalDate.of(log.year, log.month, log.day)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(shortDate(dayMs), style = LegionType.stamp, color = sem.faint)
            val mpgPart = mpgSuffix(log.avgMpg) // ticket 09 - see FleetRows.mpgSuffix's own doc
            Text("${log.milesDriven.toInt()} mi$mpgPart", style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(log.narrative, style = MaterialTheme.typography.bodySmall, color = sem.faint)
    }
}

/**
 * FAULTS: every [CodeEvent] this vehicle has ever recorded, newest first, no cap - the drilldown
 * `UplinkPane`'s own doc used to name as missing ("no faults drilldown exists"). Reached by tapping
 * anywhere in STORED CODES (`FleetScreen.kt`'s `UplinkPane` wires that tap onto [onBack]'s sibling
 * `onOpenFaults`; this screen has no opinion about how it got here).
 *
 * **Not display-only, unlike [RecapDrilldownScreen]/[DriveHistoryDrilldownScreen]** (see this
 * file's own doc comment for that convention, and [PopulateScreen]'s doc for the established
 * exception this follows instead) - two things here need a live [android.content.Context] neither
 * of those screens do:
 *
 * 1. **Surrounding telemetry.** [FaultEventRow]'s own `LaunchedEffect` queries
 *    [com.kevin.legion.data.local.OdbSampleDao.getRange] PER VISIBLE ROW, not eagerly for every
 *    event up front - [LazyColumn] only composes rows actually on screen, so a car with months of
 *    fault history never fires more than a screenful of queries at once, and scrolling a row off
 *    screen cancels its own in-flight query the ordinary Compose way (the `LaunchedEffect` leaves
 *    composition).
 * 2. **IDENTIFY.** [codeNeedsIdentification] gates a per-code affordance that spends ONE
 *    [DiagnosticAgent.diagnose] call on the driver's own Gemini key (the ticket's explicit "must be
 *    per-code and user-initiated" - there is no batch-identify loop anywhere in this file) and
 *    persists the result via [DtcDescriptions.save] so the SAME code is never asked about twice, on
 *    this install, ever again. Identify state ([identifyingCodes]/[learnedThisSession]) is hoisted
 *    HERE rather than owned per-row, because the same code can appear in more than one [CodeEvent]
 *    and identifying it once must update EVERY row currently showing it, not just the row that
 *    triggered the call - `effectiveDescriptions` below is what every [FaultEventRow] actually
 *    reads.
 * 3. **DETAILS.** A second, unrelated piece of hoisted state, [expandedDetailKeys] - the mission-
 *    control follow-up "IDENTIFY saves far more than it shows": [DtcDescriptions] stores a
 *    `(title, detail)` pair per code and this screen used to render only the title, leaving likely
 *    causes/urgency/the typical fix unread on disk. Tapping DETAILS toggles that code's detail open
 *    on the ONE event row tapped, keyed by [faultDetailKey] (`eventId:code`) - deliberately NOT
 *    shared across every row showing the same code the way IDENTIFY's state is, because opening a
 *    detail writes nothing to disk, so two rows for a recurring code are free to sit independently
 *    open/closed. See [faultDetailKey]'s own doc for why the key is the event id, never the row's
 *    list position (LazyColumn recycling).
 *
 * [onBack] bumps `reloadKey` at the `FleetScreen.kt` call site (matching [FullScheduleScreen]/
 * [PopulateScreen]'s own "any drilldown that can write bumps the reload on the way out" rule) so a
 * fresh visit re-reads [descriptions] off disk rather than needing [learnedThisSession] to survive
 * past this composable's own lifetime - it does not need to; disk is the source of truth the moment
 * [DtcDescriptions.save] returns.
 */
@Composable
fun FaultsDrilldownScreen(
    vehicleId: String,
    vehicleLabel: String,
    eventsNewestFirst: List<CodeEvent>,
    clearEvents: List<CodeClearEvent>,
    descriptions: Map<String, Pair<String, String>>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    // See this screen's own doc for why IDENTIFY state lives here rather than per-row.
    var learnedThisSession by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    var identifyingCodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var identifyErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val effectiveDescriptions = descriptions + learnedThisSession

    // DETAILS toggle state - see this screen's own doc point 3 for why this is keyed per event row
    // (faultDetailKey) rather than shared per code the way IDENTIFY's state above is.
    var expandedDetailKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun toggleDetail(key: String) {
        expandedDetailKeys = if (key in expandedDetailKeys) expandedDetailKeys - key else expandedDetailKeys + key
    }

    // The one Gemini call this screen ever launches, and only ever ONE PER TAP - see
    // codeNeedsIdentification's own doc for why a code already in effectiveDescriptions never
    // offers this affordance at all, which is what keeps this per-code rather than a batch loop.
    fun identify(code: String) {
        if (code in identifyingCodes) return
        identifyingCodes = identifyingCodes + code
        identifyErrors = identifyErrors - code
        scope.launch {
            when (val result = DiagnosticAgent.diagnose(context, vehicleLabel, listOf(code), "What does $code mean?")) {
                is AgentResult.Success -> {
                    val (title, detail) = splitIdentifyResult(result.text)
                    withContext(Dispatchers.IO) {
                        val merged = DtcDescriptions.loadLearned(context) + (code to (title to detail))
                        DtcDescriptions.save(context, merged)
                    }
                    learnedThisSession = learnedThisSession + (code to (title to detail))
                }
                // Every other AgentResult branch (RateLimited/KeyInvalid/Overloaded/Offline/Failed)
                // is a soft failure - the code stays unidentified and IDENTIFY reappears, exactly
                // the "on failure say so plainly and leave the code unidentified" instruction, never
                // a retry loop or a silently-invented description.
                else -> identifyErrors = identifyErrors + (code to "Couldn't identify $code right now. Try again.")
            }
            identifyingCodes = identifyingCodes - code
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "FAULTS",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            if (eventsNewestFirst.isEmpty()) {
                Text(
                    "No fault codes recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(eventsNewestFirst, key = { it.id }) { event ->
                        FaultEventRow(
                            vehicleId = vehicleId,
                            event = event,
                            clearedMarker = clearedMarkerFor(event, clearEvents),
                            descriptions = effectiveDescriptions,
                            identifyingCodes = identifyingCodes,
                            identifyErrors = identifyErrors,
                            onIdentify = ::identify,
                            expandedDetailKeys = expandedDetailKeys,
                            onToggleDetail = ::toggleDetail,
                        )
                        Hairline()
                    }
                }
            }
        }
    }
}

/**
 * One [CodeEvent], fully expanded (no tap-to-expand ITS OWN ROOT - the ticket's own list is
 * exhaustive per event: date/time, mileage, codes+descriptions+IDENTIFY, the full freeze frame,
 * surrounding telemetry, and a cleared marker. **A DETAILS toggle exists one level down, per code**
 * - see [FaultsDrilldownScreen]'s own doc point 3 - which is narrower than "tap-to-expand the row"
 * and does not contradict this paragraph.
 *
 * Surrounding-telemetry samples are queried in THIS row's own `LaunchedEffect(event.id)`, not
 * passed down from [FaultsDrilldownScreen] - see that screen's own doc for why per-row, lazy
 * loading is what keeps an unbounded fault history cheap to open. Speed (`010D`) and rpm (`010C`)
 * are the two PIDs [com.kevin.legion.vehicle.TelemetryRecorder] actually samples at its base
 * 30-second tick regardless of which other PIDs a given install's ECU answers, so these are the two
 * every car's history can be expected to have something to draw - see that object's own PID list.
 * Each sparkline now carries a [sparklineCaption] beneath it (mission-control follow-up, "the
 * sparklines have no magnitude") - the shape alone answers "did the car accelerate", never "was
 * that 5 mph or 60". **The caption's own "when logged" clause, this row's "AROUND WHEN LOGGED"
 * heading, and the explanatory line rendered between FREEZE FRAME and it are a correction, not
 * original wording** - a real device (P1282) showed FREEZE FRAME's SPEED 0 mph next to a caption
 * that used to read "32 at the code", and both numbers were correct for two different moments:
 * [CodeEvent.timestamp] is stamped in `AriaForegroundService.recordCodeEvent`, driven by
 * `startHealthMonitor`'s poll loop (`AriaForegroundService.kt:519`, `:926`,
 * `HEALTH_SCAN_INTERVAL_MS` = 5 minutes) - when LEGION's periodic scan NOTICED a stored code, never
 * when the ECU actually set it. A stored DTC persists until cleared, so that gap is unbounded. The
 * freeze frame, by contrast, is the ECU's own snapshot from the moment the fault set. Do not revert
 * this wording back to "at the code" - see [FAULT_SPARKLINE_NEAREST_SAMPLE_MAX_MS]'s own doc for
 * the same trace.
 */
@Composable
private fun FaultEventRow(
    vehicleId: String,
    event: CodeEvent,
    clearedMarker: CodeClearEvent?,
    descriptions: Map<String, Pair<String, String>>,
    identifyingCodes: Set<String>,
    identifyErrors: Map<String, String>,
    onIdentify: (String) -> Unit,
    /** See [FaultsDrilldownScreen]'s doc point 3 and [faultDetailKey]'s own doc for the key shape. */
    expandedDetailKeys: Set<String>,
    onToggleDetail: (String) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val context = LocalContext.current
    val codes = remember(event.id) { codesInEvent(event) }
    val freezeFrame = remember(event.id, Temp.unit(context)) { formatFreezeFrame(context, event.freezeFrameJson) }
    val mileageText = remember(event.id) { faultEventMileageText(event.mileage) }

    // `null` means "still loading"; an empty list is a genuine "queried, nothing in the window" -
    // the two states render differently below (see the AROUND THIS EVENT block).
    var speedSamples by remember(event.id) { mutableStateOf<List<OdbSample>?>(null) }
    var rpmSamples by remember(event.id) { mutableStateOf<List<OdbSample>?>(null) }
    LaunchedEffect(event.id) {
        val window = telemetryWindow(event.timestamp)
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        speedSamples = withContext(Dispatchers.IO) { dao.getRange(vehicleId, "010D", window.first, window.last) }
        rpmSamples = withContext(Dispatchers.IO) { dao.getRange(vehicleId, "010C", window.first, window.last) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text("${shortDate(event.timestamp)} · ${clockTime(event.timestamp)}", style = LegionType.stamp, color = sem.faint)
        // Mileage is ALWAYS labelled an estimate - see faultEventMileageText's own doc for why this
        // event, uniquely among the app's mileage surfaces, has no confirmed/estimated branch to
        // even ask (CarToolbelt.codeHistory's own reasoning, carried here verbatim).
        if (mileageText != null) {
            Text(mileageText, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 2.dp))
        }
        // D7's union rule applied per-event (clearedMarkerFor's own doc): this code event reads as
        // cleared the moment ANY later successful clear ran, dated to that clear.
        if (clearedMarker != null) {
            Text(
                "CLEARED ${shortDate(clearedMarker.timestamp)}",
                style = LegionType.stamp,
                color = sem.credit,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        codes.forEach { code ->
            val entry = descriptions[code]
            val description = entry?.first
            // codeDetailIsReachable's own doc: false for an unidentified code (entry == null, the
            // SAME condition codeNeedsIdentification below reads), and false for a blank or
            // title-duplicating detail - neither of those gets a tap affordance.
            val detailReachable = codeDetailIsReachable(entry)
            val detailKey = faultDetailKey(event.id, code)
            val detailExpanded = detailReachable && detailKey in expandedDetailKeys
            Column(Modifier.padding(bottom = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(code, style = LegionType.reading, color = sem.quarantined)
                    when {
                        code in identifyingCodes -> Text("IDENTIFYING...", style = LegionType.stamp, color = sem.faint)
                        codeNeedsIdentification(code, descriptions) -> Text(
                            "IDENTIFY",
                            style = LegionType.stamp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onIdentify(code) }.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        // Mutually exclusive with both branches above by construction - IDENTIFY only
                        // ever offers itself where entry == null (codeNeedsIdentification's own doc),
                        // and detailReachable requires entry != null - so this affordance and
                        // IDENTIFY's can never both render for the same code, and their two clickable
                        // Text nodes never compete for the same tap.
                        detailReachable -> Text(
                            if (detailExpanded) "HIDE" else "DETAILS",
                            style = LegionType.stamp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onToggleDetail(detailKey) }.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    description ?: "not identified locally",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (description != null) MaterialTheme.colorScheme.onSurface else sem.faint,
                )
                // The detail half of DtcDescriptions' (title, detail) pair - collapsed by default
                // (mission-control follow-up: "collapsed by default, so the list stays scannable"),
                // toggled open by the DETAILS/HIDE affordance above. Rendered through
                // detailWithoutTitlePrefix rather than entry.second raw: on a real device (P1282,
                // FAULTS drilldown) the title line read "Code P1282 on your Jeep indicates a fuel
                // pump relay control circuit malfunction" and the expanded detail BEGAN with that
                // exact sentence again, so it read twice - codeDetailIsReachable's own doc explains
                // why reachability itself is judged on the same stripped text.
                if (detailExpanded) {
                    Text(
                        detailWithoutTitlePrefix(entry!!.first, entry.second),
                        style = MaterialTheme.typography.bodySmall,
                        color = sem.faint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                identifyErrors[code]?.let { error ->
                    Text(error, style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        // Freeze frame - every PID the ECU actually latched, never a fabricated zero for one it
        // didn't (formatFreezeFrame's own doc).
        Text("FREEZE FRAME", style = LegionType.stamp, color = sem.faint)
        if (freezeFrame.isEmpty()) {
            Text(
                "No freeze frame recorded for this event.",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                freezeFrame.joinToString("   ") { "${it.label} ${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Once per event row, in words, not left to two disagreeing numbers to explain themselves:
        // FREEZE FRAME above is the ECU's OWN snapshot from the moment the fault actually set.
        // Everything below (AROUND WHEN LOGGED) is dated to event.timestamp, which
        // AriaForegroundService.recordCodeEvent stamps from startHealthMonitor's poll loop
        // (AriaForegroundService.kt:519, HEALTH_SCAN_INTERVAL_MS at :926 = 5 minutes) - the moment
        // LEGION's periodic scan NOTICED a stored code, not the moment it set. A stored DTC persists
        // until cleared, so that gap can be minutes, hours, or days, and on a real device (P1282) it
        // was enough for FREEZE FRAME to read SPEED 0 mph while the "at the code" caption below it
        // (as it used to say) read 32 mph - both readings were correct, for two different moments.
        Text(
            "Freeze frame is the car's own snapshot from when the fault set. The readings below are " +
                "from when LEGION logged it - the two can be far apart.",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(top = 6.dp),
        )

        // Surrounding telemetry - "what point in LEGION's own logging" this code was noticed at, NOT
        // "what point in the drive the fault set" - see this row's own doc and the note above for why
        // those are two different moments and this heading no longer conflates them.
        Spacer(Modifier.height(6.dp))
        Text("AROUND WHEN LOGGED", style = LegionType.stamp, color = sem.faint)
        val speed = speedSamples
        val rpm = rpmSamples
        when {
            speed == null || rpm == null -> Text(
                "Loading...",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(top = 2.dp),
            )
            speed.isEmpty() && rpm.isEmpty() -> Text(
                "No telemetry recorded around this event.",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(top = 2.dp),
            )
            else -> {
                // Speed samples are stored in km/h (TelemetryRecorder's own raw PID unit for `010D`)
                // - converted to mph here, same 0.621371 factor and same "screens match screens"
                // reasoning formatFreezeFrame's own SPEED conversion states, since this drilldown's
                // FREEZE FRAME block above already renders mph for the identical reading.
                if (speed.isNotEmpty()) {
                    Text("SPEED", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 4.dp))
                    DeckSparkline(eventTelemetrySparkline(speed))
                    sparklineCaption(speed, event.timestamp, { kmh -> (kmh * 0.621371).roundToInt() }, " mph")?.let { caption ->
                        Text(caption, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                // RPM stays the ECU's own native unit - bare number, no suffix, matching
                // formatFreezeFrame's own "RPM... no 'human' unit to convert into" call.
                if (rpm.isNotEmpty()) {
                    Text("RPM", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 4.dp))
                    DeckSparkline(eventTelemetrySparkline(rpm))
                    sparklineCaption(rpm, event.timestamp, { it.roundToInt() }, "")?.let { caption ->
                        Text(caption, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                // Location only when a surrounding sample actually carried a fix (locationFromSamples'
                // own doc) - rendered as raw coordinates rather than a reverse-geocoded address, which
                // would add a network round trip this read-only drilldown has no other reason to make.
                locationFromSamples(speed + rpm)?.let { (lat, lng) ->
                    Text(
                        "Location %.4f, %.4f".format(lat, lng),
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Maintenance drilldown: mixed schedule", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewMaintenanceDrilldown() = LegionTheme {
    MaintenanceDrilldownScreen(
        dueRows = listOf(
            DueRowView("Oil Change", "OVERDUE", "every 5,000 mi - overdue", overdue = true, isGuess = true),
            DueRowView("Front Brake Pads", "in 1750 miles", "every 25,000 mi - due in 1750 miles", overdue = false),
        ),
        unknownCount = 7,
        recapCount = 3,
        monthlyRecaps = listOf(
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 8, generatedAt = 0,
                milesDriven = 812.0, avgMpg = 27.1, driveCount = 24, longestDriveMiles = 140.0,
                codeEventCount = 0, serviceCount = 1, narrative = "A normal month.",
                coverImagePath = null, notable = false,
            ),
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 7, generatedAt = 0,
                milesDriven = 1_240.0, avgMpg = null, driveCount = 31, longestDriveMiles = 410.0,
                codeEventCount = 1, serviceCount = 0, narrative = "A road trip month.",
                coverImagePath = null, notable = true, notableReason = "Longest drive of the year",
            ),
        ),
        oilAnalysisCount = 2,
        onOpenUnknown = {}, onOpenFullSchedule = {}, onOpenRecaps = {}, onOpenOilAnalysis = {},
        onBack = {},
    )
}

@Preview(name = "Full schedule: three groups", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewFullSchedule() = LegionTheme {
    FullScheduleScreen(
        items = listOf(
            MaintenanceItem(vehicleId = "x", serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 100_000, intervalSource = "SEEDED"),
            MaintenanceItem(vehicleId = "x", serviceName = "Tire Rotation", intervalMiles = 7500, lastDoneMileage = 106_800, intervalSource = "CONFIRMED"),
            MaintenanceItem(vehicleId = "x", serviceName = "Brake Fluid", intervalMonths = 24, intervalSource = "SEEDED"),
        ),
        currentMileage = 107_000,
        odometerUnset = false,
        now = 1_700_000_000_000L,
        filterUnknownOnly = false,
        onOpenItem = {}, onAddItem = {}, onConfirmAll = { emptyList() }, onOpenServiceHistory = {}, onBack = {},
    )
}

@Preview(name = "Item detail: existing SEEDED item", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewItemDetailExisting() = LegionTheme {
    ItemDetailScreen(
        item = MaintenanceItem(vehicleId = "x", serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 100_000, intervalSource = "SEEDED"),
        serviceHistory = listOf(ServiceRecord(vehicleId = "x", serviceName = "Oil Change", mileage = 100_000, date = 1_700_000_000_000L)),
        checkDuplicate = { null },
        onSetInterval = { _, _, _ -> WriteOutcome(true, "ok") },
        onSetAnchor = { _, _, _, _, _ -> WriteOutcome(true, "ok") },
        onDelete = { WriteOutcome(true, "ok") },
        onAddItem = { _, _, _, _, _, _ -> WriteOutcome(true, "ok") },
        onBack = {},
    )
}

@Preview(name = "Item detail: add new item", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewItemDetailAdd() = LegionTheme {
    ItemDetailScreen(
        item = null,
        serviceHistory = emptyList(),
        checkDuplicate = { null },
        onSetInterval = { _, _, _ -> WriteOutcome(true, "ok") },
        onSetAnchor = { _, _, _, _, _ -> WriteOutcome(true, "ok") },
        onDelete = { WriteOutcome(true, "ok") },
        onAddItem = { _, _, _, _, _, _ -> WriteOutcome(true, "ok") },
        onBack = {},
    )
}

@Preview(name = "Recaps drilldown: Wrapped + monthly trend", widthDp = 360, heightDp = 1200)
@Composable
private fun PreviewRecapDrilldown() = LegionTheme {
    RecapDrilldownScreen(
        recapsNewestFirst = listOf(
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 8, generatedAt = 0,
                milesDriven = 812.0, avgMpg = 27.1, driveCount = 24, longestDriveMiles = 140.0,
                codeEventCount = 0, serviceCount = 1, narrative = "A normal month, one oil change.",
                coverImagePath = null, notable = false,
            ),
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 7, generatedAt = 0,
                milesDriven = 1_240.0, avgMpg = null, driveCount = 31, longestDriveMiles = 410.0,
                codeEventCount = 1, serviceCount = 0, narrative = "A road trip month - check engine light near the end.",
                coverImagePath = null, notable = true, notableReason = "Longest drive of the year",
            ),
        ),
        yearlyWrapped = YearlyWrapped(
            vehicleId = "x", year = 2026, generatedAt = 0, milesDriven = 9_400.0, driveCount = 210,
            avgMpg = 26.4, longestDriveMiles = 410.0, notableMonths = 2, codeEventCount = 3,
            serviceCount = 5, narrative = "A steady year with one long road trip.", coverImagePath = null,
        ),
        onBack = {},
    )
}

@Preview(name = "Drive history drilldown: a few logged days", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDriveHistoryDrilldown() = LegionTheme {
    DriveHistoryDrilldownScreen(
        logsNewestFirst = listOf(
            DailyDriveLog(
                vehicleId = "x", year = 2026, month = 8, day = 7, generatedAt = 0,
                milesDriven = 42.0, avgMpg = 27.3, driveCount = 2, codeEventCount = 0,
                narrative = "A normal commute day, nothing notable.",
            ),
            DailyDriveLog(
                vehicleId = "x", year = 2026, month = 8, day = 6, generatedAt = 0,
                milesDriven = 0.0, avgMpg = null, driveCount = 0, codeEventCount = 0,
                narrative = "Parked all day.",
            ),
        ),
        onBack = {},
    )
}
