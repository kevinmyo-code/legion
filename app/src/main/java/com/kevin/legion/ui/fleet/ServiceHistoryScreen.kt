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
import androidx.compose.foundation.layout.width
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
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckDialog
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.VehicleController.WriteOutcome
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Ticket 11's third surface (`.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`):
 * the full, unfiltered service history plus the fleet spend figures, reached from
 * [FullScheduleScreen] - see that screen's own header row for the entry point. Display-only, same
 * convention as every other file in this package: raw view state in, suspend-lambda writes out (see
 * `ui/fleet/ServiceHistoryWrites.kt`), no controller or DAO reference here.
 *
 * **"One list implementation, two entry points" (ticket 11 §3).** [ServiceHistoryRow] is the SAME
 * composable [ItemDetailScreen] uses for its own (read-only, per-item-filtered) service history
 * block - see that screen's call site. The two entry points differ only in [ServiceHistoryRow.showServiceName]
 * (redundant on ITEM DETAIL, where the screen's own header already names the service) and in
 * whether a tap opens the edit dialog at all: ticket 09's own doc comment on [ItemDetailScreen]
 * already commits that surface's history to READ-ONLY ("ticket 11 owns the full history screen and
 * cost") - edit and delete live HERE only, never on ITEM DETAIL, so a record can't be edited from
 * two different screens with two different code paths to keep in sync.
 */

/** How many records the year-group headers hold - reverse-chronological, one header per calendar year (device-local zone, matching [ServiceRecord.date]'s own capture convention - see [com.kevin.legion.vehicle.FleetSpendController.spendByYear]'s doc for why this is NOT [com.kevin.legion.util.documentDate]'s zone). */
internal fun groupServiceRecordsByYear(recordsNewestFirst: List<ServiceRecord>): List<Pair<Int, List<ServiceRecord>>> {
    val byYear = linkedMapOf<Int, MutableList<ServiceRecord>>()
    for (record in recordsNewestFirst) {
        // ServiceRecord.date widened to nullable at v46->v47 (engine retirement step 3) for an
        // ASSERTED anchor row - but this screen only ever renders OBSERVED rows (FleetEngineStore.
        // serviceRecordsForVehicle's own filter), which always carry a real date. `?: 0L` is a
        // type-satisfying fallback, never a value real data here hits.
        val year = Instant.ofEpochMilli(record.date ?: 0L).atZone(ZoneId.systemDefault()).year
        byYear.getOrPut(year) { mutableListOf() }.add(record)
    }
    // recordsNewestFirst is already newest-first, so linkedMapOf's insertion order already puts the
    // newest YEAR first too - re-sorting would be redundant, not a fix.
    return byYear.map { (year, records) -> year to records }
}

@Composable
fun ServiceHistoryScreen(
    recordsNewestFirst: List<ServiceRecord>,
    spend: FleetSpendView,
    onEditRecord: suspend (id: Long, mileage: Int, costCents: Long?) -> WriteOutcome,
    onDeleteRecord: suspend (id: Long) -> WriteOutcome,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current

    // A local mirror, same "the state holder only refreshes on the way out" fix
    // [FullScheduleScreen]/[ItemDetailScreen] both already apply - an edit or delete here must be
    // visible for the REST of this visit, not just after leaving and re-entering.
    var currentRecords by remember(recordsNewestFirst) { mutableStateOf(recordsNewestFirst) }
    var editingRecord by remember { mutableStateOf<ServiceRecord?>(null) }
    val grouped = remember(currentRecords) { groupServiceRecordsByYear(currentRecords) }

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
                "SERVICE HISTORY",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "spend-panel") { FleetSpendPanel(spend) }
                item(key = "spend-hairline") { Hairline() }
                if (currentRecords.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No service records logged yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    grouped.forEach { (year, records) ->
                        item(key = "year-$year") { SectionHeader(year.toString(), records.size.toString()) }
                        items(records, key = { it.id }) { record ->
                            ServiceHistoryRow(
                                record = record,
                                showServiceName = true,
                                onClick = { editingRecord = record },
                            )
                        }
                    }
                }
                item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    val target = editingRecord
    if (target != null) {
        EditServiceRecordDialog(
            record = target,
            onDismiss = { editingRecord = null },
            onSave = { mileage, cost -> onEditRecord(target.id, mileage, cost) },
            onSaved = { mileage, cost ->
                currentRecords = currentRecords.map { if (it.id == target.id) it.copy(mileage = mileage, costCents = cost) else it }
                editingRecord = null
            },
            onDelete = { onDeleteRecord(target.id) },
            onDeleted = {
                currentRecords = currentRecords.filterNot { it.id == target.id }
                editingRecord = null
            },
        )
    }
}

/**
 * The FLEET SPEND panel (ticket 11 §4, all four figures): total (with coverage stated in words),
 * cost per mile (or its refusal), spend by canonicalised service type, spend by year. `quant-viz`'s
 * "every tab face carries inline viz" - each grouped figure gets a [DeckBarChart] ABOVE its exact
 * numeric rows, chart for glanceability, rows for "money stays mono, right-aligned" (ticket 11 §4)
 * - [DeckRow]'s own value column is already right-aligned/mono-styled, so no extra alignment work
 * is needed here beyond using it.
 */
@Composable
private fun FleetSpendPanel(spend: FleetSpendView) {
    val sem = LocalLegionSemantics.current
    DeckPane(header = "Fleet Spend") {
        DeckRow(label = "Total Spent", value = spend.totalText)
        Text(
            spend.coverageText,
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        DeckRow(label = "Cost / Mile", value = spend.perMileText)
        Spacer(Modifier.height(8.dp))
        if (spend.byType.isNotEmpty()) {
            Text(
                "BY SERVICE TYPE",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            DeckBarChart(bars = spend.byType)
            spend.byTypeRows.forEach { (label, amount) -> DeckRow(label = label, value = amount) }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "BY YEAR",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (spend.yearTrendAvailable) {
            DeckBarChart(bars = spend.byYear)
            spend.byYearRows.forEach { (label, amount) -> DeckRow(label = label, value = amount) }
        } else {
            Text(
                "spend per year appears after two years with a logged cost",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * One [ServiceRecord] row - the SAME composable [ItemDetailScreen] uses (see this file's own doc
 * comment). [showServiceName] is false on ITEM DETAIL (the screen's own header already names the
 * service - repeating it on every row would be noise) and true on [ServiceHistoryScreen] (many
 * different services share one flat list). [onClick] is null on ITEM DETAIL (read-only by ticket
 * 09's own binding) and opens the edit dialog on [ServiceHistoryScreen].
 *
 * Cost renders mono, right-aligned, beneath the mileage figure - "no cost logged" in the faint
 * advisory tone when [ServiceRecord.costCents] is null, never a bare blank space that could read as
 * a rendering bug rather than an absent fact.
 */
@Composable
fun ServiceHistoryRow(record: ServiceRecord, showServiceName: Boolean, onClick: (() -> Unit)? = null) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            if (showServiceName) {
                Text(record.serviceName, style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
            }
            // Both fallbacks below are type-satisfying only - this row only ever renders an
            // OBSERVED record (see [groupServiceRecordsByYear]'s own comment), which always
            // carries both non-null.
            Text(shortDate(record.date ?: 0L), style = LegionType.stamp, color = sem.faint)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${groupThousands(record.mileage ?: 0)} mi", style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
            Text(
                record.costCents?.let { "$${formatCents(it)}" } ?: "no cost logged",
                style = LegionType.stamp,
                color = if (record.costCents != null) MaterialTheme.colorScheme.onSurface else sem.faint,
            )
        }
    }
}

/**
 * The edit/delete dialog (ticket 11 §2) - mileage and cost, both pre-filled with [record]'s current
 * values and written unconditionally on SAVE (see [ServiceRecordDao.editMileageAndCost]'s own doc
 * for why), plus the same "tap again to confirm" DELETE pattern [ItemDetailScreen]'s own delete
 * button already uses. **A non-blank cost field that fails to parse REFUSES the save with a stated
 * reason rather than silently writing `null`** - a typo in the cost box must never look, on save,
 * like the driver chose to clear a cost that was already there.
 */
@Composable
private fun EditServiceRecordDialog(
    record: ServiceRecord,
    onDismiss: () -> Unit,
    onSave: suspend (mileage: Int, costCents: Long?) -> WriteOutcome,
    onSaved: (mileage: Int, costCents: Long?) -> Unit,
    onDelete: suspend () -> WriteOutcome,
    onDeleted: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()
    var mileageText by remember(record.id) { mutableStateOf((record.mileage ?: 0).toString()) }
    // Plain "%.2f" here, deliberately NOT formatCents' grouped-thousands form ("1,234.56") - this
    // field round-trips through toDoubleOrNull() on SAVE below, and a comma would fail that parse
    // for any cost >= $1,000 the driver didn't happen to retype by hand.
    var costText by remember(record.id) { mutableStateOf(record.costCents?.let { "%.2f".format(it / 100.0) }.orEmpty()) }
    var statusText by remember(record.id) { mutableStateOf<String?>(null) }
    var confirmingDelete by remember(record.id) { mutableStateOf(false) }

    DeckDialog(title = record.serviceName.uppercase(), onDismissRequest = onDismiss) {
        Text(
            "This delete only removes the record from this phone - it does not sync.",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (statusText != null) {
            Text(statusText!!, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(bottom = 8.dp))
        }
        DeckTextField(
            value = mileageText,
            onValueChange = { mileageText = it },
            label = "Mileage",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(8.dp))
        DeckTextField(
            value = costText,
            onValueChange = { costText = it },
            label = "Cost (dollars)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Row(Modifier.padding(top = 12.dp)) {
            DeckButton(
                text = "SAVE",
                onClick = {
                    val mileage = mileageText.trim().toIntOrNull()
                    if (mileage == null) {
                        statusText = "Mileage needs to be a number."
                        return@DeckButton
                    }
                    val trimmedCost = costText.trim()
                    val costCents: Long? = if (trimmedCost.isEmpty()) {
                        null
                    } else {
                        val dollars = trimmedCost.toDoubleOrNull()
                        if (dollars == null || dollars < 0.0) {
                            statusText = "Cost needs to be a number, e.g. 45.99 - leave it blank to clear it."
                            return@DeckButton
                        }
                        Math.round(dollars * 100.0)
                    }
                    scope.launch {
                        val outcome = onSave(mileage, costCents)
                        statusText = outcome.message
                        if (outcome.success) onSaved(mileage, costCents)
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            DeckButton(
                text = if (confirmingDelete) "TAP AGAIN TO DELETE" else "DELETE",
                destructive = true,
                confirming = confirmingDelete,
                onClick = {
                    if (!confirmingDelete) {
                        confirmingDelete = true
                    } else {
                        scope.launch {
                            val outcome = onDelete()
                            statusText = outcome.message
                            if (outcome.success) onDeleted()
                        }
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Service history: several years, mixed cost", widthDp = 360, heightDp = 1200)
@Composable
private fun PreviewServiceHistoryScreen() = LegionTheme {
    ServiceHistoryScreen(
        recordsNewestFirst = listOf(
            ServiceRecord(id = 3, vehicleId = "x", serviceName = "Oil Change", mileage = 107_000, date = 1_755_000_000_000L, costCents = 4599),
            ServiceRecord(id = 2, vehicleId = "x", serviceName = "Tire Rotation", mileage = 106_800, date = 1_752_000_000_000L, costCents = null),
            ServiceRecord(id = 1, vehicleId = "x", serviceName = "Air Filter", mileage = 100_000, date = 1_700_000_000_000L, costCents = 2200),
        ),
        spend = FleetSpendView(
            totalText = "$67.99",
            coverageText = "2 of 3 service records have a cost logged.",
            perMileText = "$0.06 / mi",
            perMileIsRefusal = false,
            byType = emptyList(),
            byTypeRows = listOf("Oil Change" to "$45.99", "Air Filter" to "$22.00"),
            byYear = emptyList(),
            byYearRows = emptyList(),
            yearTrendAvailable = false,
        ),
        onEditRecord = { _, _, _ -> WriteOutcome(true, "ok") },
        onDeleteRecord = { WriteOutcome(true, "ok") },
        onBack = {},
    )
}
