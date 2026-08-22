package com.kevin.legion.ui.fleet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.BuildEntry
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckDialog
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.BuildSheetController
import com.kevin.legion.vehicle.VehicleController.WriteOutcome
import kotlinx.coroutines.launch

/**
 * The BUILD SHEET surface (ticket 07, `.scratch/command-center/issues/07-build-sheet-screen.md`) -
 * ADR 0035's hands path for `log_build_entry`/`list_build_history`/`get_spend`, none of which had
 * one: the store existed (`build_entries`, [BuildSheetController]), the voice tools existed, and
 * `ui/FleetScreen.kt` loaded `buildSheetCount` into state and rendered nothing with it.
 *
 * **Same split every other `ui/fleet/` screen file already uses**: display-only here, raw state in,
 * a suspend-lambda write out ([onAddEntry], wired in `ui/FleetScreen.kt`'s state holder to
 * `ui/fleet/BuildSheetWrites.kt`, which is the exact same [BuildSheetController.add] `log_build_entry`
 * reaches - never a second write path). The list ([entries]) reads through the same
 * [BuildSheetController.history]-shaped rows the voice `list_build_history` tool reads, and the
 * spend panel ([spendByCategory]/[totalSpend]) is [BuildSheetController.spendByCategory]/`totalSpend`
 * themselves, computed once in the state holder and handed down - the SAME numbers `get_spend`
 * reports, never a parallel sum recomputed here (that is exactly how two totals disagree).
 *
 * No photo affordance, by the same locked ruling `ui/FleetScreen.kt`'s own build-entry callers
 * respect: [BuildEntry.photoPath] was dropped at the pivot (CLAUDE.md §1, "fleet build/mod photos
 * retired... photo storage is pantry-ingestion-only") - the build sheet is text-only.
 */
@Composable
fun BuildSheetScreen(
    entries: List<BuildEntry>,
    spendByCategory: Map<String, Double>,
    totalSpend: Double,
    onAddEntry: suspend (title: String, type: String, costCents: Long?, vendor: String, notes: String) -> WriteOutcome,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()

    // Local mirror, same "an add made moments ago must be visible for the REST of this visit, not
    // just after leaving and re-entering" fix every other `ui/fleet/` list screen applies
    // (`FullScheduleScreen`/`ItemDetailScreen`/`ServiceHistoryScreen` all do this for the identical
    // reason) - `entries`/`spendByCategory`/`totalSpend` only refresh once `FleetScreen`'s own
    // reloadKey bumps, which happens on the way OUT of this screen, not while it is open.
    var currentEntries by remember(entries) { mutableStateOf(entries) }
    var currentSpendByCategory by remember(spendByCategory) { mutableStateOf(spendByCategory) }
    var currentTotalSpend by remember(totalSpend) { mutableStateOf(totalSpend) }

    var showAddDialog by remember { mutableStateOf(false) }

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
                "BUILD SHEET",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                DeckButton(text = "ADD ENTRY", onClick = { showAddDialog = true })
            }
            Hairline()
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "spend-panel") { BuildSheetSpendPanel(currentSpendByCategory, currentTotalSpend) }
                item(key = "spend-hairline") { Hairline() }
                item(key = "history-header") { SectionHeader("HISTORY", currentEntries.size.toString()) }
                if (currentEntries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing logged on the build sheet yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(currentEntries, key = { it.id }) { entry -> BuildEntryRow(entry) }
                }
                item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AddBuildEntryDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { title, type, costCents, vendor, notes -> onAddEntry(title, type, costCents, vendor, notes) },
            onAdded = { entry ->
                currentEntries = listOf(entry) + currentEntries
                val dollars = entry.cost ?: 0.0
                if (dollars > 0.0) {
                    currentSpendByCategory = currentSpendByCategory + (entry.type to (currentSpendByCategory[entry.type] ?: 0.0) + dollars)
                    currentTotalSpend += dollars
                }
                showAddDialog = false
            },
        )
    }
}

/**
 * Total plus a row per non-zero category - [BuildSheetController.TYPES] order, matching
 * [BuildSheetController.spendByCategory]'s own `linkedMapOf` insertion order (mod, part, repair,
 * consumable, other), so this reads the same order the `get_spend` voice tool's `byCategory` object
 * would enumerate. No currency is ever attached - [BuildSheetController]'s own store has never
 * recorded one (the same reason `getSpend`'s voice response emits `currency: null` with a spoken
 * note), so this panel states the bare dollar figure without a `$` currency claim beyond the plain
 * sign every other fleet-spend surface in this app already uses for an unspecified currency.
 */
@Composable
private fun BuildSheetSpendPanel(spendByCategory: Map<String, Double>, totalSpend: Double) {
    val sem = LocalLegionSemantics.current
    DeckPane(header = "Build Sheet Spend") {
        DeckRow(label = "Total Spent", value = formatDollars(totalSpend))
        if (spendByCategory.isEmpty()) {
            Text(
                "no costs logged yet",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        } else {
            spendByCategory.forEach { (type, amount) ->
                DeckRow(label = type.replaceFirstChar { it.uppercase() }, value = formatDollars(amount))
            }
        }
    }
}

/** "$1,234.56" - dollars, not cents ([BuildEntry.cost]/[BuildSheetController]'s own stored/return type, unchanged by this ticket). */
internal fun formatDollars(amount: Double): String {
    val whole = amount.toLong()
    val cents = Math.round((amount - whole) * 100.0).let { if (it < 0) -it else it }
    return "$${groupThousands(whole.toInt())}.${cents.toString().padStart(2, '0')}"
}

/**
 * One [BuildEntry] row - type, vendor, and mileage as a caption line, cost mono right-aligned same
 * as [ServiceHistoryRow]'s cost column, "no cost logged" in the faint advisory tone when
 * [BuildEntry.cost] is null (CLAUDE.md §4 rule 6's "never a bare blank standing in for an absent
 * fact", same posture that row already established).
 */
@Composable
private fun BuildEntryRow(entry: BuildEntry) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
            val caption = buildString {
                append(entry.type.replaceFirstChar { it.uppercase() })
                if (entry.vendor.isNotBlank()) append(" · ${entry.vendor}")
                append(" · ${shortDate(entry.date)}")
            }
            Text(caption, style = LegionType.stamp, color = sem.faint)
            if (entry.notes.isNotBlank()) {
                Text(entry.notes, style = LegionType.stamp, color = sem.faint)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                entry.cost?.let { formatDollars(it) } ?: "no cost logged",
                style = LegionType.stamp,
                color = if (entry.cost != null) MaterialTheme.colorScheme.onSurface else sem.faint,
            )
        }
    }
}

/**
 * ADD ENTRY's dialog - title, category (radio, [BuildSheetController.TYPES]), cost (optional,
 * parsed to cents first for precision then handed to [onAdd] as `Long?` - CLAUDE.md §4 rule 3 and
 * the same "a typo must never look like clearing a value" refusal [EditServiceRecordDialog]'s own
 * cost field already applies), vendor, notes. On a successful write, [onAdded] receives a local
 * [BuildEntry] mirror built from exactly what was typed (title/type/vendor/notes trimmed the same
 * way [BuildSheetController.add] itself trims them) so the list updates immediately without a
 * second query - the real row (with its real id/mileage/syncId) is picked up the next time
 * `FleetScreen` reloads on the way out, same "local mirror is provisional, the reload is truth"
 * shape every sibling screen in this package already uses.
 */
@Composable
private fun AddBuildEntryDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (title: String, type: String, costCents: Long?, vendor: String, notes: String) -> WriteOutcome,
    onAdded: (BuildEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var titleText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("mod") }
    var costText by remember { mutableStateOf("") }
    var vendorText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }

    DeckDialog(title = "ADD ENTRY", onDismissRequest = onDismiss) {
        if (statusText != null) {
            Text(statusText!!, style = LegionType.stamp, color = LocalLegionSemantics.current.faint, modifier = Modifier.padding(bottom = 8.dp))
        }
        DeckTextField(value = titleText, onValueChange = { titleText = it }, label = "Title")
        Spacer(Modifier.height(8.dp))
        SectionHeader("CATEGORY")
        Column(Modifier.selectableGroup()) {
            BuildSheetController.TYPES.forEach { t ->
                DeckRadio(selected = type == t, onClick = { type = t }, label = t.replaceFirstChar { it.uppercase() })
            }
        }
        Spacer(Modifier.height(8.dp))
        DeckTextField(
            value = costText,
            onValueChange = { costText = it },
            label = "Cost (dollars) - optional",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Spacer(Modifier.height(8.dp))
        DeckTextField(value = vendorText, onValueChange = { vendorText = it }, label = "Vendor - optional")
        Spacer(Modifier.height(8.dp))
        DeckTextField(value = notesText, onValueChange = { notesText = it }, label = "Notes - optional")
        Row(Modifier.padding(top = 12.dp)) {
            DeckButton(
                text = "SAVE",
                onClick = {
                    val trimmedTitle = titleText.trim()
                    if (trimmedTitle.isBlank()) {
                        statusText = "This needs a title before it can go on the build sheet."
                        return@DeckButton
                    }
                    val trimmedCost = costText.trim()
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
                    val vendorTrimmed = vendorText.trim()
                    val notesTrimmed = notesText.trim()
                    scope.launch {
                        val outcome = onAdd(trimmedTitle, type, costCents, vendorTrimmed, notesTrimmed)
                        statusText = outcome.message
                        if (outcome.success) {
                            onAdded(
                                BuildEntry(
                                    vehicleId = "",
                                    type = BuildSheetController.normalizeType(type),
                                    title = trimmedTitle,
                                    vendor = vendorTrimmed,
                                    cost = costCents?.let { it / 100.0 },
                                    date = System.currentTimeMillis(),
                                    notes = notesTrimmed,
                                ),
                            )
                        }
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Build sheet: empty", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewBuildSheetEmpty() = LegionTheme {
    BuildSheetScreen(entries = emptyList(), spendByCategory = emptyMap(), totalSpend = 0.0, onAddEntry = { _, _, _, _, _ -> WriteOutcome(true, "") }, onBack = {})
}

@Preview(name = "Build sheet: populated", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewBuildSheetPopulated() = LegionTheme {
    BuildSheetScreen(
        entries = listOf(
            BuildEntry(id = 1, vehicleId = "x", type = "mod", title = "BC Racing coilovers", vendor = "FCP Euro", cost = 899.0, date = System.currentTimeMillis(), notes = "front and rear"),
            BuildEntry(id = 2, vehicleId = "x", type = "consumable", title = "Oil filter", cost = null, date = System.currentTimeMillis()),
        ),
        spendByCategory = mapOf("mod" to 899.0),
        totalSpend = 899.0,
        onAddEntry = { _, _, _, _, _ -> WriteOutcome(true, "") },
        onBack = {},
    )
}
