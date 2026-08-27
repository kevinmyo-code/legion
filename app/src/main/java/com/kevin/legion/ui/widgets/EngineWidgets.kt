package com.kevin.legion.ui.widgets

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.WidgetDataSource
import com.kevin.legion.engine.WidgetKind
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPoint
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DECK_SPARKLINE_MIN_POINTS
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.deckSparklineHasShape
import com.kevin.legion.ui.grid.GridPreset
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The eight [WidgetKind] bodies (aspect-engine ticket 18), rendered over real [WidgetDataSource]
 * reads - every one of these is the ADR-0035-flavoured leaf `WidgetPagerScreen.kt`'s `DeckGrid`
 * `itemContent` dispatches into once it has already wrapped the card in a `DeckPane` (this file draws
 * only the CONTENT, never the pane frame, matching `PrototypeGrid.kt`'s own `WidgetBody` split).
 *
 * **Every widget below states its own error/empty case in words** (ticket 18 build item 2,
 * CLAUDE.md's "unreadable and empty are different sentences"): "not configured" (the seeded default
 * state - see [com.kevin.legion.engine.DefaultArrangementSeeder]), "deleted" (the field/record type
 * this widget pointed at is gone), and "nothing here yet" (configured correctly, genuinely zero rows)
 * are three different sentences, never collapsed into a shared blank.
 *
 * Every composable here loads its own data via a plain `LaunchedEffect` + `remember` pair rather
 * than a shared ViewModel - the pager's own widgets are independent, short-lived reads (a handful of
 * DAO calls each), and CLAUDE.md §10 already accepts this "simple and inspectable over clever"
 * tradeoff for `LedgerController`/`PantryController`'s own DB-write paths at the same data scale.
 */

private val sem @Composable get() = LocalLegionSemantics.current

@Composable
fun StatTileWidget(dataSource: WidgetDataSource, recordTypeId: Long?, fieldId: Long?) {
    var result by remember(recordTypeId, fieldId) { mutableStateOf<WidgetDataSource.StatResult?>(null) }
    LaunchedEffect(recordTypeId, fieldId) { result = dataSource.statTile(recordTypeId, fieldId) }
    when (val r = result) {
        null -> WidgetLoading()
        WidgetDataSource.StatResult.NotConfigured -> WidgetEmpty("not configured yet")
        is WidgetDataSource.StatResult.Error -> WidgetError(r.message)
        is WidgetDataSource.StatResult.Count -> {
            Text(r.n.toString(), style = LegionType.amount, color = sem.data)
            Text("records", style = LegionType.stamp, color = sem.faint)
        }
        is WidgetDataSource.StatResult.Money -> {
            val sign = if (r.cents < 0) "-" else ""
            val abs = kotlin.math.abs(r.cents)
            Text("$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}", style = LegionType.amount, color = sem.data)
        }
        is WidgetDataSource.StatResult.Number -> {
            Text("%.1f".format(r.value), style = LegionType.amount, color = sem.data)
        }
    }
}

@Composable
fun RecordListWidget(dataSource: WidgetDataSource, recordTypeId: Long?, limit: Int, maxRows: Int) {
    var loaded by remember(recordTypeId, limit) { mutableStateOf(false) }
    var rows by remember(recordTypeId, limit) { mutableStateOf<List<WidgetDataSource.ListRow>?>(null) }
    LaunchedEffect(recordTypeId, limit) {
        rows = dataSource.recordList(recordTypeId, limit)
        loaded = true
    }
    val r = rows
    when {
        !loaded -> WidgetLoading()
        recordTypeId == null || r == null -> WidgetEmpty("not configured yet")
        r.isEmpty() -> WidgetEmpty("no records yet")
        else -> Column { r.take(maxRows).forEach { DeckRow(label = it.title, value = it.value) } }
    }
}

@Composable
fun NextDueWidget(dataSource: WidgetDataSource, recordTypeId: Long?) {
    var due by remember(recordTypeId) { mutableStateOf<WidgetDataSource.DueItem?>(null) }
    var loaded by remember(recordTypeId) { mutableStateOf(false) }
    LaunchedEffect(recordTypeId) {
        due = dataSource.nextDue(recordTypeId)
        loaded = true
    }
    if (!loaded) {
        WidgetLoading()
    } else if (due == null) {
        WidgetEmpty("nothing due")
    } else {
        val d = due!!
        DeckRow(label = d.title, value = java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(d.dueAt)))
    }
}

@Composable
fun AgendaWidget(dataSource: WidgetDataSource, recordTypeId: Long?, limit: Int, maxRows: Int) {
    var items by remember(recordTypeId, limit) { mutableStateOf<List<WidgetDataSource.DueItem>?>(null) }
    LaunchedEffect(recordTypeId, limit) { items = dataSource.agenda(recordTypeId, limit) }
    when (val list = items) {
        null -> WidgetLoading()
        else -> if (list.isEmpty()) {
            WidgetEmpty("nothing scheduled")
        } else {
            Column {
                list.take(maxRows).forEach { d ->
                    DeckRow(label = d.title, value = java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(d.dueAt)))
                }
            }
        }
    }
}

@Composable
fun QuickAddWidget(title: String, targetRecordTypeId: Long?, onAdd: (Long) -> Unit) {
    if (targetRecordTypeId == null) {
        WidgetEmpty("not configured yet")
    } else {
        DeckButton(text = title, onClick = { onAdd(targetRecordTypeId) })
    }
}

@Composable
fun SingleRecordCardWidget(dataSource: WidgetDataSource, recordId: Long?, maxRows: Int) {
    var card by remember(recordId) { mutableStateOf<WidgetDataSource.RecordCard?>(null) }
    var loaded by remember(recordId) { mutableStateOf(false) }
    LaunchedEffect(recordId) {
        card = dataSource.singleRecord(recordId)
        loaded = true
    }
    if (!loaded) {
        WidgetLoading()
    } else if (card == null) {
        WidgetEmpty(if (recordId == null) "not configured yet" else "record not found")
    } else {
        val c = card!!
        Column {
            Text(c.title, style = LegionType.reading, color = sem.data)
            provenanceLabel(c.provenance)?.let { Text(it, style = LegionType.stamp, color = sem.faint) }
            c.rows.take(maxRows).forEach { (label, value) -> DeckRow(label = label, value = value) }
        }
    }
}

@Composable
fun ChartWidget(dataSource: WidgetDataSource, recordTypeId: Long?, fieldId: Long?, dateFieldId: Long?, limit: Int) {
    var points by remember(recordTypeId, fieldId, dateFieldId, limit) {
        mutableStateOf<List<WidgetDataSource.ChartPoint>?>(null)
    }
    var configured by remember(recordTypeId, fieldId, dateFieldId) { mutableStateOf(true) }
    LaunchedEffect(recordTypeId, fieldId, dateFieldId, limit) {
        val series = dataSource.chartSeries(recordTypeId, fieldId, dateFieldId, limit)
        configured = series != null
        points = series
    }
    val series = points
    if (!configured) {
        WidgetEmpty("not configured yet")
    } else if (series == null) {
        WidgetLoading()
    } else if (series.isEmpty()) {
        WidgetEmpty("no data points yet")
    } else if (!deckSparklineHasShape(series.map { it.y })) {
        // A widget occupies its grid cell whether or not the chart inside draws, so the collapse
        // [DeckSparkline] performs below the threshold would leave a titled, empty cell here rather
        // than the floating dot it replaced (command-center ticket 13 finding 4). Say the count
        // instead: "not enough yet" and "none at all" are different facts and the cell has room for
        // the distinction.
        WidgetEmpty("${series.size} of $DECK_SPARKLINE_MIN_POINTS points needed for a trend")
    } else {
        // The mission-control palette applies automatically here - DeckSparkline reads
        // LocalLegionSemantics.current.data/marker for its stroke/dot colours, same as every other
        // chart primitive in ui/common/DeckCharts.kt; this widget never picks a colour itself.
        DeckSparkline(points = series.map { it.y })
    }
}

@Composable
fun PhotoWidget(dataSource: WidgetDataSource, recordId: Long?, fieldId: Long?) {
    // "Still loading" vs "resolved to no path at all" are both a bare `null` in `String?` - a
    // separate `loaded` flag is what lets this distinguish "not answered yet" from "genuinely no
    // photo attached" below, rather than flashing an empty-state message for one frame on every load.
    var loaded by remember(recordId, fieldId) { mutableStateOf(false) }
    var path by remember(recordId, fieldId) { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId, fieldId) {
        path = dataSource.photoPath(recordId, fieldId)
        loaded = true
    }
    if (!loaded) {
        WidgetLoading()
        return
    }
    val realPath = path
    if (realPath == null) {
        WidgetEmpty(if (recordId == null || fieldId == null) "not configured yet" else "no photo attached")
        return
    }
    val bitmap = remember(realPath) { runCatching { BitmapFactory.decodeFile(realPath) }.getOrNull() }
    if (bitmap == null) {
        // Unreadable, not empty (CLAUDE.md's own distinction) - a path is stored but the file at
        // it could not be decoded (deleted from storage, corrupted), which is a different sentence
        // from "no photo was ever attached" above.
        WidgetError("photo could not be read")
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        )
    }
}

// ---- shared leaf states -----------------------------------------------------------------------

@Composable
private fun WidgetLoading() {
    Text("LOADING", style = LegionType.stamp, color = sem.ghost)
}

/** Configured-but-genuinely-nothing-there, or deliberately-unconfigured - [why] states which in
 * words (CLAUDE.md's "unreadable and empty are different sentences" extended to the widget layer). */
@Composable
private fun WidgetEmpty(why: String) {
    Text(why.uppercase(), style = LegionType.stamp, color = sem.faint)
}

/** A read that failed for a reason worth naming - a deleted field, an unreadable file. Never the
 * same visual weight as [WidgetEmpty]: this reads the quarantine tone, not the faint one, since an
 * error is not a neutral "nothing here" state. */
@Composable
private fun WidgetError(message: String) {
    Text(message.uppercase(), style = LegionType.stamp, color = sem.quarantined)
}

@Composable
private fun provenanceLabel(provenance: RecordProvenance): String? = when (provenance) {
    RecordProvenance.DETERMINISTIC -> "VERIFIED"
    RecordProvenance.LLM_RECONCILED -> "LLM RECONCILED"
    RecordProvenance.UNRECONCILED -> "UNVERIFIED"
    RecordProvenance.USER -> null // a plain hand-entered row needs no provenance callout
}

/** How many content rows a multi-row [WidgetKind] renders at its CURRENT [GridPreset] - the same
 * "content adapts per preset, a card always exactly fills its rect" rule
 * `prototype/PrototypeGrid.kt`'s own `maxRowsFor` established, carried into production. */
fun WidgetKind.maxRowsFor(preset: GridPreset): Int = when (this) {
    WidgetKind.RECORD_LIST, WidgetKind.AGENDA -> if (preset == GridPreset.LARGE) 4 else 2
    else -> Int.MAX_VALUE
}
