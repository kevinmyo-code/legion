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

/** How many grid rows a widget KIND needs to show its own mock content without clipping - the
 *  fifth feel-test pass's defect 3 fix ("card content must clip cleanly... fit rows to the card
 *  height"). [PrototypeWidgetKind.AGENDA] and [PrototypeWidgetKind.RECORD_LIST] both render THREE
 *  [DeckRow]s (48dp each = 144dp) plus the pane's own header/padding chrome (~35dp), which does not
 *  fit inside one row's worth of the grid's default 132dp [com.kevin.legion.ui.grid.DeckGrid.rowHeight] -
 *  two rows (132*2 + the 10dp gap = 274dp) comfortably clears it. Every other kind's mock content
 *  fits one row. `DeckGrid` itself ALSO gained a defensive hard clip at the card boundary in the
 *  same rework (see its own file doc) so a future widget that still overflows its assigned rowSpan
 *  cuts cleanly rather than slicing a glyph in half - this table is the "fit rows to the card
 *  height" half of that fix, not the only line of defense. */
private fun rowSpanFor(kind: PrototypeWidgetKind): Int = when (kind) {
    PrototypeWidgetKind.AGENDA, PrototypeWidgetKind.RECORD_LIST -> 2
    else -> 1
}

/**
 * Hand-assigns an initial [GridItem] layout for [widgets], packing HALF widgets two-to-a-row and
 * FULL widgets to their own row(s) - the same greedy adjacency the deleted stage-1 column used, so
 * every page starts from a familiar arrangement even though the mechanics underneath are now
 * entirely stage 2. `colSpan` 2 for HALF, 4 (the whole grid) for FULL; `rowSpan` per [rowSpanFor] -
 * exercised for real (both dimensions) once edit mode's resize handle is used.
 */
private fun initialGridItems(widgets: List<PrototypeWidget>): List<GridItem> {
    val items = mutableListOf<GridItem>()
    var row = 0
    var i = 0
    while (i < widgets.size) {
        val w = widgets[i]
        if (w.size == PrototypeWidgetSize.FULL) {
            val span = rowSpanFor(w.kind)
            items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = span, colSpan = PROTOTYPE_GRID_COLUMNS))
            row += span
            i += 1
        } else {
            val next = widgets.getOrNull(i + 1)
            if (next != null && next.size == PrototypeWidgetSize.HALF) {
                // Two HALF widgets sharing a row must share the SAME rowSpan (they occupy the
                // same row range) - the taller of the two governs, per [rowSpanFor].
                val span = maxOf(rowSpanFor(w.kind), rowSpanFor(next.kind))
                items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = span, colSpan = 2))
                items.add(GridItem(id = next.id, row = row, col = 2, rowSpan = span, colSpan = 2))
                row += span
                i += 2
            } else {
                val span = rowSpanFor(w.kind)
                items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = span, colSpan = 2))
                row += span
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
        modifier = Modifier.fillMaxWidth(),
    ) { item ->
        val widget = widgetsById[item.id] ?: return@DeckGrid
        DeckPane(
            header = widget.title,
            headerAccent = widget.kind.name.replace('_', ' '),
            modifier = Modifier.fillMaxWidth(),
        ) {
            WidgetBody(widget)
        }
    }
}

/** Fake per-kind widget body - moved here from the deleted stage-1 `PrototypeReorder.kt`, whose
 *  only remaining caller was this file once every page moved to stage 2. Never sourced from a
 *  controller; see [PrototypeData.kt]'s file doc. */
@Composable
internal fun WidgetBody(widget: PrototypeWidget) {
    val sem = LocalLegionSemantics.current
    when (widget.kind) {
        PrototypeWidgetKind.STAT_TILE -> {
            val (value, sub) = PrototypeFixtures.statValue(widget.id)
            Text(value, style = LegionType.amount, color = sem.data)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = sem.faint)
        }
        PrototypeWidgetKind.RECORD_LIST -> {
            Column {
                PrototypeFixtures.recordRows(widget.id).forEach { (label, value) ->
                    DeckRow(label = label, value = value)
                }
            }
        }
        PrototypeWidgetKind.NEXT_DUE -> {
            val (name, due) = PrototypeFixtures.nextDue(widget.id)
            DeckRow(label = name, value = due)
        }
        PrototypeWidgetKind.QUICK_ADD -> {
            DeckButton(text = widget.title, onClick = {})
        }
        PrototypeWidgetKind.AGENDA -> {
            Column {
                PrototypeFixtures.agendaRows(widget.id).forEach { (time, what) ->
                    DeckRow(label = time, value = what)
                }
            }
        }
    }
}
