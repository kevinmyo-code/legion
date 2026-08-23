package com.kevin.legion.ui.generated

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import org.json.JSONObject

/** One page of the generated list screen - kept small on a phone screen; "load more" grows this,
 * never fetches a second query, since [EngineRecordDao.activeByRecordType]'s own result for a
 * personal-app-scale record type is already cheap to hold entirely in memory (CLAUDE.md §10's
 * "simple and inspectable over clever" tradeoff, same posture [RecordStore]'s own aggregate reads
 * already accept at this data scale). */
private const val PAGE_SIZE = 20

/**
 * The generated LIST screen (aspect-engine ticket 10 answer point 1, built by ticket 18): title
 * field, sort, filter/search, pagination for a 400-row ledger-scale record type - every record type
 * gets this for free from its own [FieldDef]s, no per-aspect screen code.
 *
 * **Title-field choice**: the first TEXT field, falling back to the first CHOICE field, falling back
 * to `Record #<id>` - the same [FieldDef]-scan every generated/widget surface in this ticket uses
 * (see [com.kevin.legion.engine.WidgetDataSource]'s private `titleFor`, duplicated here rather than
 * shared because that function is `private` to a class this screen has no reason to depend on for
 * one four-line helper).
 *
 * **Search** filters client-side over [EngineRecord.searchText] (the promoted column
 * [com.kevin.legion.engine.PayloadCodec.buildSearchText] already builds) rather than a new DAO
 * query - [EngineRecordDao.search] exists but is NOT scoped to one record type, and this screen
 * always needs exactly one type's rows loaded anyway for [FieldDef]-driven rendering, so filtering
 * the already-loaded list is both simpler and avoids a second, type-unscoped query.
 */
@Composable
fun GeneratedListScreen(
    recordTypeId: Long,
    onBack: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onAddRecord: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { CarDatabase.getDatabase(context) }
    val sem = LocalLegionSemantics.current

    var fieldDefs by remember(recordTypeId) { mutableStateOf<List<FieldDef>>(emptyList()) }
    var records by remember(recordTypeId) { mutableStateOf<List<EngineRecord>>(emptyList()) }
    var loaded by remember(recordTypeId) { mutableStateOf(false) }
    var query by remember(recordTypeId) { mutableStateOf("") }
    var sortDescending by remember(recordTypeId) { mutableStateOf(true) }
    var visibleCount by remember(recordTypeId) { mutableStateOf(PAGE_SIZE) }

    LaunchedEffect(recordTypeId) {
        fieldDefs = db.fieldDefDao().forRecordType(recordTypeId)
        records = db.engineRecordDao().activeByRecordType(recordTypeId)
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        DeckScreenHeader(title = "RECORDS", onBack = onBack)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            DeckTextField(value = query, onValueChange = { query = it; visibleCount = PAGE_SIZE }, label = "SEARCH", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                DeckButton(text = if (sortDescending) "NEWEST FIRST" else "OLDEST FIRST", onClick = { sortDescending = !sortDescending })
                DeckButton(text = "ADD", onClick = onAddRecord)
            }
        }
        if (!loaded) {
            Text("LOADING", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(12.dp))
            return@Column
        }
        val filtered = if (query.isBlank()) records else records.filter { it.searchText.contains(query, ignoreCase = true) }
        val sorted = if (sortDescending) filtered.sortedByDescending { it.updatedAt } else filtered.sortedBy { it.updatedAt }
        if (sorted.isEmpty()) {
            Text(
                if (records.isEmpty()) "NO RECORDS YET" else "NO RECORDS MATCH '$query'",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(12.dp),
            )
            return@Column
        }
        val page = sorted.take(visibleCount)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(page, key = { it.id }) { record ->
                DeckRow(
                    label = titleFor(fieldDefs, record),
                    value = valueFor(record),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenRecord(record.id) },
                )
            }
        }
        if (visibleCount < sorted.size) {
            DeckButton(
                text = "LOAD MORE (${sorted.size - visibleCount} remaining)",
                onClick = { visibleCount = (visibleCount + PAGE_SIZE).coerceAtMost(sorted.size) },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
    }
}

private fun titleFor(fieldDefs: List<FieldDef>, record: EngineRecord): String {
    val textField = fieldDefs.firstOrNull { it.type == FieldType.TEXT } ?: fieldDefs.firstOrNull { it.type == FieldType.CHOICE }
    val payload = JSONObject(record.payload)
    val value = textField?.let { fd -> if (payload.has(fd.id.toString()) && !payload.isNull(fd.id.toString())) payload.optString(fd.id.toString()) else null }
    return if (!value.isNullOrBlank()) value else "Record #${record.id}"
}

private fun valueFor(record: EngineRecord): String = when {
    record.amountCents != null -> {
        val sign = if (record.amountCents < 0) "-" else ""
        val abs = kotlin.math.abs(record.amountCents)
        "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
    record.dueAt != null -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(record.dueAt))
    else -> ""
}
