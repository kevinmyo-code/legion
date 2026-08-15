package com.kevin.legion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DailyDriveLog
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.MonthlyRecap
import com.kevin.legion.data.local.OilAnalysis
import com.kevin.legion.data.local.YearlyWrapped
import com.kevin.legion.ui.common.DeckFeedRow
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.ui.fleet.DriveHistoryDrilldownScreen
import com.kevin.legion.ui.fleet.DriveSummaryView
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.fleet.FaultRow
import com.kevin.legion.ui.fleet.FaultRowView
import com.kevin.legion.ui.fleet.FullScheduleScreen
import com.kevin.legion.ui.fleet.ItemDetailScreen
import com.kevin.legion.ui.fleet.LIVE_GAUGE_PIDS
import com.kevin.legion.ui.fleet.LiveRowView
import com.kevin.legion.ui.fleet.MaintenanceDrilldownScreen
import com.kevin.legion.ui.fleet.ObdDeviceScreen
import com.kevin.legion.ui.fleet.OilAnalysisDrilldownScreen
import com.kevin.legion.ui.fleet.RecapDrilldownScreen
import com.kevin.legion.ui.fleet.SetOdometerDialog
import com.kevin.legion.ui.fleet.VehicleSpecsScreen
import com.kevin.legion.ui.fleet.buildDueRows
import com.kevin.legion.ui.fleet.buildLastDriveSummary
import com.kevin.legion.ui.fleet.buildLiveRows
import com.kevin.legion.ui.fleet.buildMilesSparkline
import com.kevin.legion.ui.fleet.buildMpgSparkline
import com.kevin.legion.ui.fleet.capFaultRows
import com.kevin.legion.ui.fleet.distinctFaultsByFirstSeen
import com.kevin.legion.ui.fleet.writeAddItem
import com.kevin.legion.ui.fleet.writeConfirmAll
import com.kevin.legion.ui.fleet.writeDeleteItem
import com.kevin.legion.ui.fleet.writeSetAnchor
import com.kevin.legion.ui.fleet.writeSetInterval
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckMotionEnabled
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.ObdDeviceRegistry
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.kevin.legion.location.PlaceController

/**
 * `fleet` tab - the vehicle UPLINK (cyberdeck-ui ticket 09's resolution, built by ticket 18).
 * **FLEET and TELEMETRY merged into one module here**: ticket 09 answer §1 ("two screens
 * claiming one car was head-unit heritage"). What used to be `ui/TelemetryScreen.kt`'s whole
 * screen is now the UPLINK panel's drilldown, invoked directly as a composable (see
 * [FleetDrilldown.UPLINK] below) rather than duplicated - [TelemetryScreen] keeps its own file,
 * its own state holder, and its own await-first/copy-once discipline (the L15 fix from commit
 * `4fd241e`) completely unchanged; only the HOSTING moved from a nav route to in-screen state.
 *
 * **Panels, fixed order, per ticket 09 answer §3**: UPLINK (always leads, fixed position -
 * answer §2 declined a reorder-on-state a third time) -> MAINTENANCE -> DRIVES -> CARS. FAULTS
 * (stored DTCs) is not one of the four named panels in the ticket - folded into UPLINK's content
 * instead of dropped, since stored codes are exactly the kind of "what is the car saying right
 * now" reading UPLINK already owns, and CLAUDE.md's L10 discipline is that a real, working
 * feature (`FaultRowView`, ported and displaying real `code_events` rows) does not get quietly
 * deleted in a merge.
 *
 * **Tiled to the mission-control grammar (ticket 16's FLEET build,
 * `.scratch/mission-control/issues/12-surface-inventories.md`).** UPLINK stays FULL (this
 * surface's hero, unchanged) and CARS stays FULL (a roster is rows); MAINTENANCE and DRIVES drop
 * from their own FULL panels to a shared HALF-tile row via
 * [com.kevin.legion.ui.common.EqualHeightRow]/[com.kevin.legion.ui.common.HalfTile] - the same
 * shell HOME's own tile row and [BodyScreen]'s INTAKE/SLEEP row both use. The fixed panel ORDER
 * above is unchanged; only MAINTENANCE and DRIVES' own SHAPE changed, and their drilldowns
 * ([MaintenanceDrilldownScreen], [DriveHistoryDrilldownScreen]) still carry the full detail the
 * old FULL panels used to render inline - see the tile row's own comment in [FleetListing]. The
 * FLEET uplink sweep (ticket 07) is explicitly OUT of this build - it needs a Layout Inspector
 * check for flat recomposition this session cannot run, so UPLINK stays exactly as it was.
 *
 * **Staleness is always worded** (ticket 09 answer §4): every UPLINK reading bakes
 * [com.kevin.legion.util.relativeAge] straight into its value text
 * (`"194°F · 3 DAYS AGO"`), not a colour or a separate glyph - a bare number never reads as live,
 * connected or not. The panel's own opening line states `// LIVE` or `// NO LINK` in words for
 * the same reason.
 *
 * **The ticket-20 driving-mode entry point.** When [ObdBluetoothManager.isConnected], UPLINK
 * shows a `DRIVE MODE` [DeckTag] row (INVERTED_GREEN, ticket 03's armed/ok family) - see
 * `DriveModeOfferRow`'s doc comment below. Built inert by ticket 18 (no click handler, "whoever
 * builds ticket 20 wires the click here"); ticket 20 wires [onOpenDrivingMode] onto it. Ticket 11
 * answer §1's second surface (an Alfred strip prompt on OBD-connect) is a named, deferred item -
 * see this row's own doc for why.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill: [FleetScreen] is the state
 * holder (talks to [VehicleController]/[CarDatabase]/[ObdBluetoothManager], owns every side
 * effect and the drilldown selection), [FleetContent] is plain UI state plus callbacks and is
 * what the `@Preview`s below exercise.
 */
data class FleetUiState(
    val loading: Boolean = true,
    val vehicleLabel: String = "",
    /**
     * The bare number half of [VehicleController.mileageLabel] - "227,900 mi" (confirmed) or "about
     * 227,900 mi" (estimated) - blank when there is no reading at all yet. Split from
     * [mileageCaveatText] rather than rendered as one [DeckRow] value (ticket 10's own combined
     * string, "about 227,900 mi - estimated, last confirmed 3 days ago", does not fit [DeckRow]'s
     * contract: its value column is `maxLines = 1` with `TextOverflow.Visible`, meant for a short
     * reading, never a full sentence) - the caveat renders as its own line underneath instead, same
     * "value plus a sub-line" shape [DueRowView] already uses elsewhere on this same screen.
     */
    val mileageValueText: String = "",
    /**
     * [VehicleController.mileageCaveat]'s own words ("estimated, last confirmed 3 days ago"), or
     * blank when the reading IS the driver's own last typed one with nothing accrued since - see
     * [mileageValueText]'s doc for why this renders as a separate line rather than folded into one
     * string. Never a bare threshold or a colour standing in for the words (CLAUDE.md §4).
     */
    val mileageCaveatText: String = "",
    val connected: Boolean = false,
    val liveRows: List<LiveRowView> = emptyList(),
    val dueRows: List<DueRowView> = emptyList(),
    /**
     * Every non-deleted [MaintenanceItem] for the vehicle, raw - ticket 09's FULL SCHEDULE and ITEM
     * DETAIL both need the whole row, not [dueRows]' already-formatted-and-filtered subset
     * ([buildDueRows]'s own doc: unknown-anchor items are excluded entirely, not merely unsorted).
     */
    val maintenanceItems: List<MaintenanceItem> = emptyList(),
    /** Items with no anchor at all ([VehicleController.unknownItems]) - ticket 09's MAINTENANCE triage counts these, never lists them; [buildFleetTile] also needs this so the tile stops saying OK while they exist. */
    val maintenanceUnknownCount: Int = 0,
    /** Raw current mileage - [mileageValueText]/[mileageCaveatText] are display-formatted, ticket 09's due-figure math on FULL SCHEDULE/ITEM DETAIL needs the `Int`. */
    val currentMileageRaw: Int = 0,
    /**
     * `vehicle.odometerBaseline == 0` - the real "driver has never confirmed an odometer" signal
     * (senior-dev review fix, mission-control ticket 09 follow-up). [currentMileageRaw] can read
     * positive even in this state (accumulated trip miles against a still-zero baseline - see
     * [chooseDueAxis]'s doc), so [FullScheduleScreen] needs this carried separately rather than
     * re-deriving it from `currentMileageRaw > 0`, the same fix applied to [dueRows] above.
     * Defaults `true` (the safe/conservative reading - refuse the miles axis) so a not-yet-loaded
     * [FleetUiState] never opens the miles axis on a mileage this default (`0`) would otherwise make
     * look plausible.
     */
    val odometerUnset: Boolean = true,
    /** The active vehicle's own id ([Vehicle.obdMac]) - every ticket 09 write goes through this exact id, not a re-resolved [ActiveVehicle.current] that could race a car switch mid-screen. */
    val vehicleId: String = "",
    /**
     * Every [ServiceRecord] for the vehicle, unfiltered - [FleetDrilldown.ITEM_DETAIL]'s own
     * `serviceHistory` param filters this by name in Kotlin (see the call site's own comment for
     * why: no per-name DAO query exists and ticket 09 adds none).
     */
    val allServiceRecords: List<ServiceRecord> = emptyList(),
    val faults: List<Pair<FaultRow, String?>> = emptyList(),
    val serviceHistoryCount: Int = 0,
    val buildSheetCount: Int = 0,
    val recapCount: Int = 0,
    /** Cars in the roster OTHER than the active one - what the switcher row offers. */
    val otherCarCount: Int = 0,
    /** True when no car is pinned on this phone and [com.kevin.legion.vehicle.ActiveVehicle] follows the dongle. */
    val followingAdapter: Boolean = true,
    /**
     * What the CARS pane's adapter row shows: the selected dongle's MAC (suffixed
     * `· BLE` when it is a GATT adapter), or `NONE SELECTED`. Read from
     * [com.kevin.legion.vehicle.ObdDeviceRegistry], not from the live link -
     * a selected adapter that is out of range is still the selected one.
     *
     * **Lived in [UplinkPane] until mission-control ticket 16 follow-up ("get FLEET's tile row
     * above the fold") moved it to [CarsPane]** - see that pane's own doc for why: ADAPTER and
     * SPECS/VIN are configuration ("what is this car / what is it paired to"), not telemetry, and
     * moving both off the hero pane was the headroom UPLINK needed once the gauge-row density fix
     * alone still measured short on-device (see [UplinkPane]'s doc for the actual pixel numbers).
     */
    val adapterLabel: String = "NONE SELECTED",
    /**
     * Whether a VIN has been decoded for this car, for the SPECS row's
     * ON FILE / NOT READ state. Just the presence of the string - the row is a
     * doorway, and the drilldown owns the detail.
     *
     * **Moved from [UplinkPane] to [CarsPane] alongside [adapterLabel]** - same ticket, same
     * reasoning.
     */
    val vinOnFile: Boolean = false,
    /** DRIVES panel: the most recent day with a finished drive, from `daily_drive_logs` - see [buildLastDriveSummary]. */
    val driveSummary: DriveSummaryView = DriveSummaryView("NO DRIVES LOGGED", "nothing recorded yet", hasData = false),
    /** DRIVES panel: oldest-first MPG points for [DeckSparkline], from the same rows as [driveSummary] - see [buildMpgSparkline]. */
    val mpgSparkline: List<Float?> = emptyList(),
    /** DRIVES panel (quant-viz ticket 12): oldest-first miles-driven points for the second [DeckSparkline], same rows as [mpgSparkline] - see [buildMilesSparkline]. */
    val milesSparkline: List<Float?> = emptyList(),
    /** DRIVES drilldown: the raw recent logs, newest-first, for [DriveHistoryDrilldownScreen]. */
    val recentDriveLogs: List<DailyDriveLog> = emptyList(),
    /** RECAPS drilldown (quant-viz ticket 05): every [MonthlyRecap] on file, newest-first - same list [recapCount] is `.size` of, not a second query. */
    val monthlyRecaps: List<MonthlyRecap> = emptyList(),
    /** RECAPS drilldown: the latest [YearlyWrapped] year, or `null` when none has generated yet (it generates in December - see [RecapDrilldownScreen]'s doc). */
    val yearlyWrapped: YearlyWrapped? = null,
    /** OIL drilldown (quant-viz ticket 06): every [OilAnalysis] on file, newest-first, straight off [com.kevin.legion.data.local.OilAnalysisDao.getAll]. */
    val oilAnalyses: List<OilAnalysis> = emptyList(),
)

/**
 * The in-screen drilldown selection (ticket 18: "in-screen drilldown... not a nav route",
 * following [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]'s pattern). Most of these need no
 * payload - each drilldown reads straight off [FleetUiState], the same list its panel already
 * rendered - so a plain enum is enough. **Ticket 09's two new values are the exception**:
 * [FULL_SCHEDULE] needs to remember whether it was entered filtered-to-unknowns, and [ITEM_DETAIL]
 * needs to remember which item (or whether this is ADD ITEM) - rather than promoting this to a
 * sealed class carrying a payload, that state lives in two small sibling `var`s
 * (`fullScheduleFilterUnknown`, `itemDetailTarget`/`itemDetailIsAdd`) right beside `drilldown` in
 * [FleetScreen] below, so returning from ITEM DETAIL to FULL SCHEDULE preserves the filter without
 * threading it back through the enum itself.
 */
private enum class FleetDrilldown { UPLINK, MAINTENANCE, FULL_SCHEDULE, ITEM_DETAIL, DRIVES, ADAPTER, SPECS, RECAPS, OIL }

@Composable
fun FleetScreen(
    onOpenPlaces: () -> Unit,
    onOpenCars: () -> Unit,
    onOpenDrivingMode: () -> Unit,
    /**
     * Mission-control ticket 07's uplink sweep, reported up to [MainActivity]'s `LegionShell` so
     * [com.kevin.legion.ui.common.StatusLine]'s shell cursor can yield (`cursorSolid = true`)
     * while, and ONLY while, [UplinkPane]'s own sweep is genuinely animating - see that pane's
     * doc for the two-effect mechanism that fires `false` the instant the pane leaves composition
     * (any drilldown, or navigating off FLEET entirely). Defaults to a no-op so every other caller
     * of this screen (there is currently exactly one, `LegionShell`) is unaffected until it wires
     * a `mutableStateOf<Boolean>` through, same "dead by construction until threaded" shape
     * [com.kevin.legion.ui.common.StatusLine]'s own `cursorSolid`/`alarmCount` params use.
     */
    onSweepActiveChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(FleetUiState()) }
    val connectionState by ObdBluetoothManager.connectionState.collectAsStateWithLifecycle()

    // Bumped when the ADAPTER drilldown closes. Selecting a dongle in there
    // writes SharedPreferences, not `state`, so without a re-key the UPLINK
    // adapter row would keep showing the pre-selection value until the tab was
    // left and re-entered.
    var reloadKey by remember { mutableStateOf(0) }

    // Ticket 10's manual-entry control - see CarsPane's own doc and the dialog's render site below.
    var showOdometerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        val vehicle = VehicleController.currentVehicle(context)
        val currentMileage = VehicleController.currentMileage(vehicle)
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val items: List<MaintenanceItem> = db.maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val codeEvents = db.codeEventDao().getAll(vehicle.obdMac)
        val samplesByPid = LIVE_GAUGE_PIDS.associateWith { pid ->
            db.odbSampleDao().getLatest(vehicle.obdMac, pid, 1).firstOrNull()
        }
        // DRIVES: reuses DailyDriveLogController's own hourly rollup - no new
        // aggregation query, see buildLastDriveSummary/buildMpgSparkline's docs.
        val recentLogs = db.dailyDriveLogDao().getRecent(vehicle.obdMac, DRIVE_HISTORY_WINDOW)

        // RECAPS/OIL (quant-viz tickets 05/06): read once here, reused for
        // both the MAINTENANCE drilldown's count rows and their own
        // drilldowns below - no DAO accessor added where one already covers
        // this (MonthlyRecapDao.getAll, YearlyWrappedDao.getAll, OilAnalysisDao.getAll).
        val monthlyRecaps = db.monthlyRecapDao().getAll(vehicle.obdMac)
        // YearlyWrappedDao has no getLatest - getAll is already `ORDER BY year
        // DESC`, so firstOrNull() is that accessor without adding a new query.
        val yearlyWrapped = db.yearlyWrappedDao().getAll(vehicle.obdMac).firstOrNull()
        val oilAnalyses = db.oilAnalysisDao().getAll(vehicle.obdMac)

        // ITEM DETAIL's read-only service-history list (ticket 09): one collect of the whole
        // vehicle's history, filtered by name at the ITEM_DETAIL call site - see that call site's
        // own comment for why this is not a second, per-name DAO query.
        val allServiceRecords = db.serviceRecordDao().getRecordsForVehicle(vehicle.obdMac).first()

        val selectedAdapter = ObdBluetoothManager.getActiveDeviceMac(context)
        val adapterLabel = selectedAdapter?.let { mac ->
            if (ObdDeviceRegistry.isBle(context, mac)) "$mac · BLE" else mac
        } ?: "NONE SELECTED"

        // Resolved into a local before the single state assignment below, per
        // this block's own AWAIT FIRST, COPY ONCE note.
        val vinOnFile = db.vehicleSpecDao().get(vehicle.obdMac)?.vin?.isNotBlank() == true

        // File IO (DtcDescriptions reads a bundled asset + a per-install disk
        // cache), off the composition's dispatcher so a cold read never jank
        // the frame this LaunchedEffect resumes on.
        val descriptions = withContext(Dispatchers.IO) {
            DtcDescriptions.loadSeed(context) + DtcDescriptions.loadLearned(context)
        }
        val faults = distinctFaultsByFirstSeen(codeEvents).map { it to descriptions[it.code]?.first }

        // AWAIT FIRST, COPY ONCE: every suspend call above is resolved into a
        // local val before this single, non-suspending assignment - the L15
        // fix from commit 4fd241e, carried into the merged screen rather than
        // re-introduced by accident when DRIVES' new reads were added.
        state = FleetUiState(
            loading = false,
            vehicleLabel = VehicleController.displayLabel(vehicle).ifBlank { "This car" },
            // Ticket 10: bare "N mi" only when it IS the driver's own last reading with nothing
            // accrued since; every drifting-estimate value carries the "estimated, last confirmed..."
            // caveat in words, every time - see VehicleController.mileageCaveat's own doc for the
            // exact bare/estimate split. This is the CARS pane row the ticket names explicitly; see
            // FleetUiState.mileageValueText's own doc for why it's rendered as two fields, not one.
            mileageValueText = VehicleController.mileageValueText(vehicle),
            mileageCaveatText = VehicleController.mileageCaveat(vehicle, now).orEmpty(),
            connected = ObdBluetoothManager.isConnected,
            liveRows = buildLiveRows(samplesByPid, now),
            dueRows = buildDueRows(items, currentMileage, vehicle.odometerBaseline == 0, now),
            maintenanceItems = items,
            maintenanceUnknownCount = items.count { VehicleController.isUnknown(it) },
            currentMileageRaw = currentMileage,
            odometerUnset = vehicle.odometerBaseline == 0,
            vehicleId = vehicle.obdMac,
            faults = faults,
            serviceHistoryCount = db.serviceRecordDao().countForVehicle(vehicle.obdMac),
            buildSheetCount = db.buildEntryDao().countForVehicle(vehicle.obdMac),
            recapCount = monthlyRecaps.size,
            // Archived cars are excluded: they are not switchable targets, so
            // counting them would offer the driver more cars than the roster
            // will actually show them.
            otherCarCount = (VehicleController.allVehicles(context).count { it.obdMac != vehicle.obdMac }),
            followingAdapter = ActiveVehicle.selected(context) == null,
            adapterLabel = adapterLabel,
            vinOnFile = vinOnFile,
            driveSummary = buildLastDriveSummary(recentLogs, now),
            mpgSparkline = buildMpgSparkline(recentLogs),
            milesSparkline = buildMilesSparkline(recentLogs),
            recentDriveLogs = recentLogs,
            monthlyRecaps = monthlyRecaps,
            yearlyWrapped = yearlyWrapped,
            oilAnalyses = oilAnalyses,
            allServiceRecords = allServiceRecords,
        )
    }

    // connectionState is the live signal (collected reactively); everything
    // else in `state` is a one-shot DB snapshot loaded above - same "merge a
    // live flow onto an async-loaded state" shape as LedgerScreen's fullState.
    val fullState = state.copy(connected = connectionState == ObdBluetoothManager.ConnectionState.CONNECTED)

    // The in-screen drilldowns (see FleetDrilldown's own doc). Kept OUTSIDE
    // `state` (same split LedgerScreen's `drilldownCategory` uses) so
    // opening/closing one never fights the LaunchedEffect above over
    // ownership of the same var.
    var drilldown by remember { mutableStateOf<FleetDrilldown?>(null) }

    // Ticket 09: FULL SCHEDULE's own filter state and ITEM DETAIL's target service name both live
    // OUTSIDE the FleetDrilldown value itself (a plain enum, not a sealed class carrying a payload)
    // so returning from ITEM DETAIL to FULL SCHEDULE preserves whichever filter got it there -
    // MAINTENANCE's UnknownCountRow sets filterUnknownOnly = true before navigating in; the triage
    // screen's own unconditional FULL SCHEDULE button leaves it false. `itemDetailTarget = null`
    // means ADD ITEM (ItemDetailScreen's own `item == null` shape); a non-null value is an existing
    // item's service name, looked up fresh out of `fullState.maintenanceItems` on each render so an
    // edit made moments ago is what the screen actually shows.
    var fullScheduleFilterUnknown by remember { mutableStateOf(false) }
    var itemDetailTarget by remember { mutableStateOf<String?>(null) }
    var itemDetailIsAdd by remember { mutableStateOf(false) }

    val currentDrilldown = drilldown
    if (currentDrilldown != null) {
        // Physical back returns to the panel list, EXCEPT from RECAPS/OIL (reached from inside
        // MAINTENANCE, so back returns there) and ITEM DETAIL (reached from inside FULL SCHEDULE,
        // so back returns there, filter preserved via fullScheduleFilterUnknown above).
        val backTarget = when (currentDrilldown) {
            FleetDrilldown.RECAPS, FleetDrilldown.OIL -> FleetDrilldown.MAINTENANCE
            FleetDrilldown.ITEM_DETAIL -> FleetDrilldown.FULL_SCHEDULE
            else -> null
        }
        // Ticket 09 verification #3: "confirm the drilldown-return path refreshes the parent"
        // (mission-control ticket 04's stale-parent bug). FULL SCHEDULE and ITEM DETAIL are the two
        // screens on this map that can write, so both bump reloadKey on every way out - physical
        // back (below) and each screen's own onBack callback (further down) - rather than trying to
        // track whether a write actually happened on this particular visit.
        BackHandler {
            if (currentDrilldown == FleetDrilldown.FULL_SCHEDULE || currentDrilldown == FleetDrilldown.ITEM_DETAIL) reloadKey++
            drilldown = backTarget
        }
        when (currentDrilldown) {
            FleetDrilldown.UPLINK -> TelemetryScreen(onBack = { drilldown = null })
            FleetDrilldown.MAINTENANCE -> MaintenanceDrilldownScreen(
                dueRows = fullState.dueRows,
                unknownCount = fullState.maintenanceUnknownCount,
                recapCount = fullState.recapCount,
                // The RECAPS row's inline strip (quant-viz ticket 12) reads the
                // SAME monthlyRecaps list recapCount is .size of - no second query.
                monthlyRecaps = fullState.monthlyRecaps,
                oilAnalysisCount = fullState.oilAnalyses.size,
                onOpenUnknown = {
                    fullScheduleFilterUnknown = true
                    drilldown = FleetDrilldown.FULL_SCHEDULE
                },
                onOpenFullSchedule = {
                    fullScheduleFilterUnknown = false
                    drilldown = FleetDrilldown.FULL_SCHEDULE
                },
                onOpenRecaps = { drilldown = FleetDrilldown.RECAPS },
                onOpenOilAnalysis = { drilldown = FleetDrilldown.OIL },
                onBack = { drilldown = null },
            )
            FleetDrilldown.FULL_SCHEDULE -> FullScheduleScreen(
                items = fullState.maintenanceItems,
                currentMileage = fullState.currentMileageRaw,
                odometerUnset = fullState.odometerUnset,
                now = System.currentTimeMillis(),
                filterUnknownOnly = fullScheduleFilterUnknown,
                onOpenItem = { serviceName ->
                    itemDetailTarget = serviceName
                    itemDetailIsAdd = false
                    drilldown = FleetDrilldown.ITEM_DETAIL
                },
                onAddItem = {
                    itemDetailTarget = null
                    itemDetailIsAdd = true
                    drilldown = FleetDrilldown.ITEM_DETAIL
                },
                onConfirmAll = { items -> writeConfirmAll(context, fullState.vehicleId, items) },
                onBack = { reloadKey++; drilldown = FleetDrilldown.MAINTENANCE },
            )
            FleetDrilldown.ITEM_DETAIL -> ItemDetailScreen(
                // Looked up fresh out of fullState rather than carried by value from the tap that
                // opened this screen - fullState only actually refreshes on the NEXT reloadKey bump
                // (i.e. after leaving), so within one visit this is a stable snapshot the same way
                // every other drilldown here reads fullState once; the ADD case (itemDetailIsAdd)
                // never had a row to look up in the first place.
                item = if (itemDetailIsAdd) null else fullState.maintenanceItems.firstOrNull { it.serviceName == itemDetailTarget },
                // Filtered here, in Kotlin, off the ONE unfiltered load below - MaintenanceItemDao
                // has no per-name query and ticket 09's brief is explicit that no DAO method gets
                // added for this build, so "every ServiceRecord whose name matches this item" reads
                // the whole vehicle's history once and filters client-side rather than adding one.
                serviceHistory = fullState.allServiceRecords.filter { it.serviceName == itemDetailTarget },
                checkDuplicate = { typed -> VehicleController.looksLikeExistingItem(typed, fullState.maintenanceItems.map { it.serviceName }) },
                onSetInterval = { serviceName, miles, months -> writeSetInterval(context, fullState.vehicleId, serviceName, miles, months) },
                onSetAnchor = { serviceName, mode, mileage, date -> writeSetAnchor(context, fullState.vehicleId, serviceName, mode, mileage, date) },
                onDelete = { serviceName -> writeDeleteItem(context, fullState.vehicleId, serviceName) },
                onAddItem = { name, miles, months, mode, mileage, date -> writeAddItem(context, fullState.vehicleId, name, miles, months, mode, mileage, date) },
                onBack = { reloadKey++; drilldown = FleetDrilldown.FULL_SCHEDULE },
            )
            FleetDrilldown.RECAPS -> RecapDrilldownScreen(
                recapsNewestFirst = fullState.monthlyRecaps,
                yearlyWrapped = fullState.yearlyWrapped,
                onBack = { drilldown = FleetDrilldown.MAINTENANCE },
            )
            FleetDrilldown.OIL -> OilAnalysisDrilldownScreen(
                analysesNewestFirst = fullState.oilAnalyses,
                onBack = { drilldown = FleetDrilldown.MAINTENANCE },
            )
            FleetDrilldown.DRIVES -> DriveHistoryDrilldownScreen(
                logsNewestFirst = fullState.recentDriveLogs,
                onBack = { drilldown = null },
            )
            // Closing ADAPTER re-keys the load above: selecting a dongle in
            // there writes the registry, which this screen only reads on load.
            FleetDrilldown.ADAPTER -> ObdDeviceScreen(
                onBack = {
                    drilldown = null
                    reloadKey++
                },
            )
            FleetDrilldown.SPECS -> VehicleSpecsScreen(onBack = { drilldown = null })
        }
        return
    }

    FleetContent(
        state = fullState,
        onOpenPlaces = onOpenPlaces,
        onOpenCars = onOpenCars,
        onOpenUplink = { drilldown = FleetDrilldown.UPLINK },
        onOpenMaintenance = { drilldown = FleetDrilldown.MAINTENANCE },
        onOpenDrives = { drilldown = FleetDrilldown.DRIVES },
        onOpenDrivingMode = onOpenDrivingMode,
        onOpenAdapter = { drilldown = FleetDrilldown.ADAPTER },
        onOpenSpecs = { drilldown = FleetDrilldown.SPECS },
        onSetOdometer = { showOdometerDialog = true },
        onSweepActiveChanged = onSweepActiveChanged,
    )

    // Ticket 10's manual-entry control (see CarsPane's own doc). Rendered as a sibling of
    // FleetContent above rather than nested inside it - a Dialog is its own window regardless of
    // where in the tree it's emitted, and keeping it here means it can call VehicleController and
    // bump reloadKey directly, the same state-holder privileges every other write on this screen
    // already has, without threading a suspend write callback down through FleetContent/
    // FleetListing/CarsPane (which the split's own doc says stay plain UI + callbacks).
    if (showOdometerDialog) {
        SetOdometerDialog(
            currentValueText = fullState.mileageValueText,
            currentCaveatText = fullState.mileageCaveatText,
            onDismiss = { showOdometerDialog = false },
            onSubmit = { miles ->
                val outcome = VehicleController.setOdometer(context, miles, fullState.vehicleId)
                if (outcome.success) reloadKey++
                outcome
            },
        )
    }
}

/** How many recent daily logs the DRIVES panel/drilldown pulls - a sparkline needs more than a headline figure, but no reason to load the whole table for a panel-height chart. */
private const val DRIVE_HISTORY_WINDOW = 14

/** Plain UI: [state] plus callbacks, no controller/DB reference - see the file doc comment. */
@Composable
fun FleetContent(
    state: FleetUiState,
    onOpenPlaces: () -> Unit,
    onOpenCars: () -> Unit,
    onOpenUplink: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDrives: () -> Unit,
    onOpenDrivingMode: () -> Unit = {},
    onOpenAdapter: () -> Unit = {},
    onOpenSpecs: () -> Unit = {},
    onSetOdometer: () -> Unit = {},
    onSweepActiveChanged: (Boolean) -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("FLEET", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            }

            if (state.loading) {
                Text("LOADING...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
            } else {
                FleetListing(state, onOpenPlaces, onOpenCars, onOpenUplink, onOpenMaintenance, onOpenDrives, onOpenDrivingMode, onOpenAdapter, onOpenSpecs, onSetOdometer, onSweepActiveChanged)
            }
        }
    }
}

@Composable
private fun FleetListing(
    state: FleetUiState,
    onOpenPlaces: () -> Unit,
    onOpenCars: () -> Unit,
    onOpenUplink: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDrives: () -> Unit,
    onOpenDrivingMode: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenSpecs: () -> Unit,
    onSetOdometer: () -> Unit,
    onSweepActiveChanged: (Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // ------------------------------------------------------------ UPLINK (leads, always)
        item(key = "uplink-pane") {
            UplinkPane(state, onOpenDrivingMode, onSweepActiveChanged, modifier = Modifier.clickable(onClick = onOpenUplink))
        }

        // ------------------------------------------------------ MAINTENANCE / DRIVES (HALF tiles)
        // Mission-control ticket 16's FLEET build: both panels drop from FULL to HALF per ticket
        // 12's inventory, sharing one row via EqualHeightRow - same shell HOME's own BIO/CRED/
        // FLEET/LOG row and BodyScreen's INTAKE/SLEEP row both use. UPLINK stays FULL above (the
        // surface's hero) and CARS stays FULL below (a roster is rows) - ticket 12's own reasoning.
        // top=8dp matches TodayScreen's first tile row, which sits in the identical position
        // (directly under a FULL hero pane, no Spacer between them) - the tile's own DeckPane
        // already contributes its usual 8dp label-pill pad on top of this, and that combination is
        // what HOME's shipped, on-device-checked spacing already uses in this exact slot.
        item(key = "tile-row-maintenance-drives") {
            val maintenanceTile = buildFleetTile(state.dueRows, state.maintenanceUnknownCount)
            val drivesTile = buildDrivesTile(state.driveSummary, state.recentDriveLogs)
            EqualHeightRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalGap = 9.dp) {
                HalfTile(
                    header = "Maintenance",
                    hero = maintenanceTile.hero,
                    caption = maintenanceTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenMaintenance),
                )
                HalfTile(
                    header = "Drives",
                    hero = drivesTile.hero,
                    caption = drivesTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenDrives),
                ) {
                    // MPG only, not the panel's old second (miles) sparkline - a HALF tile has room
                    // for one small chart, and MPG is the trend a driver actually watches; DRIVES'
                    // own drilldown (unchanged by this ticket) still carries both series in full.
                    if (state.mpgSparkline.any { it != null }) {
                        DeckSparkline(state.mpgSparkline, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }

        // ------------------------------------------------------------ CARS row
        item(key = "cars-pane") {
            CarsPane(state, onOpenCars, onOpenPlaces, onOpenAdapter, onOpenSpecs, onSetOdometer)
        }

        // ------------------------------------------------------------ GOALS (ticket 19)
        // FLEET aspect key, matching com.kevin.legion.advisor.playbooks.FleetPlaybook's own key.
        // See GoalsPanel's own doc comment for why it is self-contained rather than folded into
        // FleetUiState.
        item(key = "goals-pane") {
            com.kevin.legion.ui.goals.GoalsPanel(aspect = "fleet")
        }
        item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * UPLINK: live/last-known readouts, stored faults, and the ticket-20 drive-mode entry point.
 * Ticket 09 answer §2's `UPLINK // NO LINK` wording is spelled out literally in the opening line
 * rather than folded into [DeckPane]'s `headerAccent` slot - that slot is reserved for a positive
 * status word (ticket 03: "a pane's accent clause is always... never a value"), and a disconnected
 * link is neither a value nor a green "good" state, so it belongs in plain content text instead.
 *
 * **Mission-control ticket 16 follow-up ("get FLEET's tile row above the fold"), two changes, both
 * driven by an on-device measurement that contradicted the ticket's own estimate:**
 *
 * 1. The three gauge readings (COOLANT/BATTERY/FUEL TRIM) are display-only - this whole pane is
 *    already the tap target ([FleetListing] wraps it in one outer `onOpenUplink` clickable) and no
 *    individual reading has ever carried its own - so [DeckRow]'s 48dp tappable-row floor was
 *    spending height these rows never needed. [DeckFeedRow] (22dp, never-tappable by construction,
 *    ticket 03's "a 22dp row cannot carry a 48dp target" finding) recovers 26dp per row, 78dp
 *    across the three gauges. `code` carries the raw PID (same PID-as-code shape
 *    [com.kevin.legion.ui.theme.ThemePreview]'s "Live PIDs" section already uses); staleness still
 *    bakes into the value text exactly as before (ticket 09 answer §4: "COOLANT 194°F · 3 DAYS
 *    AGO") - a bare number never reads as live.
 * 2. **The ticket's own "roughly 12dp short" estimate did not hold on the real phone with Kevin's
 *    real 6-DTC car**: measured with the gauge-row fix alone already applied (`uiautomator`-style
 *    pixel scan of an on-device screenshot, panel border to panel border, cross-checked against a
 *    scroll-and-remeasure of the same tile row to get its true un-clipped height), the MAINTENANCE/
 *    DRIVES tile row was still short by roughly 52dp, not 12 - the ticket's figure was measured
 *    against a lighter synthetic state than the STORED CODES overflow row and the DRIVE MODE row
 *    Kevin's actual data now renders. The ADAPTER and SPECS/VIN rows (48dp each, 96dp together)
 *    moved out to [CarsPane] closes that gap with margin to spare - they answer "what is this car
 *    and what is it paired to", which is CARS' own question, not UPLINK's telemetry stream. Both
 *    stay reachable, one tab-level pane over, not lost.
 *
 * **Mission-control ticket 07's uplink sweep now lives here** (answer §2: "FLEET only, and only
 * while OBD is connected" - the ONE surface in the whole deck with a genuinely live datum behind
 * it). [sweepActive] folds together every condition the answer names: [FleetUiState.connected]
 * (the load-bearing one - "an ambient element not tied to genuinely live data is decoration"),
 * [deckMotionEnabled] (answer §6/§7's single reduced-motion gate), and `alarmActive` (answer §5's
 * precedence, "alarm pulse > surface ambient > shell cursor" - **hardcoded `false` here because no
 * alarm state source exists anywhere in the app yet**, same gap [com.kevin.legion.ui.common
 * .StatusLine]'s own `alarmCount` doc already names; when a later ticket wires one, gating this
 * one `val` is the only change this file needs).
 *
 * The sweep itself is [UplinkSweep], drawn as a SIBLING overlay on top of [DeckPane] rather than
 * as a modifier passed into it: [DeckPane]'s own `Column` paints an opaque
 * `.background(MaterialTheme.colorScheme.surface)` fill, which would paint straight over anything
 * drawn behind it - so the wrapping [Box] here draws [DeckPane] FIRST and [UplinkSweep] LAST,
 * sized to match via `Modifier.matchParentSize()`, the only way for the sweep to land on top of
 * that fill instead of underneath it.
 *
 * `onSweepActiveChanged` fires on two separate lifecycles rather than one `DisposableEffect
 * (sweepActive)`: a `LaunchedEffect(sweepActive)` reports every value change while this pane stays
 * mounted, and a `DisposableEffect(Unit)` guarantees `false` fires once when [UplinkPane] itself
 * leaves composition - which is exactly what happens when ANY drilldown opens (including UPLINK's
 * own [TelemetryScreen], since [FleetScreen] replaces this whole panel list rather than merely
 * hiding it) or the driver navigates off FLEET entirely. Both cases genuinely stop the sweep (it
 * is not composed, so it is not animating), and the shell cursor must resume blinking rather than
 * stay solid for a pane that no longer exists.
 */
@Composable
private fun UplinkPane(
    state: FleetUiState,
    onOpenDrivingMode: () -> Unit,
    onSweepActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    // See this composable's own doc for why alarmActive is hardcoded rather than read from a
    // source that does not exist yet.
    val alarmActive = false
    val sweepActive = state.connected && deckMotionEnabled() && !alarmActive
    LaunchedEffect(sweepActive) { onSweepActiveChanged(sweepActive) }
    DisposableEffect(Unit) { onDispose { onSweepActiveChanged(false) } }

    Box(modifier.fillMaxWidth()) {
    DeckPane(header = "Uplink") {
        Text(
            if (state.connected) "// LIVE" else "// NO LINK",
            style = LegionType.stamp,
            color = if (state.connected) sem.credit else sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (!state.connected) {
            Text(
                "No OBD adapter connected. Every reading below is the last one seen.",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        if (state.liveRows.isEmpty()) {
            Text(
                "No readings recorded yet. Connect an OBD adapter to start.",
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            state.liveRows.forEach { row -> DeckFeedRow(code = row.pid, name = row.label, value = "${row.value} · ${row.sub}") }
        }
        if (state.faults.isNotEmpty()) {
            Text(
                "STORED CODES",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // Ticket 16 (Kevin's call): capped at 2 with a worded overflow row - an unbounded
            // list here (6 DTCs on Kevin's real car) pushed MAINTENANCE/DRIVES/CARS below the
            // fold. Same "no faults drilldown exists" tap-through as before the cap: neither the
            // capped rows nor the overflow row carry their own clickable, so both still resolve
            // to UplinkPane's own outer Modifier.clickable(onOpenUplink) from FleetListing - the
            // same place every STORED CODES row already tapped to.
            val cappedFaults = capFaultRows(state.faults)
            cappedFaults.visible.forEach { (fault, description) -> FaultRowView(fault, description) }
            if (cappedFaults.overflowCount > 0) {
                Text(
                    "AND ${cappedFaults.overflowCount} MORE",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        // Manual override (Kevin, 2026-08-08): the row is ALWAYS shown, not
        // gated on the link - driving mode is enterable dongle or no dongle.
        // The wording still tells the truth about which case this is.
        DriveModeOfferRow(onOpenDrivingMode, linkUp = state.connected)
    }
    // Drawn LAST (see this composable's own doc): paints over DeckPane's opaque surface fill,
    // matched to its measured size via matchParentSize rather than any width/height of its own -
    // sizing the sweep off ANOTHER composable's layout result, never animating its own, is part of
    // the "amplitude is alpha and translation only" discipline ticket 07 answer §3/§4 requires.
    UplinkSweep(active = sweepActive, modifier = Modifier.matchParentSize())
    }
}

/** Ticket 07 answer §3's floor is "period >= 4 seconds" - picked comfortably above it, not a round number, so the sweep reads as a slow instrument cycle rather than a metronome tick. */
private const val UPLINK_SWEEP_PERIOD_MS = 4400

/**
 * [UplinkPane]'s ambient element (mission-control ticket 07 answer §2) - a dim light band that
 * sweeps horizontally across the pane while [active], reading as "telemetry is actually arriving"
 * rather than decoration. [active] is computed entirely by the caller ([UplinkPane]); this leaf
 * never reads connection, motion, or alarm state itself.
 *
 * **Containment (ticket 07 answer §4, this element's named mechanism): a leaf [Canvas]** that
 * reads the animated [androidx.compose.runtime.State] ONLY inside its own draw lambda. No
 * composable above this one - not [UplinkPane], [FleetContent], [FleetListing]'s `LazyColumn`, or
 * [FleetScreen] - ever reads `phase`; this [Canvas] is the sole reader, so the sweep's tick
 * invalidates only this one leaf's draw pass, never a recomposition of the pane around it or
 * anything above it. Mirrors [com.kevin.legion.ui.common.StatusLine]'s
 * `graphicsLayer { alpha = cursorAlpha.value }` - the reference implementation ticket 07 answer §4
 * points at - one level down: a [Canvas] draw lambda in place of a `graphicsLayer` lambda, because
 * this element paints a moving band rather than toggling one layer's alpha, but the same discipline
 * (read the `State` at the LEAF, in the draw phase, nowhere else) applies unchanged.
 *
 * **Amplitude is alpha and translation only** (ticket 07 answer §3): the band's WIDTH is a fixed
 * fraction of whatever size this [Canvas] measures to - read once per draw call off
 * [androidx.compose.ui.graphics.drawscope.DrawScope.size], never itself animated - and only its
 * horizontal POSITION (`centerX`) and its `Brush`'s edge-fade ALPHA move frame to frame. No size,
 * bound, or layout parameter is ever touched, so this can never re-run layout, and [modifier] is
 * always `Modifier.matchParentSize()` from the caller rather than a size this composable picks for
 * itself - sizing off ANOTHER composable's already-resolved layout, never animating its own.
 *
 * The `InfiniteTransition` itself is constructed ONLY while [active] is true (an early `return`
 * when it is not, before any animation API is even touched) - the same pattern
 * [com.kevin.legion.ui.common.StatusLine]'s cursor and
 * [com.kevin.legion.ui.common.DeckControls.DeckTextField]'s focused caret both already use, so a
 * disconnected FLEET (or reduced motion, already folded into [active] by the caller) never drives
 * an idle animation clock at all - there is nothing left running to stop.
 */
@Composable
private fun UplinkSweep(active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    val sweepColor = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "uplink-sweep")
    val phase = transition.animateFloat(
        // Starts and ends just outside [0, 1] so the band fades fully in and out at the pane's own
        // edges rather than popping into view mid-frame - see the draw lambda below for how
        // [phase] maps to a pixel position.
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = UPLINK_SWEEP_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "uplink-sweep-phase",
    )
    Canvas(modifier) {
        // The ONLY read of `phase` anywhere in this file - deferred to the draw lambda, per this
        // composable's own containment doc.
        val centerX = phase.value * size.width
        val bandHalfWidth = size.width * 0.16f
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to sweepColor.copy(alpha = 0.14f),
                1f to Color.Transparent,
                startX = centerX - bandHalfWidth,
                endX = centerX + bandHalfWidth,
            ),
        )
    }
}

/**
 * The ticket-20 driving-mode entry point (ticket 11 answer §1: "the offer surfaces... the
 * UPLINK panel"; one tap enters). This row carries its OWN `clickable`, nested inside
 * [UplinkPane]'s outer one (which opens the telemetry drilldown) - Compose consumes a nested
 * clickable's tap before it reaches the parent's, so tapping this row enters driving mode
 * rather than the drilldown, the same "inner click wins" shape [CarsPane]'s `DeckRow`s already
 * rely on inside their own un-clickable [DeckPane].
 *
 * **Ticket 11 answer §1's second surface - an Alfred strip prompt on OBD-connect - is deferred,
 * not built here.** Traced: [com.kevin.legion.ui.assistant.AssistantStrip]'s `onTap` is
 * hardcoded to exactly two actions (start a Live turn, or open Settings when the mic is
 * ungranted) via [com.kevin.legion.ui.assistant.AssistantStripResolver.State] - there is no
 * generic "offer with its own navigation action" slot to hang a third tap target off, and
 * ticket 20's own scope note says explicitly: if the strip has no action-tap mechanism, "name
 * the strip-offer as a deferred item rather than rebuilding the strip." Rebuilding that
 * mechanism is out of this ticket's tight footprint; this row is the whole entry point until
 * a later ticket adds one.
 */
@Composable
private fun DriveModeOfferRow(onOpenDrivingMode: () -> Unit, linkUp: Boolean) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onOpenDrivingMode),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Manual override (Kevin, 2026-08-08): the row no longer implies a
        // live link. With one, the readouts will be live; without, the screen
        // shows last-known values age-worded - said here so entering without
        // a dongle is an informed choice, not a surprise (CLAUDE.md §4).
        Text(
            if (linkUp) "Driving mode available" else "Driving mode - manual, no link",
            style = LegionType.stamp,
            color = sem.faint,
        )
        DeckTag("DRIVE MODE", DeckTagStyle.INVERTED_GREEN)
    }
}

/**
 * MAINTENANCE and DRIVES used to be full-width panels here - `MaintenancePane` rendered every due
 * row inline (label/value/tag, sub-line, meter) and `DrivesPane` rendered the last-drive headline
 * plus two full sparklines (mpg, miles). Both composables are gone, not merely renamed - mission-
 * control ticket 16's FLEET build (ticket 12's inventory) drops both panels to HALF tiles sharing
 * one row in [FleetListing] above, via [buildFleetTile]/[buildDrivesTile]. The detail these two used
 * to show inline did not disappear: [MaintenanceDrilldownScreen] still carries the full due-row list
 * with tags/subs/meters, and [DriveHistoryDrilldownScreen]/the DRIVES tap-through still reach the
 * same raw history - only the ROOT panel's own shape changed, matching ticket 16's binding that
 * drilldowns are out of scope.
 */

/**
 * The CARS row: the active vehicle NAMED explicitly (ticket 09 answer §3: "a reading is never
 * attributed to the wrong car" - the exact bug the 2026-08-04 Midnight AI import caused, see
 * [com.kevin.legion.ui.fleet.CarRows.kt]'s file doc), the switcher summary, and the PLACES link
 * this screen has carried since before ticket 09 (ticket 07 resolution §5: "their content is
 * already written - only the hosting changes" - unchanged here, still reachable, never dropped).
 *
 * **ADAPTER and SPECS/VIN moved in from [UplinkPane] (mission-control ticket 16 follow-up, "get
 * FLEET's tile row above the fold").** Both rows answer "what is this car and what is it paired
 * to" - CARS' own question, not UPLINK's live-telemetry one - and moving them here was the 96dp
 * of headroom UPLINK needed once the gauge-row density change alone still measured ~52dp short of
 * the tile row on the real device (see [UplinkPane]'s doc for the numbers). Still reachable, still
 * one tap each, just relocated one pane down rather than dropped.
 *
 * **The odometer's manual-entry control lives here too (ticket 10,
 * `.scratch/fleet-maintenance/issues/10-odometer-truth-and-drift.md`), beside the reading it's
 * already showing** - the SET ODOMETER row below opens [SetOdometerDialog], the ONE non-voice
 * write path this ticket adds (`set_odometer`'s voice tool was the only caller before it). Ticket
 * 09's triage screen and ticket 14's future registration form are meant to reuse
 * [SetOdometerDialog] itself rather than re-implement the field/validation shape - not wired here,
 * out of this ticket's scope, but the control is built to be reused, not duplicated.
 */
@Composable
private fun CarsPane(
    state: FleetUiState,
    onOpenCars: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenSpecs: () -> Unit,
    onSetOdometer: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    DeckPane(header = "Cars") {
        DeckRow(
            label = state.vehicleLabel.ifBlank { "This car" },
            value = state.mileageValueText.ifBlank { "-" },
            modifier = Modifier.clickable(onClick = onOpenCars),
        )
        // The estimate caveat renders as its own line, never folded into the DeckRow value above -
        // see FleetUiState.mileageValueText's own doc for why. Blank (nothing rendered) means the
        // reading above IS the driver's own confirmed one - ticket 10's "renders bare" case - so
        // there is deliberately no row at all here rather than an empty one.
        if (state.mileageCaveatText.isNotBlank()) {
            Text(
                state.mileageCaveatText,
                style = LegionType.stamp,
                // sem.estimated (amber): CLAUDE.md §4 rule 5's own colour for "a value the source
                // document never stated" - the odometer between readings is exactly that.
                color = sem.estimated,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable(onClick = onOpenCars),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSetOdometer) {
                Text("SET ODOMETER", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            if (state.otherCarCount > 0) {
                "${state.otherCarCount} other car${if (state.otherCarCount == 1) "" else "s"} - " +
                    if (state.followingAdapter) "following the adapter" else "pinned on this phone"
            } else {
                "only car on the roster"
            },
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable(onClick = onOpenCars),
        )
        // The ADAPTER drilldown - the only way to reach a BLE dongle, which
        // never bonds and so can never be auto-detected off `bondedDevices`
        // (see ObdDeviceScreen's file doc).
        DeckRow(
            label = "Adapter",
            value = state.adapterLabel,
            modifier = Modifier.clickable(onClick = onOpenAdapter),
        )
        // SPECS sits under ADAPTER: both answer "what is this car", and the VIN
        // is read off the very dongle the row above selects. Never gated on the
        // link - stored specs are readable with no adapter present at all.
        DeckRow(
            label = "Specs / VIN",
            value = if (state.vinOnFile) "ON FILE" else "NOT READ",
            modifier = Modifier.clickable(onClick = onOpenSpecs),
        )
        DeckRow(label = "Places", value = ">", modifier = Modifier.clickable(onClick = onOpenPlaces))
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Fleet: loading", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewFleetLoading() = LegionTheme {
    FleetContent(FleetUiState(loading = true), onOpenPlaces = {}, onOpenCars = {}, onOpenUplink = {}, onOpenMaintenance = {}, onOpenDrives = {})
}

@Preview(name = "Fleet: disconnected, last-seen readings", widthDp = 360, heightDp = 1400)
@Composable
private fun PreviewFleetDisconnected() = LegionTheme {
    FleetContent(
        FleetUiState(
            loading = false,
            vehicleLabel = "2014 MAZDA 3",
            mileageValueText = "138,204 mi",
            connected = false,
            liveRows = listOf(
                LiveRowView("Coolant", "88 C", "3 days ago"),
                LiveRowView("Battery", "12.4", "3 days ago"),
                LiveRowView("Fuel trim, long", "-2.3 %", "3 days ago"),
            ),
            dueRows = listOf(
                DueRowView("Oil Change", "OVERDUE", "every 5,000 mi - last at 132,400", overdue = true),
                DueRowView("Front Brake Pads", "in 1750 miles", "every 25,000 mi - last at 115,000", overdue = false),
                DueRowView("Cabin Air Filter", "in 240 days", "every 12 mo - last Dec 4, 2025", overdue = false),
            ),
            faults = listOf(
                FaultRow("P0442", 1_753_000_000_000L) to "EVAP small leak",
                FaultRow("P1101", 1_753_000_000_000L) to null,
            ),
            serviceHistoryCount = 12,
            buildSheetCount = 4,
            recapCount = 3,
            otherCarCount = 2,
            followingAdapter = false,
            driveSummary = DriveSummaryView("214 mi · 26.8 mpg", "3 drives · 1 day ago", hasData = true),
            mpgSparkline = listOf(24.1f, 25.0f, null, 26.2f, 27.0f, 26.8f),
        ),
        onOpenPlaces = {}, onOpenCars = {}, onOpenUplink = {}, onOpenMaintenance = {}, onOpenDrives = {},
    )
}

@Preview(name = "Fleet: connected, no history yet", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewFleetConnectedEmpty() = LegionTheme {
    FleetContent(
        FleetUiState(
            loading = false,
            vehicleLabel = "this car",
            mileageValueText = "",
            connected = true,
            liveRows = emptyList(),
            dueRows = emptyList(),
            faults = emptyList(),
        ),
        onOpenPlaces = {}, onOpenCars = {}, onOpenUplink = {}, onOpenMaintenance = {}, onOpenDrives = {},
    )
}

/**
 * `fleet/places` - absorbed from the deleted `SavedPlacesActivity`. Content
 * unchanged (list of tagged-place labels for the `show_saved_places` voice
 * tool); only the hosting changed, per ticket 07 resolution §5 ("their
 * content is already written - only the hosting changes"). Untouched by
 * ticket 09/18 - fleet's read-only screens above are additive, not a rewrite
 * of this sub-route.
 */
@Composable
fun SavedPlacesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var places by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        places = PlaceController.all(context).map { it.label }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            androidx.compose.material3.TextButton(onClick = onBack) {
                Text("< Back")
            }
            Text(if (places.isEmpty()) "No saved places yet" else places.joinToString("\n"))
        }
    }
}
