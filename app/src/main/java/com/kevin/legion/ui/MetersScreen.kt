package com.kevin.legion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.grocery.GroceryController
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MealController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.common.DeckMeter
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.GapSign
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.fleet.buildDueRows
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import java.time.YearMonth

/**
 * The third tab ("C", [LegionRoute.METERS]'s own doc comment - Kevin, verbatim: "C as another tab.
 * retire the bottom headers like cred fleet etc. those we tap through from view C the meters.") -
 * now the ONLY hand path to Body, Money, Fleet, Notes and Pantry (mission-control's six bottom tabs
 * are gone; CALENDAR/METERS/SETTINGS is what is left). Every meter here is both a READING (the same
 * pure builders `ui/TodayGapResolvers.kt` already computes and unit-tests for HOME/BIO/FLEET) and a
 * DOOR - tapping it opens the full screen that owns the underlying data, exactly the "every home
 * pane taps through to its module" convention [TodayScreen] already established.
 *
 * **No new computation lands here beyond breach detection.** [buildMeterBreaches] is the one new
 * pure function this file adds - see its own doc comment for why "over budget" / "overdue" have to
 * be decided somewhere, and why here rather than inside an existing builder that was never asked to
 * answer that question. Every reading itself reuses [buildIntakeTile]/[buildFleetTile]/
 * [buildBudgetLineGapRowData] verbatim.
 *
 * Deck primitives only ([DeckPane]/[DeckMeter]/[DeckRow]/[DeckTag]/`DeckSectionRule` - CLAUDE.md's
 * "introduce no new visual language" read literally): no [com.kevin.legion.ui.common.GapRow], which
 * is the OLDER pre-cyberdeck row vocabulary mission-control ticket 16 already superseded on every
 * HOME/BIO/FLEET tile this screen otherwise mirrors.
 */
@Composable
fun MetersScreen(
    onOpenBody: () -> Unit,
    onOpenMoney: () -> Unit,
    onOpenFleet: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenPantry: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(MetersUiState()) }
    // Matches TodayScreen/BodyScreen's own reload-nonce convention for a screen with no live Flow
    // of its own to key a reload off - bumped nowhere yet (nothing on this screen currently writes
    // back into its own reads), kept for parity with every other suspend-one-shot screen in the app.
    var reloadNonce by remember { mutableStateOf(0) }

    LaunchedEffect(reloadNonce) {
        val now = System.currentTimeMillis()
        val db = CarDatabase.getDatabase(context)

        // INTAKE: identical pair of reads TodayScreen's own INTAKE half-tile makes - see that
        // file's LaunchedEffect for why hasMealTarget needs its own direct DAO read (NotLogged vs
        // NoTarget are two different empty states, never collapsed into one - CLAUDE.md's
        // empty-vs-unreadable discipline).
        val mealTarget = db.mealTargetDao().currentTarget(dayStartEpoch(now))
        val mealGap = MealController.dayGap(context, now)

        // MONEY/GROCERIES: the current month's budget-versus-actual, the exact figure
        // LedgerScreen's own budget section and TodayScreen's CRED tile both read.
        val budget = LedgerController.budgetVsActual(context, LedgerEntity.US, YearMonth.now())

        // MAINTENANCE: the active vehicle's schedule, same rows FleetScreen's own DUE block and
        // TodayScreen's FLEET tile build from.
        val vehicle = VehicleController.currentVehicle(context)
        val currentMileage = VehicleController.currentMileage(vehicle)
        val items = FleetEngineStore.getForVehicle(context, vehicle.obdMac)
        val maintenanceRows = buildDueRows(items, currentMileage, vehicle.odometerBaseline == 0, now)
        val maintenanceUnknownCount = items.count { VehicleController.isUnknown(it) }

        // LISTS: open counts off two genuinely different lists - NotesController's own persistent
        // checklist and GroceryController's short-lived shopping trip (`service/LiveToolbox.kt`'s
        // own manage_grocery/show_groceries_modal tool copy is explicit that the grocery trip is
        // "NOT the persistent list" - two structures, not one, so two separate reads here).
        val persistentOpenCount = NotesController.openItemCount(context)
        val groceriesOpenCount = GroceryController.items(context).count { !it.done }

        state = MetersUiState(
            loading = false,
            mealGap = mealGap,
            hasMealTarget = mealTarget != null,
            budget = budget,
            maintenanceRows = maintenanceRows,
            maintenanceUnknownCount = maintenanceUnknownCount,
            persistentOpenCount = persistentOpenCount,
            groceriesTripOpenCount = groceriesOpenCount,
            nowMs = now,
        )
    }

    MetersContent(state, onOpenBody, onOpenMoney, onOpenFleet, onOpenNotes, onOpenPantry)
}

/** One-shot suspend reads only - see [MetersScreen]'s own `LaunchedEffect`. [connectionState] is
 * deliberately NOT part of this snapshot: it is a live [kotlinx.coroutines.flow.StateFlow] read
 * straight from [ObdBluetoothManager] inside [MetersContent], the one meter on this screen that
 * genuinely changes without a reload, matching how [FleetScreen] already reads the same flow. */
data class MetersUiState(
    val loading: Boolean = true,
    val mealGap: DailyMealGap = DailyMealGap.NotLogged,
    val hasMealTarget: Boolean = false,
    val budget: BudgetVsActual? = null,
    val maintenanceRows: List<DueRowView> = emptyList(),
    val maintenanceUnknownCount: Int = 0,
    val persistentOpenCount: Int = 0,
    val groceriesTripOpenCount: Int = 0,
    val nowMs: Long = System.currentTimeMillis(),
)

/** Plain UI: [state] plus callbacks, no controller/DB reference - see [MetersScreen]'s file doc. */
@Composable
fun MetersContent(
    state: MetersUiState,
    onOpenBody: () -> Unit,
    onOpenMoney: () -> Unit,
    onOpenFleet: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenPantry: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val connectionState by ObdBluetoothManager.connectionState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "METERS",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )

        if (state.loading) {
            Text(
                "Loading...",
                style = LegionType.stamp,
                color = sem.ghost,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            return@Column
        }

        // ---------------------------------------------------------------- NEEDS YOU
        // Only ever present when something is actually breaching - never a reassuring "all clear"
        // placeholder (this file's own doc: "if nothing breaches, that pane is absent entirely").
        val breaches = buildMeterBreaches(state.budget, state.maintenanceRows)
        if (breaches.isNotEmpty()) {
            DeckPane(header = "Needs you", alarm = true, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                breaches.forEach { breach ->
                    val onClick = when (breach.target) {
                        MetersBreachTarget.MONEY -> onOpenMoney
                        MetersBreachTarget.MONEY_PANTRY -> onOpenPantry
                        MetersBreachTarget.FLEET -> onOpenFleet
                    }
                    DeckRow(label = breach.label, value = breach.reason, modifier = Modifier.clickable(onClick = onClick))
                }
            }
        }

        // ---------------------------------------------------------------- BODY
        DeckPane(header = "Body", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val intakeTile = buildIntakeTile(state.mealGap, state.hasMealTarget)
            DeckRow(
                label = "Calories today",
                value = intakeTile.hero,
                modifier = Modifier.clickable(onClick = onOpenBody),
            )
            Text(intakeTile.caption, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp))
            val loggedGap = state.mealGap as? DailyMealGap.Logged
            if (loggedGap != null && loggedGap.gap.target.caloriesKcal > 0) {
                DeckMeter(
                    fraction = loggedGap.gap.actual.caloriesKcal.toFloat() / loggedGap.gap.target.caloriesKcal.toFloat(),
                    paceFraction = dayElapsedFraction(state.nowMs),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
                // CLAUDE.md §4 rule 5: a meal's macros are an LLM estimate from a product name, never
                // a measurement - a disclosure the source document (there isn't one) cannot state, so
                // it is stated in words here, visible on the meter itself, never behind an expander.
                if (loggedGap.gap.tier == TrustTier.REPORTED) {
                    Text(
                        "estimated from what you told me, not measured",
                        style = LegionType.stamp,
                        color = sem.estimated,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- MONEY
        DeckPane(header = "Money", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val budget = state.budget
            if (budget == null) {
                DeckRow(label = "Spent this month", value = "...", modifier = Modifier.clickable(onClick = onOpenMoney))
            } else {
                val targetCents = budget.lines.sumOf { it.gap.target }
                val spentCents = budget.spentCents
                DeckRow(
                    label = "Spent this month",
                    value = formatMoney(spentCents, budget.entity.currency),
                    tag = if (targetCents > 0 && spentCents > targetCents) {
                        { DeckTag("OVER", DeckTagStyle.INVERTED_AMBER) }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable(onClick = onOpenMoney),
                )
                Text(
                    if (targetCents > 0) {
                        "${formatMoney(spentCents, budget.entity.currency)} of ${formatMoney(targetCents, budget.entity.currency)} budgeted"
                    } else {
                        "no budget set"
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (targetCents > 0) {
                    DeckMeter(
                        fraction = spentCents.toFloat() / targetCents.toFloat(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }

                // GROCERIES - one category line inside the same BudgetVsActual, seeded in
                // CategorySeed.kt - there is no separate grocery budget to read.
                val groceriesLine = budget.lines.firstOrNull { it.category == "Groceries" }
                if (groceriesLine == null) {
                    DeckRow(label = "Groceries", value = "NO BUDGET", modifier = Modifier.clickable(onClick = onOpenPantry))
                    Text(
                        "no groceries budget set this month",
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    val row = buildBudgetLineGapRowData(groceriesLine, budget.entity.currency)
                    val over = row.sign == GapSign.BAD
                    DeckRow(
                        label = "Groceries",
                        value = row.gapValue,
                        tag = if (over) { { DeckTag("OVER", DeckTagStyle.INVERTED_AMBER) } } else null,
                        modifier = Modifier.clickable(onClick = onOpenPantry),
                    )
                    // At most one qualification line (CLAUDE.md's "a disclosure is never furniture"):
                    // a provisional/guessed-category caveat, when there is one, always outranks the
                    // plain actual-of-target restatement below it - the numbers are already visible
                    // on the row and the bar, the caveat is the one thing that is not.
                    Text(
                        row.tierNote ?: row.actualOverTarget,
                        style = LegionType.stamp,
                        color = if (row.tierNote != null) sem.estimated else sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    if (groceriesLine.gap.target > 0L) {
                        DeckMeter(
                            fraction = groceriesLine.gap.actual.toFloat() / groceriesLine.gap.target.toFloat(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- FLEET
        DeckPane(header = "Fleet", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val fleetTile = buildFleetTile(state.maintenanceRows, state.maintenanceUnknownCount)
            val overdueCount = state.maintenanceRows.count { it.overdue }
            DeckRow(
                label = "Maintenance",
                value = fleetTile.hero,
                tag = if (overdueCount > 0) { { DeckTag("OVERDUE", DeckTagStyle.INVERTED_AMBER) } } else null,
                modifier = Modifier.clickable(onClick = onOpenFleet),
            )
            Text(fleetTile.caption, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp))

            val obdValue = when (connectionState) {
                ObdBluetoothManager.ConnectionState.CONNECTED -> "CONNECTED"
                ObdBluetoothManager.ConnectionState.CONNECTING -> "CONNECTING"
                ObdBluetoothManager.ConnectionState.ERROR -> "ERROR"
                ObdBluetoothManager.ConnectionState.DISCONNECTED -> "DISCONNECTED"
            }
            // DISCONNECTED is the ordinary state for a phone that is not currently in the car - not
            // a fault, so it renders silent, matching DeckTagStyle's own "silence is the strong
            // state" ladder. CONNECTED gets the armed/ok tag; CONNECTING/ERROR read as advisories on
            // the LINK itself, never QuarantineTag - that red is reserved for a failed reconciliation
            // gate or a crisis state (CLAUDE.md §7), and a Bluetooth dongle dropping is neither.
            val obdTag: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = when (connectionState) {
                ObdBluetoothManager.ConnectionState.CONNECTED -> { { DeckTag("LINKED", DeckTagStyle.INVERTED_GREEN) } }
                ObdBluetoothManager.ConnectionState.CONNECTING -> { { DeckTag("LINKING", DeckTagStyle.INVERTED_AMBER) } }
                ObdBluetoothManager.ConnectionState.ERROR -> { { DeckTag("LINK ERROR", DeckTagStyle.INVERTED_AMBER) } }
                ObdBluetoothManager.ConnectionState.DISCONNECTED -> null
            }
            DeckRow(label = "OBD link", value = obdValue, tag = obdTag, modifier = Modifier.clickable(onClick = onOpenFleet))
        }

        // ---------------------------------------------------------------- LISTS
        DeckPane(header = "Lists", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            DeckRow(
                label = "Persistent list",
                value = "${state.persistentOpenCount} open",
                modifier = Modifier.clickable(onClick = onOpenNotes),
            )
            DeckRow(
                label = "Groceries trip",
                value = "${state.groceriesTripOpenCount} open",
                modifier = Modifier.clickable(onClick = onOpenPantry),
            )
        }
    }
}

// ------------------------------------------------------------- Needs-you breach detection (new)

/** Which callback a [MeterBreach] taps through to - see [MetersContent]'s own `when` for the real
 * navigation lambda each one resolves to. No [BODY]/[NOTES] member: neither Intake nor the Lists
 * pane currently has a breach condition this file defines (see [buildMeterBreaches]'s own doc for
 * exactly which three do), and inventing a target nothing ever returns would be dead code a later
 * change could silently miss wiring correctly. */
enum class MetersBreachTarget { MONEY, MONEY_PANTRY, FLEET }

/** One breaching meter, worded rather than coloured (CLAUDE.md §7's "never colour alone" applied to
 * this screen's own new pane) - [reason] is a full sentence fragment ready to sit in a [DeckRow]'s
 * value slot, e.g. "over budget by $42", never a bare boolean the row would have to re-word itself. */
data class MeterBreach(val label: String, val reason: String, val target: MetersBreachTarget)

/**
 * The new pure logic this ticket adds (everything else in [MetersContent] re-shapes an
 * already-existing builder). Three breach conditions, matching the three concrete types the brief
 * names ("overdue, over budget, behind target") that actually apply to this screen's five meters -
 * intake and the two list counts have no breach condition defined here, on purpose: CLAUDE.md's
 * "do not invent computation" reads onto breach detection as much as onto a meter's own reading, and
 * nothing in this ticket's brief describes what "breaching" would even mean for a calorie count or
 * an open-task count.
 *
 * **Money and Groceries both require a real, positive target before they can be "over" it** - the
 * same `target > 0` guard [BudgetLineRow]/this file's own [DeckMeter] calls already use, because a
 * month with no budget set is an empty state, not a breach (CLAUDE.md's empty-vs-unreadable
 * discipline again: "no budget set" and "over budget" are different facts about the same null gap).
 * **Money's own total deliberately excludes the Groceries line's own overage from double-reporting
 * as a SEPARATE breach reason** - it does not: [BudgetVsActual.spentCents] already sums every
 * category's actual, Groceries included, so a Groceries overage that also pushes the whole month
 * over is reported as ONE Money breach AND, separately, its own Groceries breach - two true facts
 * about two different totals, not one fact stated twice, matching how the Money and Groceries
 * [DeckPane] rows already render as two separate lines below.
 */
fun buildMeterBreaches(budget: BudgetVsActual?, maintenanceRows: List<DueRowView>): List<MeterBreach> {
    val breaches = mutableListOf<MeterBreach>()

    if (budget != null) {
        val targetCents = budget.lines.sumOf { it.gap.target }
        val spentCents = budget.spentCents
        if (targetCents > 0 && spentCents > targetCents) {
            val overCents = spentCents - targetCents
            breaches += MeterBreach(
                label = "Money",
                reason = "over budget by ${formatMoney(overCents, budget.entity.currency)}",
                target = MetersBreachTarget.MONEY,
            )
        }

        val groceriesLine = budget.lines.firstOrNull { it.category == "Groceries" }
        if (groceriesLine != null && groceriesLine.gap.target > 0 && groceriesLine.gap.gap < 0) {
            val overCents = -groceriesLine.gap.gap
            breaches += MeterBreach(
                label = "Groceries",
                reason = "over budget by ${formatMoney(overCents, budget.entity.currency)}",
                target = MetersBreachTarget.MONEY_PANTRY,
            )
        }
    }

    val overdueCount = maintenanceRows.count { it.overdue }
    if (overdueCount > 0) {
        breaches += MeterBreach(
            label = "Maintenance",
            reason = if (overdueCount == 1) "1 item overdue" else "$overdueCount items overdue",
            target = MetersBreachTarget.FLEET,
        )
    }

    return breaches
}
