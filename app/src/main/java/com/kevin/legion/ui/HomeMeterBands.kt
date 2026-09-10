package com.kevin.legion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.groupAccountBalances
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.common.DeckMeter
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.GapSign
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.media.MediaMiniBar
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.voicenotes.RecordControlRow
import com.kevin.legion.ui.world.AreaCard
import com.kevin.legion.ui.world.NewsDigestCard
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.voice.VoiceNoteController
import com.kevin.legion.voice.VoiceNoteStartResult
import com.kevin.legion.weather.WeatherController
import kotlinx.coroutines.launch

/**
 * The meters, the world and the rows to ASK/media - **rehomed here from `ui/MetersScreen.kt`
 * (one-home tickets 01/02, 2026-09-10)** once that screen folded into HOME rather than staying a
 * second tab (`.scratch/one-home/issues/01-what-the-shell-is-without-meters.md`'s Resolution).
 * [CalendarScreen] renders the day first and this composable directly beneath it, in the band
 * order that resolution fixed: Needs you (breaches, conditional) -> the meters as rows (Body,
 * Money, Fleet, Lists, Recordings) -> the world (weather, [AreaCard], newsletters) -> rows for ASK
 * and the media mini-bar.
 *
 * **Every reading here is unchanged from `MetersScreen.kt` - only the file and the caller moved.**
 * The ASK pane itself did NOT move here: ticket 01's resolution gives it its own route
 * ([LegionRoute.ASK], see `ui/ask/AskScreen.kt`), so what HOME renders for it is a single
 * navigation row, never the five-picker panel.
 *
 * **A pane with nothing to say renders nothing, not an empty pane** (ticket 01's own rule, "Needs
 * you" was always this way). Body/Money/Fleet/Lists/Recordings always have something to render (a
 * reading or an honest absence like "NO BUDGET"), so only "Needs you" is conditional here, same as
 * it always was on `MetersScreen.kt`.
 */
data class MetersUiState(
    val loading: Boolean = true,
    val mealGap: DailyMealGap = DailyMealGap.NotLogged,
    val hasMealTarget: Boolean = false,
    val budget: BudgetVsActual? = null,
    val ledgerBalances: List<AccountBalance> = emptyList(),
    val nominatedAccountId: String? = null,
    val maintenanceRows: List<DueRowView> = emptyList(),
    val maintenanceUnknownCount: Int = 0,
    val checklistCount: Int = 0,
    val voiceNotesCount: Int = 0,
    val weather: WeatherController.WeatherInfo? = null,
    val nowMs: Long = System.currentTimeMillis(),
)

@Composable
fun HomeMeterBands(
    state: MetersUiState,
    onOpenBody: () -> Unit,
    onOpenMoney: () -> Unit,
    onOpenFleet: () -> Unit,
    onOpenPantry: () -> Unit,
    onOpenAsk: () -> Unit,
    onOpenMedia: () -> Unit = {},
    onOpenVoiceNotes: () -> Unit = {},
    onOpenChecklists: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    val connectionState by ObdBluetoothManager.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (state.loading) {
        return
    }

    // ---------------------------------------------------------------- NEEDS YOU
    // Only ever present when something is actually breaching - never a reassuring "all clear"
    // placeholder (ticket 01's own rule: "if nothing breaches, that pane is absent entirely").
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
        // Absence-vs-data: "NOT LOGGED"/"NO TARGET" is not a reading, so it renders muted rather
        // than the same mint every real calorie count gets.
        val intakeIsAbsence = state.mealGap == DailyMealGap.NotLogged
        DeckRow(
            label = "Calories today",
            value = intakeTile.hero,
            valueColor = if (intakeIsAbsence) sem.ghost else null,
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

            // The uncategorised-excluded disclosure (CLAUDE.md §4 rule 5).
            moneyUncategorizedSentence(budget)?.let { sentence ->
                Text(
                    sentence,
                    style = LegionType.stamp,
                    color = sem.estimated,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            // The nominated account's own balance. Rendered only when a nomination exists at all.
            if (!state.nominatedAccountId.isNullOrBlank()) {
                val balanceLine = buildCredBalanceLine(groupAccountBalances(state.ledgerBalances), state.nominatedAccountId)
                if (balanceLine.isAdvisory) {
                    Text(
                        balanceLine.primary,
                        style = LegionType.stamp,
                        color = sem.estimated,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                    if (balanceLine.secondary != null) {
                        Text(
                            balanceLine.secondary,
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                } else {
                    DeckRow(label = balanceLine.secondary ?: "balance", value = balanceLine.primary, modifier = Modifier.clickable(onClick = onOpenMoney))
                }
            }

            // GROCERIES - one category line inside the same BudgetVsActual.
            val groceriesLine = budget.lines.firstOrNull { it.category == "Groceries" }
            if (groceriesLine == null) {
                DeckRow(
                    label = "Groceries",
                    value = "NO BUDGET",
                    valueColor = sem.ghost,
                    modifier = Modifier.clickable(onClick = onOpenPantry),
                )
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
                    value = groceriesHeroValue(groceriesLine, budget.entity.currency),
                    tag = if (over) { { DeckTag("OVER", DeckTagStyle.INVERTED_AMBER) } } else null,
                    modifier = Modifier.clickable(onClick = onOpenPantry),
                )
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
        val everyItemUnknown = state.maintenanceRows.isEmpty() && state.maintenanceUnknownCount > 0
        val maintenanceHero = if (everyItemUnknown) "UNKNOWN" else fleetTile.hero
        val maintenanceHeroColor = when {
            overdueCount > 0 -> sem.chromeText
            everyItemUnknown || fleetTile.hero == "NO LINK" -> sem.ghost
            else -> null
        }
        DeckRow(
            label = "Maintenance",
            value = maintenanceHero,
            valueColor = maintenanceHeroColor,
            tag = if (overdueCount > 0) { { DeckTag("OVERDUE", DeckTagStyle.INVERTED_AMBER) } } else null,
            modifier = Modifier.clickable(onClick = onOpenFleet),
        )
        Text(
            fleetTile.caption,
            style = if (state.maintenanceUnknownCount > 0) LegionType.amount else LegionType.stamp,
            color = if (state.maintenanceUnknownCount > 0) sem.estimated else sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        val obdValue = when (connectionState) {
            ObdBluetoothManager.ConnectionState.CONNECTED -> "CONNECTED"
            ObdBluetoothManager.ConnectionState.CONNECTING -> "CONNECTING"
            ObdBluetoothManager.ConnectionState.ERROR -> "ERROR"
            ObdBluetoothManager.ConnectionState.DISCONNECTED -> "DISCONNECTED"
        }
        val obdTag: (@Composable RowScope.() -> Unit)? = when (connectionState) {
            ObdBluetoothManager.ConnectionState.CONNECTED -> { { DeckTag("LINKED", DeckTagStyle.INVERTED_GREEN) } }
            ObdBluetoothManager.ConnectionState.CONNECTING -> { { DeckTag("LINKING", DeckTagStyle.INVERTED_AMBER) } }
            ObdBluetoothManager.ConnectionState.ERROR -> { { DeckTag("LINK ERROR", DeckTagStyle.INVERTED_AMBER) } }
            ObdBluetoothManager.ConnectionState.DISCONNECTED -> null
        }
        val obdValueColor = when (connectionState) {
            ObdBluetoothManager.ConnectionState.DISCONNECTED -> sem.ghost
            ObdBluetoothManager.ConnectionState.ERROR -> sem.chromeText
            ObdBluetoothManager.ConnectionState.CONNECTING -> sem.estimated
            ObdBluetoothManager.ConnectionState.CONNECTED -> null
        }
        DeckRow(
            label = "OBD link",
            value = obdValue,
            valueColor = obdValueColor,
            tag = obdTag,
            modifier = Modifier.clickable(onClick = onOpenFleet),
        )
    }

    // ---------------------------------------------------------------- LISTS
    DeckPane(header = "Lists", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        DeckRow(
            label = "Checklists",
            value = "${state.checklistCount} lists",
            modifier = Modifier.clickable(onClick = onOpenChecklists),
        )
    }

    // ---------------------------------------------------------------- RECORDINGS
    DeckPane(header = "Recordings", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        val recordingState by VoiceNoteController.recordingState(context).collectAsStateWithLifecycle()
        var startRefusalHere by remember { mutableStateOf<String?>(null) }
        DeckRow(
            label = "Recordings",
            value = "${state.voiceNotesCount} saved",
            modifier = Modifier.clickable(onClick = onOpenVoiceNotes),
        )
        RecordControlRow(
            state = recordingState,
            onStart = {
                scope.launch {
                    when (val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO)) {
                        is VoiceNoteStartResult.Started -> {
                            startRefusalHere = null
                        }
                        is VoiceNoteStartResult.Refused -> {
                            startRefusalHere = started.reason
                        }
                    }
                }
            },
            onStop = {
                scope.launch {
                    // Never claims the note is ready, only that it saved and is transcribing.
                    VoiceNoteController.stop(context)
                }
            },
        )
        startRefusalHere?.let { reason ->
            Text(
                reason,
                style = LegionType.stamp,
                color = sem.estimated,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }

    // ---------------------------------------------------------------- WEATHER / AREA
    Text(
        weatherLine(state.weather),
        style = LegionType.stamp,
        color = sem.faint,
        modifier = Modifier.padding(top = 9.dp, start = 12.dp, end = 12.dp, bottom = 2.dp),
    )
    AreaCard(modifier = Modifier.padding(horizontal = 12.dp))

    // ---------------------------------------------------------------- NEWSLETTERS
    NewsDigestCard(modifier = Modifier.padding(start = 12.dp, top = 9.dp, end = 12.dp))

    // ---------------------------------------------------------------- ASK
    // ADR 0035's hands path, given its own route (ticket 01's resolution) - a single navigation
    // row here, never the five-picker panel (see ui/ask/AskScreen.kt).
    DeckPane(header = "Ask", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        DeckRow(
            label = "Ask a question",
            value = "open",
            modifier = Modifier.clickable(onClick = onOpenAsk),
        )
    }

    // ---------------------------------------------------------------- MEDIA
    // Renders nothing when nothing is playing (MediaMiniBar's own early return) - PINNED LAST.
    MediaMiniBar(onOpenMedia = onOpenMedia)
}
