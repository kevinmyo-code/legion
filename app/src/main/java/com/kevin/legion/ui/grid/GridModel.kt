package com.kevin.legion.ui.grid

/**
 * Stage-2 grid mechanics (aspect-engine ticket 18, priced by the ticket 09 prototype spike). This
 * file is the whole cell model: pure Kotlin, zero Compose/Android imports, zero Room dependency -
 * the persistence boundary the ticket calls for is exactly this file's public surface. A caller
 * (the prototype harness today, `widget_instances` later) hands in and gets back a plain
 * `List<GridItem>`; nothing here knows or cares where that list came from or where it is going.
 *
 * **Shape, deliberately react-grid-layout's**: every widget occupies a rectangle of cells on a
 * fixed column count, drag/resize propose a new rectangle for ONE item, [GridEngine] pushes
 * anything that rectangle now overlaps straight down (never sideways - vertical-only push, same
 * as react-grid-layout's default `verticalCompact` strategy), and a compaction pass pulls every
 * item as far up as it can go without re-colliding. Nothing here is animated or drawn - see
 * `DeckGrid.kt` for the Compose layer that renders a `List<GridItem>` as cell rects and drives
 * these functions from drag/resize gestures.
 */

/**
 * One widget instance's placement. `row`/`col` are zero-based cell coordinates; `rowSpan`/
 * `colSpan` are cell counts, both floored at 1 (a 0-height or 0-width widget is not a widget).
 * `id` is the caller's own stable identity (a `widget_instances` row id, eventually) - [GridEngine]
 * never invents or reassigns it, only the four geometry fields.
 */
data class GridItem(
    val id: String,
    val row: Int,
    val col: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
)

/**
 * The pure occupancy-map algorithm. Every function takes a `List<GridItem>` and a fixed
 * `columnCount` and returns a NEW `List<GridItem>` - nothing here mutates its input, and nothing
 * here is a `@Composable`. Order of the returned list is not meaningful (callers key by `id`);
 * [normalize] and [compact] both re-sort internally before laying out but do not promise the
 * output list's own iteration order matches that internal sort.
 */
object GridEngine {

    /**
     * True when [a] and [b]'s cell rectangles overlap on both axes. Two items with the same `id`
     * never collide (a defensive equality check, not something the callers below rely on since
     * they always pass disjoint id sets, but cheap to guarantee here rather than assume upstream).
     */
    fun collides(a: GridItem, b: GridItem): Boolean {
        if (a.id == b.id) return false
        val rowOverlap = a.row < b.row + b.rowSpan && b.row < a.row + a.rowSpan
        val colOverlap = a.col < b.col + b.colSpan && b.col < a.col + a.colSpan
        return rowOverlap && colOverlap
    }

    /**
     * Clamp one item's geometry into a legal shape on a grid of [columnCount] columns: spans
     * floored at 1 (min-size 1x1, the ticket's own phrase), `colSpan` ceilinged at [columnCount]
     * (a widget can never be wider than the grid it lives on), `col` pulled left just far enough
     * that `col + colSpan` never exceeds [columnCount] (an out-of-bounds column, not just an
     * oversized span), and `row` floored at 0. Never touches another item - collisions this
     * clamp's own bounds now cause are [resolveCollisions]'s job, not this function's.
     */
    fun clampToBounds(item: GridItem, columnCount: Int): GridItem {
        val colSpan = item.colSpan.coerceIn(1, columnCount.coerceAtLeast(1))
        val col = item.col.coerceIn(0, (columnCount - colSpan).coerceAtLeast(0))
        val rowSpan = item.rowSpan.coerceAtLeast(1)
        val row = item.row.coerceAtLeast(0)
        return item.copy(row = row, col = col, rowSpan = rowSpan, colSpan = colSpan)
    }

    /**
     * Push every item in [others] straight down until none of them overlaps [fixed] or each
     * other, processing top-to-bottom (row then col) so a chain of stacked items cascades in the
     * order they would visually fall. An item is never moved sideways - only its `row` changes,
     * matching react-grid-layout's default vertical-push behaviour, which is what makes the result
     * predictable for both a drag (the moved item is [fixed]) and a resize (the resized item is
     * [fixed]).
     *
     * Each candidate is re-checked in a loop against the growing "settled" set (starting with
     * [fixed]) rather than a single pass, because pushing a candidate below one collider can land
     * it inside a second, already-settled item lower down the same column - the loop keeps
     * dropping it until no settled item overlaps it, which is what makes a three-deep stack
     * resolve in one call rather than needing the caller to call this repeatedly.
     */
    fun resolveCollisions(fixed: GridItem, others: List<GridItem>): List<GridItem> {
        val settled = mutableListOf(fixed)
        val sorted = others.sortedWith(compareBy({ it.row }, { it.col }))
        val result = mutableListOf<GridItem>()
        for (item in sorted) {
            var candidate = item
            var moved = true
            while (moved) {
                moved = false
                val collider = settled.firstOrNull { collides(it, candidate) }
                if (collider != null) {
                    candidate = candidate.copy(row = collider.row + collider.rowSpan)
                    moved = true
                }
            }
            settled.add(candidate)
            result.add(candidate)
        }
        return result
    }

    /**
     * Pull every item up as far as it can go without colliding with an item processed earlier in
     * the same pass - the "compaction pulls everything up" half of the mechanic. Items are
     * processed in row-then-col order, so an item higher and further left always gets first claim
     * on the space above it; a later item can compact only as far as the ones already placed
     * allow, which is what keeps the result stable and non-overlapping in one pass with no
     * further collision resolution needed afterward.
     *
     * [pinnedId], when non-null, names the ONE item [moveTo]/[resize] just placed deliberately -
     * it is still considered by every other item's compaction (nothing compacts THROUGH it), but
     * it never itself moves. Without this, a drag that lands a widget on an otherwise-empty row 5
     * would immediately compact it back up to row 0 the instant the gesture ends, which reads as
     * the grid silently overriding the user's own drop rather than making room for it - the whole
     * point of "drag-to-move" is that the moved item's OWN row is deliberate, only the others
     * reflow around it. [normalize] has no such anchor (a freshly-loaded list has no "the user just
     * placed this one" item) and calls this with `pinnedId = null`, compacting everything.
     */
    fun compact(items: List<GridItem>, pinnedId: String? = null): List<GridItem> {
        val sorted = items.sortedWith(compareBy({ it.row }, { it.col }))
        val settled = mutableListOf<GridItem>()
        for (item in sorted) {
            if (item.id == pinnedId) {
                settled.add(item)
                continue
            }
            var candidateRow = item.row
            while (candidateRow > 0) {
                val probe = item.copy(row = candidateRow - 1)
                if (settled.any { collides(it, probe) }) break
                candidateRow -= 1
            }
            settled.add(item.copy(row = candidateRow))
        }
        return settled
    }

    /**
     * Defensive entry point for a list a caller did NOT build through this object - e.g. rows read
     * back from `widget_instances`, or the harness's hand-typed fixtures. Clamps every item's own
     * geometry into bounds ([clampToBounds]), resolves any collisions the raw data might already
     * contain by pushing later-sorted items down out of earlier ones' way, then compacts. Idempotent:
     * normalizing an already-normalized list returns it unchanged (proven in
     * `GridEngineTest.normalize is idempotent`).
     */
    fun normalize(items: List<GridItem>, columnCount: Int): List<GridItem> {
        val clamped = items.map { clampToBounds(it, columnCount) }
        val sorted = clamped.sortedWith(compareBy({ it.row }, { it.col }))
        val settled = mutableListOf<GridItem>()
        for (item in sorted) {
            var candidate = item
            var moved = true
            while (moved) {
                moved = false
                val collider = settled.firstOrNull { collides(it, candidate) }
                if (collider != null) {
                    candidate = candidate.copy(row = collider.row + collider.rowSpan)
                    moved = true
                }
            }
            settled.add(candidate)
        }
        return compact(settled)
    }

    /**
     * The drag-to-move mechanic: move item [id] so its top-left lands at ([targetRow], [targetCol]),
     * clamped to the grid's own bounds (never off the left/right edge, never above row 0 - there is
     * no bottom bound, the grid simply grows), push whatever it now overlaps straight down
     * ([resolveCollisions]), then [compact] the result so the move never leaves a gap behind it.
     * Returns [items] unchanged if [id] is not present - a caller racing a remove against an
     * in-flight drag gets a no-op, not a crash.
     */
    fun moveTo(items: List<GridItem>, id: String, targetRow: Int, targetCol: Int, columnCount: Int): List<GridItem> {
        val current = items.firstOrNull { it.id == id } ?: return items
        val colSpan = current.colSpan.coerceIn(1, columnCount.coerceAtLeast(1))
        val col = targetCol.coerceIn(0, (columnCount - colSpan).coerceAtLeast(0))
        val row = targetRow.coerceAtLeast(0)
        val moved = current.copy(row = row, col = col, colSpan = colSpan)
        val others = items.filter { it.id != id }
        val pushed = resolveCollisions(moved, others)
        return compact(pushed + moved, pinnedId = id)
    }

    /**
     * The corner-resize mechanic: set item [id]'s span to ([newRowSpan], [newColSpan]), clamped to
     * the ticket's own "min-size 1x1" floor and to the grid's own bounds (a `colSpan` that would
     * push `col + colSpan` past [columnCount] is clamped down to whatever still fits at the item's
     * CURRENT `col`, since a resize never relocates the item's anchor corner), push whatever the new
     * footprint now overlaps down, then compact. Returns [items] unchanged if [id] is not present.
     */
    fun resize(items: List<GridItem>, id: String, newRowSpan: Int, newColSpan: Int, columnCount: Int): List<GridItem> {
        val current = items.firstOrNull { it.id == id } ?: return items
        val rowSpan = newRowSpan.coerceAtLeast(1)
        val maxColSpanHere = (columnCount - current.col).coerceAtLeast(1)
        val colSpan = newColSpan.coerceIn(1, maxColSpanHere)
        val resized = current.copy(rowSpan = rowSpan, colSpan = colSpan)
        val others = items.filter { it.id != id }
        val pushed = resolveCollisions(resized, others)
        return compact(pushed + resized, pinnedId = id)
    }

    /** Drop item [id] entirely and pull everything else up to close the gap it leaves. A no-op
     *  (returns [items] unchanged) if [id] is not present. */
    fun remove(items: List<GridItem>, id: String): List<GridItem> {
        if (items.none { it.id == id }) return items
        return compact(items.filter { it.id != id })
    }

    /** How many rows the grid needs to show every item - the tallest occupied row plus one, or 0
     *  for an empty grid. The Compose layer uses this to size its own container; nothing here
     *  assumes a Compose caller, it is just an integer derived from the same geometry. */
    fun rowCount(items: List<GridItem>): Int = items.maxOfOrNull { it.row + it.rowSpan } ?: 0
}
