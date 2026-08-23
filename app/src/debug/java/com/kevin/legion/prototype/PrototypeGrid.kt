package com.kevin.legion.prototype

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.grid.DeckGrid
import com.kevin.legion.ui.grid.GridItem
import com.kevin.legion.ui.grid.GridPreset
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Stage-2 harness (aspect-engine ticket 18, built from the ticket 09 spike Kevin authorized
 * 2026-08-23: "feels like a dashboard. build stage 2."). This is the feel-test half of the ticket
 * - the reusable mechanics themselves live in the PRODUCTION source set,
 * `ui/grid/GridModel.kt` + `ui/grid/DeckGrid.kt`, so ticket 18's real widget pager can reach for
 * them directly rather than porting anything out of `prototype/`. This file only wires that
 * component to each page's mock widgets and hand-assigns an initial layout, exactly the
 * throwaway-fixture posture [PrototypeData.kt]'s own file doc states for the rest of this package.
 *
 * **Every page is stage 2 now (2026-08-23, second feel-test pass).** Kevin: "HOME works." The
 * stage-1 reorderable column ([app/src/debug/.../PrototypeReorder.kt], now DELETED) served its
 * comparison purpose and is gone - FLEET and LEDGER render through the exact same [PrototypeGridPage]
 * HOME does, each with its OWN independent [GridItem] list (`remember(widgets)` keys per page's own
 * widget list, so HOME/FLEET/LEDGER never share drag/resize state).
 *
 * [WidgetBody] (below) used to live in the deleted stage-1 file; it moved here since this is now
 * the only caller.
 */

/** Four columns in portrait, per ticket 18's brief. Not read from anywhere else in this harness -
 *  a real screen would size this from the device's own width class, which is out of scope here. */
private const val PROTOTYPE_GRID_COLUMNS = 4

/**
 * Hand-assigns an initial [GridItem] layout for [widgets] from each one's own
 * [PrototypeWidget.initialPreset] - packing two side-by-side widgets into one row whenever both
 * their presets are narrower than the full grid width and still fit together, one widget to its
 * own row(s) otherwise (the same greedy adjacency the deleted stage-1 column used, so every page
 * starts from a familiar arrangement even though the mechanics underneath are now entirely
 * stage 2, and now entirely preset-driven rather than a HALF/FULL toggle - see
 * [PrototypeWidget.initialPreset]'s own doc). `rowSpan`/`colSpan` come straight from the preset;
 * exercised for real (both dimensions change together) the moment edit mode's size chip is tapped.
 */
private fun initialGridItems(widgets: List<PrototypeWidget>): List<GridItem> {
    val items = mutableListOf<GridItem>()
    var row = 0
    var i = 0
    while (i < widgets.size) {
        val w = widgets[i]
        val preset = w.initialPreset
        if (preset.colSpan >= PROTOTYPE_GRID_COLUMNS) {
            items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = preset.rowSpan, colSpan = preset.colSpan))
            row += preset.rowSpan
            i += 1
        } else {
            val next = widgets.getOrNull(i + 1)
            val nextPreset = next?.initialPreset
            if (next != null && nextPreset != null && preset.colSpan + nextPreset.colSpan <= PROTOTYPE_GRID_COLUMNS) {
                // Two widgets sharing a row must share the SAME rowSpan (they occupy the same row
                // range) - the taller of the two governs.
                val span = maxOf(preset.rowSpan, nextPreset.rowSpan)
                items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = span, colSpan = preset.colSpan))
                items.add(GridItem(id = next.id, row = row, col = preset.colSpan, rowSpan = span, colSpan = nextPreset.colSpan))
                row += span
                i += 2
            } else {
                items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = preset.rowSpan, colSpan = preset.colSpan))
                row += preset.rowSpan
                i += 1
            }
        }
    }
    return items
}

/**
 * One page's stage-2 body: a true 2D [DeckGrid] over that page's own [PrototypeWidget] fixtures,
 * rendering each cell through [WidgetBody] inside a [DeckPane] shell (matching the deleted stage-1
 * `WidgetCard`'s own DeckPane-plus-title read, minus the stage-1-only drag-handle/size-toggle
 * chrome that [DeckGrid] now supplies itself).
 *
 * Widget geometry (`row`/`col`/`rowSpan`/`colSpan`) lives ENTIRELY in this page's own [gridItems] -
 * [PrototypeWidget] itself contributes only its id/kind/title, exactly the "persistence boundary"
 * split ticket 18 draws: this harness's in-memory `mutableStateListOf` stands in for the
 * `widget_instances` table a later ticket wires for real. Keyed on `widgets` (via `remember`), so
 * HOME/FLEET/LEDGER each get their own independent grid state even though they call this same
 * composable.
 */
@Composable
fun PrototypeGridPage(widgets: List<PrototypeWidget>, editMode: Boolean, onEnterEditMode: () -> Unit) {
    val widgetsById = remember(widgets) { widgets.associateBy { it.id } }
    val gridItems = remember(widgets) { mutableStateListOf(*initialGridItems(widgets).toTypedArray()) }

    DeckGrid(
        items = gridItems,
        columnCount = PROTOTYPE_GRID_COLUMNS,
        editMode = editMode,
        onEnterEditMode = onEnterEditMode,
        onLayoutChange = { updated ->
            gridItems.clear()
            gridItems.addAll(updated)
        },
        onRemove = { id -> gridItems.removeAll { it.id == id } },
        presetsFor = { item -> widgetsById[item.id]?.kind?.supportedPresets ?: listOf(GridPreset.SMALL) },
        modifier = Modifier.fillMaxWidth(),
    ) { item ->
        val widget = widgetsById[item.id] ?: return@DeckGrid
        // The card's CURRENT preset comes from the grid's own live geometry ([GridPreset.match]),
        // never from [PrototypeWidget.initialPreset] - that field seeds the very first layout only
        // (see its own doc) and is never updated by a size-chip tap. A `null` match (should not
        // happen once every fixture starts on a supported preset) falls back to the widget's own
        // first supported preset, same "start over" posture as [GridEngine.nextPreset].
        val preset = GridPreset.match(item) ?: widget.kind.supportedPresets.first()
        DeckPane(
            header = widget.title,
            headerAccent = widget.kind.name.replace('_', ' '),
            modifier = Modifier.fillMaxWidth(),
        ) {
            WidgetBody(widget, preset)
        }
    }
}

/** How many rows of a multi-row widget's own fixture list to actually render at [preset] - the
 *  "content adapts per preset, a card always exactly fills its rect" rule (ticket brief point 3).
 *  [GridPreset.WIDE] (one row tall) gets a compact 2-row read; [GridPreset.LARGE] (two rows tall)
 *  gets up to 4. Only [PrototypeWidgetKind.RECORD_LIST]/[PrototypeWidgetKind.AGENDA] have more than
 *  one row of content to begin with - every other kind ignores this and renders its fixed content
 *  regardless of [preset], since [PrototypeWidgetKind.supportedPresets] never gives them room to
 *  show more or less. */
private fun PrototypeWidgetKind.maxRowsFor(preset: GridPreset): Int = when (this) {
    PrototypeWidgetKind.RECORD_LIST, PrototypeWidgetKind.AGENDA -> if (preset == GridPreset.LARGE) 4 else 2
    else -> Int.MAX_VALUE
}

/** Fake per-kind widget body - moved here from the deleted stage-1 `PrototypeReorder.kt`, whose
 *  only remaining caller was this file once every page moved to stage 2. Never sourced from a
 *  controller; see [PrototypeData.kt]'s file doc.
 *
 *  [preset] is the card's CURRENT [GridPreset] (not necessarily [widget]'s
 *  [PrototypeWidget.initialPreset] - see [PrototypeGridPage]'s own call site), threaded through so
 *  a multi-row kind can render exactly as many rows as [maxRowsFor] says its current size has room
 *  for - "a card always exactly fills its preset rect," never more, never a clipped remainder. */
@Composable
internal fun WidgetBody(widget: PrototypeWidget, preset: GridPreset) {
    val sem = LocalLegionSemantics.current
    when (widget.kind) {
        PrototypeWidgetKind.STAT_TILE -> {
            // SMALL is STAT_TILE's only supported preset (see supportedPresets) - value only, no
            // per-size branching needed.
            val (value, sub) = PrototypeFixtures.statValue(widget.id)
            Text(value, style = LegionType.amount, color = sem.data)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = sem.faint)
        }
        PrototypeWidgetKind.RECORD_LIST -> {
            Column {
                PrototypeFixtures.recordRows(widget.id).take(widget.kind.maxRowsFor(preset)).forEach { (label, value) ->
                    DeckRow(label = label, value = value)
                }
            }
        }
        PrototypeWidgetKind.NEXT_DUE -> {
            val (name, due) = PrototypeFixtures.nextDue(widget.id)
            DeckRow(label = name, value = due)
        }
        PrototypeWidgetKind.QUICK_ADD -> {
            // SMALL is QUICK_ADD's only supported preset - a single button, no per-size branching.
            DeckButton(text = widget.title, onClick = {})
        }
        PrototypeWidgetKind.AGENDA -> {
            Column {
                PrototypeFixtures.agendaRows(widget.id).take(widget.kind.maxRowsFor(preset)).forEach { (time, what) ->
                    DeckRow(label = time, value = what)
                }
            }
        }
    }
}
