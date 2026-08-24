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
 * in this app's `NavHost` already uses (see `TodayScreen`'s `onOpenBody`/`onOpenFleet` etc.). Two
 * callers: the HOME page's own "CLASSIC" button (`LegionRoute.TODAY` - the old Today panel, which
 * this pager's seeded HOME arrangement is modelled on but does not yet fully replicate, e.g. the
 * ALERTS pane and the media mini-bar tap-through), and each aspect page's "OPEN FULL SCREEN" button
 * for an aspect that still has a richer legacy screen ([legacyRouteForAspect]) - ingestion UIs
 * (ledger/pantry import), OBD live views, and other capabilities the generic engine widgets do not
 * yet carry.
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
                // The HOME page's own hands path back to the old Today panel (cutover 5) - see
                // this file's own doc comment on [onOpenRoute] for exactly what it still carries
                // that the seeded HOME arrangement does not yet replicate. Only on page 0: every
                // other page has its own "OPEN FULL SCREEN" link instead (see [WidgetPagerPage]).
                if (pagerState.currentPage == 0) {
                    DeckButton(text = "CLASSIC", onClick = { onOpenRoute(com.kevin.legion.ui.LegionRoute.TODAY) })
                }
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

/** The trailing "+" page - "creates an aspect" (ticket 18 brief; "stub screen is fine this pass").
 * A real, working create - a name field and a button, writing straight to [com.kevin.legion.data.local.AspectDao] -
 * because a stub that visibly does nothing would contradict CLAUDE.md §7's "say plainly what is not
 * built" posture just as much as a fake success would; this genuinely creates a page, it simply has
 * no further chrome (icon/colour pickers, reordering) beyond the one field the brief calls for. */
@Composable
private fun AddAspectPage(onCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).border(1.dp, sem.chromeDim), contentAlignment = Alignment.Center) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = sem.chromeText)
        }
        Text("ADD AN ASPECT", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        DeckTextField(value = name, onValueChange = { name = it }, label = "NAME", modifier = Modifier.fillMaxWidth())
        DeckButton(
            text = "CREATE",
            enabled = name.isNotBlank(),
            onClick = {
                val trimmed = name.trim()
                scope.launch {
                    val db = CarDatabase.getDatabase(context)
                    val now = System.currentTimeMillis()
                    val position = db.aspectDao().listActive().size
                    db.aspectDao().insert(Aspect(name = trimmed, position = position, createdAt = now, updatedAt = now))
                    name = ""
                    onCreated()
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
