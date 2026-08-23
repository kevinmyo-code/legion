package com.kevin.legion.ui.grid

/**
 * Stage-2 grid mechanics (aspect-engine ticket 18, priced by the ticket 09 prototype spike). This
 * file is the whole cell model: pure Kotlin, zero Compose/Android imports, zero Room dependency -
 * the persistence boundary the ticket calls for is exactly this file's public surface. A caller
 * (the prototype harness today, `widget_instances` later) hands in and gets back a plain
 * `List<GridItem>`; nothing here knows or cares where that list came from or where it is going.
 *
 * **Three generations of interactive semantics live here, on purpose (2026-08-23, three feel-test
 * passes in one day).** The first build gave drag/resize react-grid-layout's own shape: propose a
 * rectangle, [resolveCollisions] pushes whatever it now overlaps straight down, [compact] pulls
 * the rest back up. Kevin: "drag and reflow on home page is not very intuitive." A rework made the
 * reflow live instead of drop-only; still rejected: "still doesnt feel good. ditch reflow. just
 * snap to grid." The THIRD pass tried outright rejection of any occupied target (accept exactly as
 * proposed or refuse and animate home, no reflow at all) - closer, but still wrong: "it doesnt
 * replace or move the items in the grid up if something is already there." **The interactive path
 * (drag-to-move, corner-resize, both driven from `DeckGrid.kt`) is now DISPLACE, not reject**: a
 * candidate rectangle is always accepted exactly as proposed, and whatever it overlaps is
 * relocated to the nearest free space (upward first) rather than the drop being refused. That is
 * [displaceForPlacement] below, built on [clampMoveTarget] / [clampResizeTarget] / [collides].
 * [overlapsAny] / [commitIfValid] (the pure "accept-or-reject-outright" pair from the THIRD
 * generation) are kept - same posture as [moveTo]/[resize] below - as a stricter primitive a
 * future caller that must never disturb anything else (a paste, an undo) might still want; they
 * are no longer called by `DeckGrid.kt`'s interactive gestures either.
 *
 * **[resolveCollisions], [compact], [moveTo], [resize], [overlapsAny], and [commitIfValid] are NOT
 * deleted** - `ticket 18` may still want an explicit "auto-arrange" tidy-up action later (a
 * user-invoked "pack my widgets" command is a different feature from an involuntary reflow
 * mid-drag), and [normalize] still leans on [resolveCollisions]/[compact] to make sense of
 * untrusted input (a raw `widget_instances` read, or this harness's hand-typed fixtures) that
 * might arrive already overlapping. But as of 2026-08-23, **the only functions actually called by
 * `DeckGrid.kt`'s live drag/resize gestures are [clampMoveTarget], [clampResizeTarget], and
 * [displaceForPlacement]** - everything else in this generational list is kept-but-unused-by-the-
 * interactive-path; see each function's own KDoc for its specific status.
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
 * **The FOURTH generation of interactive semantics (2026-08-23, same day, after the seventh
 * feel-test pass confirmed drag/drop itself was solid).** Kevin: "drag and drop works. but card
 * sizing is not accurate. lets just implement set size cards. like android widgets." Free
 * corner-resize - drag a handle, land on whatever `(rowSpan, colSpan)` the pointer happened to
 * stop at - is RETIRED from the interactive path entirely. In its place, every widget declares a
 * fixed subset of this catalog it supports (an Android app-widget's own "this widget comes in
 * these sizes, no others" contract), and edit mode's former resize handle is now a SIZE CHIP that
 * cycles the selected card through its own kind's supported presets on tap, one preset per tap,
 * wrapping past the last back to the first.
 *
 * Four presets on the 4-column phone grid, chosen to span the two shapes the mocks actually need
 * (a short single-row card and a multi-row list) at both half and full width - `colSpan` first,
 * `rowSpan` second, matching [GridItem]'s own field order:
 * - [SMALL] `2x1` - a stat tile, a quick-add button: one short row, half the grid's width.
 * - [WIDE] `4x1` - a compact full-width row: next-due, a short single-record summary.
 * - [TALL] `2x2` - reserved headroom for a half-width widget that needs two rows; no v1 mock
 *   claims it yet, kept in the catalog because the ticket's own brief names it explicitly.
 * - [LARGE] `4x2` - a full-width, two-row-tall list: an agenda or a record list with room for
 *   several entries.
 *
 * A widget's declared subset is NOT stored on [GridItem] itself - [GridItem] stays a pure
 * geometry record with zero knowledge of "widget kinds," same posture as the rest of this file.
 * The subset lives with whatever knows what a "record list" or a "stat tile" is (today,
 * `PrototypeWidgetKind.supportedPresets` in the debug-only harness; the real `widget_instances`
 * catalog's job later) and is handed to [GridEngine.nextPreset] and to `DeckGrid`'s own
 * `presetsFor` parameter as a plain `List<GridPreset>`.
 *
 * **[GridEngine.clampResizeTarget] and [GridEngine.resize] are now unused by BOTH the old free-drag
 * path (already true before this change) AND the new preset-cycle path** - a preset never shrinks
 * to fit, it relocates ([GridEngine.clampPresetTarget] reuses [GridEngine.clampMoveTarget] instead,
 * preserving the full declared span and only ever adjusting `col`). They are kept for the same
 * "future auto-arrange/paste primitive" reason [GridEngine.moveTo] is kept - see that function's
 * own doc - not because anything still calls them from `DeckGrid.kt`.
 */
enum class GridPreset(val colSpan: Int, val rowSpan: Int, val label: String) {
    SMALL(colSpan = 2, rowSpan = 1, label = "S"),
    WIDE(colSpan = 4, rowSpan = 1, label = "W"),
    TALL(colSpan = 2, rowSpan = 2, label = "T"),
    LARGE(colSpan = 4, rowSpan = 2, label = "L");

    companion object {
        /** The catalog entry matching [item]'s CURRENT geometry exactly, or `null` if it does not
         *  match any preset at all - a stale fixture, or an item still holding a leftover free-resize
         *  span from before this generation. A caller (`DeckGrid`'s content lambda, deciding how much
         *  to render) falls back to its own default in the `null` case; [GridEngine.nextPreset] falls
         *  back to the first SUPPORTED preset rather than throwing, for the identical reason. */
        fun match(item: GridItem): GridPreset? = entries.firstOrNull { it.colSpan == item.colSpan && it.rowSpan == item.rowSpan }
    }
}

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
     *
     * **"Idempotent" is not "identity" - do not call this on every recomposition (fifth feel-test
     * pass, 2026-08-23).** [compact]'s own unconditional pull-everything-up pass means a perfectly
     * valid, collision-free, DELIBERATELY GAPPED layout does NOT round-trip through this function
     * untouched - a card sitting alone at row 5 with nothing above it moves to row 0. That is
     * correct and by design for genuinely untrusted FIRST input, and it is exactly what broke
     * "drop into an empty space" on the A25: an earlier draft of `DeckGrid.kt` called this on EVERY
     * recomposition (including the one right after each commit), which silently relocated the
     * user's OWN just-placed card the instant it landed. **This function is now reserved for the
     * ONE-TIME pass over whatever `items` looked like on a `DeckGrid` instance's FIRST-EVER
     * composition** - see that file's own `baseItems` doc for the fix. Proven at this layer by
     * `GridEngineTest.normalize does NOT round-trip a valid gapped layout untouched`.
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
     * The OLD (2026-08-23, first build) drag-to-move mechanic: move item [id] so its top-left lands
     * at ([targetRow], [targetCol]), clamped to the grid's own bounds, push whatever it now overlaps
     * straight down ([resolveCollisions]), then [compact] the result so the move never leaves a gap
     * behind it. Returns [items] unchanged if [id] is not present.
     *
     * **NOT called by `DeckGrid.kt`'s interactive drag gesture as of the SAME day's rework** - two
     * feel-test passes rejected the reflow this produces (see the file doc). Kept as a candidate
     * "auto-arrange" primitive for a future EXPLICIT tidy-up action, which is a different feature
     * from an involuntary push mid-drag. The interactive path uses [clampMoveTarget] +
     * [commitIfValid], which never pushes another item and rejects outright instead.
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
     * The OLD (2026-08-23, first build) corner-resize mechanic: set item [id]'s span to
     * ([newRowSpan], [newColSpan]), clamped to the ticket's own "min-size 1x1" floor and to the
     * grid's own bounds, push whatever the new footprint now overlaps down, then compact. Returns
     * [items] unchanged if [id] is not present.
     *
     * **NOT called by `DeckGrid.kt`'s interactive resize gesture** - same reasoning and same day's
     * rework as [moveTo]'s doc. Kept as a candidate auto-arrange primitive. The interactive path
     * uses [clampResizeTarget] + [commitIfValid].
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

    // -------------------------------------------------------- interactive (no-reflow) placement

    /**
     * Clamp a MOVE target into legal bounds - the bounds half of the old [moveTo]'s clamping,
     * kept because "bounds-clamping stays" (2026-08-23 rework): `col` pulled left so `col + colSpan`
     * never exceeds [columnCount], `row` floored at 0. Deliberately does NOT check for a collision
     * with another item - that is [overlapsAny]'s job, kept separate so a caller (`DeckGrid.kt`) can
     * render the clamped candidate rect as a snap-preview outline BEFORE deciding whether it is
     * valid, which is exactly what an Android launcher's own drag preview does.
     */
    fun clampMoveTarget(item: GridItem, targetRow: Int, targetCol: Int, columnCount: Int): GridItem {
        val colSpan = item.colSpan.coerceIn(1, columnCount.coerceAtLeast(1))
        val col = targetCol.coerceIn(0, (columnCount - colSpan).coerceAtLeast(0))
        val row = targetRow.coerceAtLeast(0)
        return item.copy(row = row, col = col, colSpan = colSpan)
    }

    /**
     * Clamp a RESIZE target into legal bounds - the min-size-1x1-and-never-exceed-the-grid half of
     * the old [resize]'s clamping, same "bounds-clamping stays" posture as [clampMoveTarget]. A
     * resize never relocates the item's anchor corner, so `colSpan` is capped at whatever still
     * fits starting from the item's CURRENT `col`, not re-anchored. No collision check here either -
     * see [clampMoveTarget]'s doc for why that stays a separate step.
     */
    fun clampResizeTarget(item: GridItem, newRowSpan: Int, newColSpan: Int, columnCount: Int): GridItem {
        val rowSpan = newRowSpan.coerceAtLeast(1)
        val maxColSpanHere = (columnCount - item.col).coerceAtLeast(1)
        val colSpan = newColSpan.coerceIn(1, maxColSpanHere)
        return item.copy(rowSpan = rowSpan, colSpan = colSpan)
    }

    /**
     * Clamp a PRESET-CYCLE target into legal bounds - the fourth-generation replacement for a
     * free resize, see [GridPreset]'s own doc for the full "like android widgets" rationale.
     * Unlike [clampResizeTarget], this never shrinks anything: [item] takes on [preset]'s exact
     * `(colSpan, rowSpan)` in full, and only its `col` is adjusted (via [clampMoveTarget], reused
     * rather than reimplemented) if the preset's own width would otherwise run the card off the
     * grid's right edge - a preset is a fixed shape, not a negotiable span, exactly like an
     * Android home-screen widget's declared size. `row` is left exactly as [item] already has it;
     * [GridEngine.displaceForPlacement] (the caller's next step, same as every other interactive
     * commit in this file) is what makes room for the resulting rectangle among whatever it now
     * overlaps.
     */
    fun clampPresetTarget(item: GridItem, preset: GridPreset, columnCount: Int): GridItem {
        val resized = item.copy(colSpan = preset.colSpan, rowSpan = preset.rowSpan)
        return clampMoveTarget(resized, targetRow = resized.row, targetCol = resized.col, columnCount)
    }

    /**
     * The next preset in [supported]'s own declared order, wrapping past the end back to the
     * first - the whole "tap the size chip" interaction. [supported] must not be empty (a widget
     * kind with no supported preset at all is a fixture bug, not a runtime case to degrade
     * gracefully for). If [current] is not itself one of [supported] - should not happen once
     * every fixture starts on a supported preset, but a caller cannot prove that statically -
     * cycling starts from the FIRST supported preset rather than throwing, since "the current
     * size is unrecognised" reads closer to "start over" than to an error.
     */
    fun nextPreset(current: GridPreset, supported: List<GridPreset>): GridPreset {
        require(supported.isNotEmpty()) { "a widget kind must support at least one preset" }
        val index = supported.indexOf(current)
        return if (index == -1) supported.first() else supported[(index + 1) % supported.size]
    }

    /** True when [candidate] collides with anything in [others] - the validity half of the
     *  Android-launcher "legal, unoccupied spot or reject" rule. [others] is expected to already
     *  exclude [candidate]'s own id (both [clampMoveTarget]/[clampResizeTarget] preserve the
     *  original `id`), but [collides] is itself id-safe regardless. */
    fun overlapsAny(candidate: GridItem, others: List<GridItem>): Boolean = others.any { collides(it, candidate) }

    /**
     * Commit [candidate] into [items] - replacing whichever existing entry shares its `id` - IF AND
     * ONLY IF it does not overlap anything else in [items]. Returns `null` (never a mutated or
     * partial list) when it does, which is the whole "occupied target = invalid" rule: the caller
     * is expected to treat a `null` result as a rejected drop and leave the item exactly where it
     * was, never to fall back to some other placement. Returns [items] unchanged, wrapped in a
     * non-null result, if [candidate]'s id is not present at all (a caller racing a remove against
     * an in-flight drag/resize) - `commitIfValid` cannot silently invent a row that was never there.
     */
    fun commitIfValid(items: List<GridItem>, candidate: GridItem): List<GridItem>? {
        if (items.none { it.id == candidate.id }) return items
        val others = items.filter { it.id != candidate.id }
        if (overlapsAny(candidate, others)) return null
        return items.map { if (it.id == candidate.id) candidate else it }
    }

    /**
     * **DISPLACE, not reject (2026-08-23, third feel-test pass).** Kevin: "it doesnt replace or
     * move the items in the grid up if something is already there" - [commitIfValid]'s outright
     * rejection of any overlap was too strict. This is its replacement for the interactive
     * drag/resize path: [candidate] (already clamped into bounds by [clampMoveTarget]/
     * [clampResizeTarget] - this function trusts that, it does not re-clamp) is accepted EXACTLY
     * as proposed, and every item it now overlaps is relocated to the nearest free space that
     * fits it - searching UPWARD first (rows above its own, closest first), then DOWNWARD
     * (its own row and below, closest first) - scanning columns left to right at each row
     * candidate. A relocation can itself displace a THIRD item it now collides with (the knock-on
     * chain); an item never queued for relocation - because neither [candidate] nor any moved
     * item ever overlapped it - never moves, byte-for-byte.
     *
     * Returns `null`, the whole displacement wrapped, if NO arrangement fits - the only case this
     * can actually happen in practice is a displaced item whose own `colSpan` exceeds
     * [columnCount] (row search is unbounded, so a downward slot always exists eventually; column
     * width is the one dimension that can be genuinely impossible). The caller ([DeckGrid]'s drag/
     * resize gestures) treats `null` exactly as [commitIfValid] used to treat any overlap: leave
     * [items] untouched, animate the dragged/resized card back to where it started.
     *
     * Returns [items] unchanged, wrapped in a non-null result, if [candidate]'s id is not present
     * at all (same "cannot invent a row that was never there" contract as [commitIfValid]).
     */
    fun displaceForPlacement(items: List<GridItem>, candidate: GridItem, columnCount: Int): List<GridItem>? {
        if (items.none { it.id == candidate.id }) return items
        if (candidate.colSpan < 1 || candidate.colSpan > columnCount) return null
        if (candidate.col < 0 || candidate.col + candidate.colSpan > columnCount || candidate.row < 0) return null

        val others = items.filter { it.id != candidate.id }.associateBy { it.id }.toMutableMap()
        val queue = ArrayDeque<String>()
        val queued = mutableSetOf<String>()
        for (o in others.values) {
            if (collides(candidate, o)) {
                queue.add(o.id)
                queued.add(o.id)
            }
        }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val current = others.getValue(id)
            // Everything NOT being relocated right now is an obstacle: the candidate itself, plus
            // every other item at its CURRENT best-known position (original if not yet touched,
            // already-relocated if a prior step in this same call moved it).
            val avoid = others.values.filter { it.id != id } + candidate
            val slot = findFreeSlot(current.rowSpan, current.colSpan, avoid, columnCount, searchFromRow = current.row)
                ?: return null
            val moved = current.copy(row = slot.first, col = slot.second)
            others[id] = moved
            // Knock-on: anything not already queued/settled that the FRESHLY moved item now
            // overlaps must also relocate - checked against every other item's current
            // best-known position, same as the avoid-set above.
            for (o in others.values) {
                if (o.id == id || o.id in queued) continue
                if (collides(moved, o)) {
                    queue.add(o.id)
                    queued.add(o.id)
                }
            }
        }
        return listOf(candidate) + others.values.toList()
    }

    /**
     * The nearest legal (rowSpan x colSpan) rectangle that collides with nothing in [avoid],
     * starting the search from [searchFromRow] and preferring UPWARD (searchFromRow - 1 downTo 0,
     * closest row first) before DOWNWARD (searchFromRow upward, closest row first) - see
     * [displaceForPlacement]'s own doc for why this order and not an expanding-ring search.
     * Columns are scanned left to right (0..columnCount-colSpan) at each candidate row; this
     * function does not attempt to prefer a column nearest the item's own original column, since
     * ticket 18's brief specified row order only.
     *
     * The downward search is bounded, not literally infinite, but the bound
     * (`max occupied bottom edge in [avoid] + item count + rowSpan + 2`) is constructed to be
     * provably sufficient - a rectangle placed below every avoided item's bottom edge cannot
     * collide with any of them - so in practice this only returns `null` via the `colSpan`
     * checked by [displaceForPlacement] before calling this at all (or a non-positive [colSpan]
     * passed directly, exercised by `GridEngineTest`).
     */
    private fun findFreeSlot(rowSpan: Int, colSpan: Int, avoid: List<GridItem>, columnCount: Int, searchFromRow: Int): Pair<Int, Int>? {
        if (colSpan < 1 || colSpan > columnCount || rowSpan < 1) return null
        val maxCol = columnCount - colSpan
        fun fitsAt(row: Int, col: Int): Boolean {
            val probe = GridItem("__probe__", row, col, rowSpan, colSpan)
            return avoid.none { collides(it, probe) }
        }
        for (row in (searchFromRow - 1) downTo 0) {
            for (col in 0..maxCol) {
                if (fitsAt(row, col)) return row to col
            }
        }
        val downwardBound = (avoid.maxOfOrNull { it.row + it.rowSpan } ?: 0) + avoid.size + rowSpan + 2
        for (row in searchFromRow..downwardBound) {
            for (col in 0..maxCol) {
                if (fitsAt(row, col)) return row to col
            }
        }
        return null
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

/**
 * The pointer-to-cell mapping, extracted into its own pure, unit-testable function (sixth
 * feel-test pass, 2026-08-23). Kevin, reproducing over adb with screenshots: dragging ANY
 * full-width card any distance in any direction always committed at row 0, col 0.
 *
 * **Root cause, exact line, stated plainly.** `DeckGrid.kt`'s `onMoveDragEnd` computed the commit
 * candidate with two adjacent, UNNAMED `run { }` blocks passed positionally into
 * [GridEngine.clampMoveTarget]`(item, targetRow, targetCol, columnCount)`:
 * ```
 * val candidate = GridEngine.clampMoveTarget(current, run {
 *     val liveX = d.originCol * colPitchPx + d.accumPx.x
 *     floor(liveX / colPitchPx).toInt()
 * }, run {
 *     val liveY = d.originRow * rowPitchPx + d.accumPx.y
 *     floor(liveY / rowPitchPx).toInt().coerceAtLeast(0)
 * }, columnCount)
 * ```
 * The FIRST block - landing in the `targetRow` parameter slot - computes a COLUMN value (`liveX`,
 * built from `colPitchPx`/`originCol`/`accumPx.x`). The SECOND block - landing in `targetCol` -
 * computes a ROW value (`liveY`, built from `rowPitchPx`/`originRow`/`accumPx.y`). Row and column
 * are transposed at this one call site. The live PREVIEW computation a few lines above (used for
 * the drag's own on-screen ghost outline) does the identical arithmetic correctly, naming its
 * results `candidateRow`/`candidateCol` and passing them in the right order - only the COMMIT path,
 * which duplicated the arithmetic inline instead of reusing one implementation, got the order
 * wrong. That is why the live outline during a drag looked right while the drop still reverted to
 * the wrong cell: two different, divergent computations of the "same" value.
 *
 * **Why this reads as "always (0,0)" specifically.** [GridEngine.clampMoveTarget] clamps its
 * `targetCol` argument into `[0, columnCount - colSpan]`. For a FULL-width card (`colSpan ==
 * columnCount`), that range is exactly `[0, 0]` - so whatever numeric value the swap put into the
 * `targetCol` slot clamps to 0 regardless of magnitude. The swap put the LARGE, correctly-computed
 * row-progress value there; it also put the near-zero column value (unchanged from `originCol`,
 * since every reproduction was a vertical-only drag) into `targetRow`, which floors at 0 and stays
 * ~0 for a card that started at column 0. Two independent near-misses that both happen to land on
 * 0 for a full-width card starting at col 0 - which is every widget in the reproduction.
 *
 * **The fix is this function.** Both the live preview and the commit now call
 * [GridGesture.candidateCell] - the SAME implementation, called from exactly two sites, both of
 * which pass its named, ordered result straight into [GridEngine.clampMoveTarget] with no
 * intervening inline arithmetic to get wrong a second time. This is the "three consecutive passes
 * shipped gesture-math bugs invisible to the JVM suite" fix: extracting the pixel-to-cell mapping
 * out of `DeckGrid.kt` (Compose, untestable without Robolectric/instrumentation) into this
 * dependency-free file means the arithmetic itself now has direct JVM coverage
 * (`GridGestureTest`), leaving only POINTER EVENT PLUMBING (`detectDragGestures` wiring, state
 * threading) as the untested remainder - which is the honest boundary of what a JVM test can reach
 * in a Compose app.
 */
object GridGesture {
    /**
     * The candidate (row, col) a drag is currently hovering, given the dragged item's ORIGIN cell
     * (captured once at gesture start, before any movement) and the cumulative RAW pointer delta
     * in pixels since. [colPitchPx]/[rowPitchPx] are the fixed per-cell pixel pitch (cell size plus
     * gap) - the same values [DeckGrid][com.kevin.legion.ui.grid] derives from its own measured
     * width and `rowHeight` parameter.
     *
     * No `Offset` type here on purpose - this file (unlike `DeckGrid.kt`) carries zero Compose
     * imports, so pixel deltas are plain `Float` x/y, exactly like every other geometry value in
     * this file. `col` is NOT clamped to `columnCount` here - that stays [GridEngine.clampMoveTarget]'s
     * job once the caller also knows the item's own `colSpan`; this function only answers "which
     * cell is the pointer over," never "is that cell legal for this item."
     *
     * @return `row to col`, row floored at 0 (never negative - there is no ceiling on rows, the
     *   grid simply grows), col NOT floored or ceilinged (an out-of-bounds column is a real,
     *   representable answer to "which cell is the pointer over," even left of column 0 or past
     *   the last column - clamping that away is the caller's job, not this function's).
     */
    fun candidateCell(
        originRow: Int,
        originCol: Int,
        accumPxX: Float,
        accumPxY: Float,
        colPitchPx: Float,
        rowPitchPx: Float,
    ): Pair<Int, Int> {
        val liveX = originCol * colPitchPx + accumPxX
        val liveY = originRow * rowPitchPx + accumPxY
        val col = kotlin.math.floor(liveX / colPitchPx).toInt()
        val row = kotlin.math.floor(liveY / rowPitchPx).toInt().coerceAtLeast(0)
        return row to col
    }
}
