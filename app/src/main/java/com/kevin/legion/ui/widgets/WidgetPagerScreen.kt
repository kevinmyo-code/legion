@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.WidgetInstance
import com.kevin.legion.engine.DefaultArrangementSeeder
import com.kevin.legion.engine.DeviceId
import com.kevin.legion.engine.WidgetDataSource
import com.kevin.legion.engine.WidgetInstanceStore
import com.kevin.legion.engine.WidgetKind
import com.kevin.legion.engine.parseWidgetKind
import com.kevin.legion.engine.supportedPresets
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.grid.DeckGrid
import com.kevin.legion.ui.grid.GridItem
import com.kevin.legion.ui.grid.GridPreset
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * The PRODUCTION widget pager (aspect-engine ticket 18) - "home page one, one page per aspect, a +
 * page that creates an aspect" (ticket's own brief), backed by real `widget_instances` rows through
 * [WidgetInstanceStore] and the eight [WidgetKind] bodies in `EngineWidgets.kt`, replacing
 * `prototype/PrototypeDashboard.kt`'s in-memory fixtures with the real engine tables while keeping
 * every mechanic that prototype validated: [DeckGrid] stage-2 grid, edit-mode jiggle, page dots.
 *
 * **The app's home as of cutover 5** (`docs/architecture/cutover5-2026-08-24.md`) - hosted as an
 * ordinary `composable(LegionRoute.DASHBOARD)` destination inside `MainActivity`'s own `NavHost`
 * (the same shell every other screen lives in), not a separate Activity. `WidgetPagerActivity` -
 * the debug-only, `adb shell am start`-reachable entry point ticket 18 shipped this behind - is
 * DELETED as of this cutover, same disposal `SavedPlacesActivity`/`LedgerImportActivity`/
 * `PantryImportActivity` got at ticket 07: only the hosting changed, the composable is unchanged.
 *
 * [onOpenRoute] is how this composable reaches destinations OUTSIDE itself without owning a
 * [androidx.navigation.NavHostController] of its own - the same shape every other top-level screen
 * in this app's `NavHost` already uses (see [com.kevin.legion.ui.MetersScreen]'s `onOpenBody`/
 * `onOpenFleet` etc.). One caller today: each aspect page's "OPEN FULL SCREEN" button for an aspect
 * that still has a richer legacy screen ([legacyRouteForAspect]) - ingestion UIs (ledger/pantry
 * import), OBD live views, and other capabilities the generic engine widgets do not yet carry.
 *
 * **Demoted from HOME to an opt-in surface 2026-08-25** - Kevin field-tested this pager as the
 * app's home overnight and ruled "kill it, revert everything to classic"
 * (`docs/architecture/cutover5-2026-08-24.md`'s postscript). This composable and every capability
 * behind it (the pager itself, the eight widget kinds, the DeckGrid edit mode, per-aspect pages)
 * stays in the codebase unchanged - only its reachability changed, from HOME/start-destination down
 * to a single "DASHBOARD" button on `ui/TodayScreen.kt` - and then, when that screen was deleted
 * 2026-09-01 (one-today ticket 07), down again to a "Dashboard" row in
 * [com.kevin.legion.ui.SettingsScreen] - so the seven on-device grid-feel rounds this pager went
 * through are not orphaned and the pager remains a real hands path to the aspect engine (ADR 0035).
 * The HOME page's own "CLASSIC" button (the mirror-image hands path back to Today) was REMOVED at
 * cutover 5's revert - pointless once TODAY was home again, one tap away by the back button on
 * anyone who reached this page at all; TODAY itself is gone now too (one-today ticket 07), so this
 * pager's own back button lands on whatever the caller's own back stack holds instead.
 */
@Composable
fun WidgetPagerRoot(onOpenRoute: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { CarDatabase.getDatabase(context) }
    val widgetStore = remember { WidgetInstanceStore(db.widgetInstanceDao()) }
    val dataSource = remember { WidgetDataSource(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao(), db.aspectDao()) }
    val seeder = remember { DefaultArrangementSeeder(db, widgetStore, db.aspectDao()) }
    val deviceId = remember { DeviceId.current(context) }

    var aspects by remember { mutableStateOf<List<Aspect>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        seeder.seedHomeIfEmpty(deviceId)
        aspects = db.aspectDao().listActive()
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("LOADING", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
        }
        return
    }

    // Page 0 is HOME (aspectId = null); pages 1..n are each active aspect in position order; the
    // trailing page is the "+ add an aspect" stub - ticket 18's own "new aspect = new page" brief,
    // and ticket 09's own answer to "where does 'new aspect' live" (a trailing pager page, like a
    // launcher's own "+" screen), carried over from the prototype unchanged.
    val pageCount = aspects.size + 2
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var editMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LEGION // DASHBOARD", style = LegionType.stamp, color = LocalLegionSemantics.current.chromeText)
            Row {
                // The HOME page's own "CLASSIC" button back to TODAY was removed 2026-08-25 - TODAY
                // is the app's home again (see this file's own doc comment), so a hands path FROM
                // this opt-in surface back TO it is redundant with the system back button.
                if (pagerState.currentPage < pageCount - 1) {
                    DeckButton(text = if (editMode) "DONE" else "EDIT", onClick = { editMode = !editMode })
                }
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { pageIndex ->
            when {
                pageIndex == 0 -> WidgetPagerPage(
                    label = "HOME",
                    aspectId = null,
                    deviceId = deviceId,
                    widgetStore = widgetStore,
                    dataSource = dataSource,
                    editMode = editMode,
                    onEnterEditMode = { editMode = true },
                )
                pageIndex <= aspects.size -> {
                    val aspect = aspects[pageIndex - 1]
                    WidgetPagerPage(
                        label = aspect.name,
                        aspectId = aspect.id,
                        deviceId = deviceId,
                        widgetStore = widgetStore,
                        dataSource = dataSource,
                        editMode = editMode,
                        onEnterEditMode = { editMode = true },
                        legacyRoute = legacyRouteForAspect(aspect.name),
                        onOpenRoute = onOpenRoute,
                    )
                }
                else -> AddAspectPage(
                    onCreated = {
                        scope.launch {
                            editMode = false
                            reloadKey += 1
                        }
                    },
                )
            }
        }

        PagerDots(current = pagerState.currentPage, count = pageCount)
    }
}

/**
 * The legacy `LegionRoute` an aspect page's "OPEN FULL SCREEN" button targets, keyed by
 * [com.kevin.legion.data.local.Aspect.name] against the exact string literal each of the six
 * `engine` package `AspectSeeder.kt` files' own `ASPECT_NAME` constant seeds - cutover 5's own reachability
 * ruling table (`docs/architecture/cutover5-2026-08-24.md`) names the mapping and why `Dates`
 * carries none: it is a genuinely new aspect with no pre-existing legacy screen to point at, not a
 * capability this cutover dropped. A user-created aspect (the pager's own "+" page) also carries
 * none - it never had a legacy screen either.
 */
internal fun legacyRouteForAspect(aspectName: String): String? = when (aspectName) {
    "Fleet" -> com.kevin.legion.ui.LegionRoute.FLEET
    "Ledger" -> com.kevin.legion.ui.LegionRoute.MONEY
    "Pantry" -> com.kevin.legion.ui.LegionRoute.MONEY_PANTRY
    "Notes" -> com.kevin.legion.ui.LegionRoute.NOTES
    "Places" -> com.kevin.legion.ui.LegionRoute.FLEET_PLACES
    else -> null
}

/** One page's body: header rule, then a [DeckGrid] over that page's [WidgetInstance] rows. */
@Composable
private fun WidgetPagerPage(
    label: String,
    aspectId: Long?,
    deviceId: String,
    widgetStore: WidgetInstanceStore,
    dataSource: WidgetDataSource,
    editMode: Boolean,
    onEnterEditMode: () -> Unit,
    legacyRoute: String? = null,
    onOpenRoute: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var rows by remember(aspectId) { mutableStateOf<List<WidgetInstance>>(emptyList()) }
    var loaded by remember(aspectId) { mutableStateOf(false) }
    LaunchedEffect(aspectId, deviceId) {
        rows = widgetStore.layoutForPage(deviceId, aspectId)
        loaded = true
    }

    if (!loaded) {
        Text("LOADING", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
        return
    }

    val byId = rows.associateBy { it.id.toString() }
    var items by remember(rows) { mutableStateOf(WidgetInstanceStore.toGridItems(rows)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        DeckSectionRule(label = label)
        // The aspect page's own hands path to its richer legacy screen (cutover 5) - ingestion
        // UIs (ledger/pantry import), the OBD live views, and other capabilities the generic
        // engine widgets above do not yet carry. Absent for an aspect with no legacy screen
        // ([legacyRouteForAspect]'s own doc for exactly which and why).
        if (legacyRoute != null) {
            DeckButton(text = "OPEN FULL SCREEN", onClick = { onOpenRoute(legacyRoute) })
        }
        if (rows.isEmpty()) {
            Text("NOTHING PLACED ON THIS PAGE YET", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
            return@Column
        }
        DeckGrid(
            items = items,
            columnCount = 4,
            editMode = editMode,
            onEnterEditMode = onEnterEditMode,
            onLayoutChange = { updated ->
                items = updated
                scope.launch { widgetStore.saveLayout(updated) }
            },
            onRemove = { id ->
                items = items.filterNot { it.id == id }
                id.toLongOrNull()?.let { rid -> scope.launch { widgetStore.removeWidget(rid) } }
            },
            presetsFor = { item -> byId[item.id]?.widgetType?.let { parseWidgetKind(it)?.supportedPresets } ?: listOf(GridPreset.SMALL) },
            modifier = Modifier.fillMaxWidth(),
        ) { item ->
            val widget = byId[item.id]
            if (widget == null) {
                Text("MISSING WIDGET", style = LegionType.stamp, color = LocalLegionSemantics.current.quarantined)
                return@DeckGrid
            }
            val kind = parseWidgetKind(widget.widgetType)
            val preset = GridPreset.match(item) ?: GridPreset.SMALL
            DeckPane(header = kind?.name?.replace('_', ' ') ?: "UNKNOWN", modifier = Modifier.fillMaxWidth()) {
                WidgetContent(dataSource = dataSource, widget = widget, kind = kind, preset = preset)
            }
        }
    }
}

/** Dispatches one [WidgetInstance] to its [WidgetKind]'s body - the pager's own analogue of
 * `prototype/PrototypeGrid.kt`'s `WidgetBody`, over real reads instead of fixtures. */
@Composable
private fun WidgetContent(dataSource: WidgetDataSource, widget: WidgetInstance, kind: WidgetKind?, preset: GridPreset) {
    if (kind == null) {
        Text("UNRECOGNISED WIDGET TYPE '${widget.widgetType}'", style = LegionType.stamp, color = LocalLegionSemantics.current.quarantined)
        return
    }
    val config = WidgetConfigCodec.parse(widget.config)
    when (kind) {
        WidgetKind.STAT_TILE -> StatTileWidget(dataSource, widget.recordTypeId, config.fieldId)
        WidgetKind.RECORD_LIST -> RecordListWidget(dataSource, widget.recordTypeId, config.limit, kind.maxRowsFor(preset))
        WidgetKind.NEXT_DUE -> NextDueWidget(dataSource, widget.recordTypeId)
        WidgetKind.QUICK_ADD -> QuickAddWidget(
            title = "ADD",
            targetRecordTypeId = config.targetRecordTypeId,
            onAdd = { /* opens the generated add form for the target record type - ui/generated/GeneratedFormScreen.kt, wired at nav-integration time (ticket 18 build item 5's "not the app's home yet" scope) */ },
        )
        WidgetKind.SINGLE_RECORD_CARD -> SingleRecordCardWidget(dataSource, config.recordId, kind.maxRowsFor(preset))
        WidgetKind.AGENDA -> AgendaWidget(dataSource, widget.recordTypeId, config.limit, kind.maxRowsFor(preset))
        WidgetKind.CHART -> ChartWidget(dataSource, widget.recordTypeId, config.fieldId, config.dateFieldId, config.limit.coerceAtLeast(2))
        WidgetKind.PHOTO -> PhotoWidget(dataSource, config.recordId, config.photoFieldId)
    }
}

/**
 * The trailing "+" page - "creates an aspect" (ticket 18 brief). **Was a stub through ticket 18**
 * (named as such in that ticket's own report): it wrote a bare [Aspect] row and nothing else, with
 * no [com.kevin.legion.data.local.RecordType] and no [com.kevin.legion.data.local.FieldDef] under
 * it. That is the "adding an aspect doesn't work" report (Kevin, 2026-08-25) - a driver who tapped
 * CREATE got a new page that would forever read "NOTHING PLACED ON THIS PAGE YET", because there
 * was no record type for any widget or voice tool to point at: [EngineToolbox.declarations]'s own
 * `create_record`/`describe_aspect` tools all key off a record type id, and none existed. The
 * aspect looked created; nothing about it was actually usable.
 *
 * **Real create flow as of 2026-08-25**: aspect name, a starter record type name, and one or more
 * starter field names, committed through [EngineToolbox.manualCreateDraft] +
 * [EngineToolbox.commitCreateAspect] - the EXACT same write path `create_aspect`'s voice confirm
 * handshake commits a Pro-drafted schema through (see [EngineToolbox.manualCreateDraft]'s own doc
 * comment for why this is one implementation, not a second). A driver who wants richer field types
 * than plain text still has the voice path; this form's whole scope is getting a genuinely usable
 * aspect - one record type with at least one field - out of the pager's own "+" page.
 */
@Composable
private fun AddAspectPage(onCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var aspectName by remember { mutableStateOf("") }
    var recordTypeName by remember { mutableStateOf("") }
    // At least one starter field, so the record type this creates is genuinely writable the moment
    // it exists - see this composable's own doc comment for why a record-type-less aspect was the
    // bug. A second, optional field slot covers the common "name + one more thing" shape without
    // building a full repeatable-row editor for a page whose brief only ever asked for "a name field
    // and a button".
    var firstFieldName by remember { mutableStateOf("Name") }
    var secondFieldName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current
    val canCreate = aspectName.isNotBlank() && recordTypeName.isNotBlank() && firstFieldName.isNotBlank()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp).border(1.dp, sem.chromeDim), contentAlignment = Alignment.Center) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = sem.chromeText)
        }
        Text("ADD AN ASPECT", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        DeckTextField(value = aspectName, onValueChange = { aspectName = it }, label = "ASPECT NAME", modifier = Modifier.fillMaxWidth())
        DeckTextField(
            value = recordTypeName, onValueChange = { recordTypeName = it }, label = "STARTER RECORD TYPE",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        DeckTextField(
            value = firstFieldName, onValueChange = { firstFieldName = it }, label = "FIRST FIELD",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        DeckTextField(
            value = secondFieldName, onValueChange = { secondFieldName = it }, label = "SECOND FIELD (OPTIONAL)",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        errorMessage?.let {
            Text(it, style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(top = 8.dp))
        }
        DeckButton(
            text = "CREATE",
            enabled = canCreate,
            onClick = {
                val fieldNames = listOfNotNull(
                    firstFieldName.trim().takeIf { it.isNotBlank() },
                    secondFieldName.trim().takeIf { it.isNotBlank() },
                )
                scope.launch {
                    val db = CarDatabase.getDatabase(context)
                    val draft = com.kevin.legion.service.EngineToolbox.manualCreateDraft(
                        aspectName = aspectName.trim(),
                        recordTypeName = recordTypeName.trim(),
                        fieldNames = fieldNames,
                    )
                    val outcome = com.kevin.legion.service.EngineToolbox.commitCreateAspect(db, draft)
                    // The outcome rule (CLAUDE.md §7): only claim it worked if the write path
                    // actually reported success - a failure surfaces its own worded message in
                    // place of silently doing nothing or fabricating a success.
                    if (outcome.optBoolean("success", false)) {
                        errorMessage = null
                        aspectName = ""
                        recordTypeName = ""
                        firstFieldName = "Name"
                        secondFieldName = ""
                        onCreated()
                    } else {
                        errorMessage = outcome.optString("message").ifBlank { "Could not create that aspect." }
                    }
                }
            },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun PagerDots(current: Int, count: Int) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val filled = i == current
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .combinedClickable(onClick = {})
                    .let { if (filled) it.background(MaterialTheme.colorScheme.primary) else it.border(1.dp, sem.faint) },
            )
        }
    }
}
