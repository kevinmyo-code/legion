package com.kevin.legion.prototype

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.grid.DeckGrid
import com.kevin.legion.ui.grid.GridItem

/**
 * Stage-2 harness (aspect-engine ticket 18, built from the ticket 09 spike Kevin authorized
 * 2026-08-23: "feels like a dashboard. build stage 2."). This is the feel-test half of the ticket
 * - the reusable mechanics themselves live in the PRODUCTION source set,
 * `ui/grid/GridModel.kt` + `ui/grid/DeckGrid.kt`, so ticket 18's real widget pager can reach for
 * them directly rather than porting anything out of `prototype/`. This file only wires that
 * component to the HOME page's mock widgets and hand-assigns an initial layout, exactly the
 * throwaway-fixture posture [PrototypeData.kt]'s own file doc states for the rest of this package.
 *
 * **Only the HOME page moves to stage 2.** FLEET and LEDGER stay on [ReorderableWidgetColumn]
 * (stage 1) deliberately, per the brief's "keep FLEET/LEDGER pages on stage 1 for comparison" -
 * Kevin reacting to both mechanics side by side on the same device is worth more than converting
 * every page at once.
 */

/** Four columns in portrait, per ticket 18's brief. Not read from anywhere else in this harness -
 *  a real screen would size this from the device's own width class, which is out of scope here. */
private const val HOME_GRID_COLUMNS = 4

/**
 * Hand-assigns an initial [GridItem] layout for [widgets], packing HALF widgets two-to-a-row and
 * FULL widgets to their own row - the exact same greedy adjacency [packRows] uses for the stage-1
 * column, so the two mechanics start from a visually equivalent arrangement and the only thing
 * that differs is what happens once you touch one. `colSpan` 2 for HALF, 4 (the whole grid) for
 * FULL; every widget starts 1 row tall - stage 1 never modeled row height at all, so "1 row tall"
 * is this harness's own new claim, exercised for real once edit mode's resize handle is used.
 */
private fun initialGridItems(widgets: List<PrototypeWidget>): List<GridItem> {
    val items = mutableListOf<GridItem>()
    var row = 0
    var i = 0
    while (i < widgets.size) {
        val w = widgets[i]
        if (w.size == PrototypeWidgetSize.FULL) {
            items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = 1, colSpan = HOME_GRID_COLUMNS))
            row += 1
            i += 1
        } else {
            val next = widgets.getOrNull(i + 1)
            if (next != null && next.size == PrototypeWidgetSize.HALF) {
                items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = 1, colSpan = 2))
                items.add(GridItem(id = next.id, row = row, col = 2, rowSpan = 1, colSpan = 2))
                row += 1
                i += 2
            } else {
                items.add(GridItem(id = w.id, row = row, col = 0, rowSpan = 1, colSpan = 2))
                row += 1
                i += 1
            }
        }
    }
    return items
}

/**
 * The HOME page's stage-2 body: a true 2D [DeckGrid] over the same [PrototypeWidget] fixtures the
 * stage-1 pages use, rendering each cell through the shared [WidgetBody] inside a [DeckPane] shell
 * (matching [WidgetCard]'s own DeckPane-plus-title read, minus the stage-1-only drag-handle/
 * size-toggle chrome that [DeckGrid] now supplies itself).
 *
 * Widget geometry (`row`/`col`/`rowSpan`/`colSpan`) lives ENTIRELY in [gridItems] -
 * [PrototypeWidget] itself contributes only its id/kind/title, exactly the "persistence boundary"
 * split ticket 18 draws: this harness's in-memory `mutableStateListOf` stands in for the
 * `widget_instances` table a later ticket wires for real.
 */
@Composable
fun PrototypeGridPage(widgets: List<PrototypeWidget>, editMode: Boolean, onEnterEditMode: () -> Unit) {
    val widgetsById = remember(widgets) { widgets.associateBy { it.id } }
    val gridItems = remember(widgets) { mutableStateListOf(*initialGridItems(widgets).toTypedArray()) }

    DeckGrid(
        items = gridItems,
        columnCount = HOME_GRID_COLUMNS,
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
