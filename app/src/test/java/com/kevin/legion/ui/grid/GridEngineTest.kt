package com.kevin.legion.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM coverage of [GridEngine] - the whole point of keeping the model free of Compose/Room
 * is that this file needs no Robolectric, no emulator, nothing but the JVM. Aspect-engine ticket
 * 18 (stage-2 grid mechanics): "this logic must not live only under fingers."
 *
 * **Three feel-test passes on the A25 (2026-08-23) retired push-and-compact reflow, then outright
 * rejection, from the INTERACTIVE drag/resize path** (`DeckGrid.kt` no longer calls
 * [GridEngine.moveTo]/[GridEngine.resize], and no longer calls [GridEngine.commitIfValid] either).
 * Their tests below, in sections marked "kept, unused by the interactive path", stay as direct
 * coverage of those functions - they are real, present code (kept on purpose per each day's
 * rework, in case a future explicit "auto-arrange" or "must never disturb anything else" caller
 * wants them), not dead code, so testing them directly remains honest coverage.
 * [GridEngine.compact]/[GridEngine.resolveCollisions] are exercised both directly AND through
 * [GridEngine.normalize] (their only remaining LIVE caller in the interactive path's own call
 * graph). **The live interactive path as of the THIRD pass is [GridEngine.clampMoveTarget] /
 * [GridEngine.clampResizeTarget] / [GridEngine.displaceForPlacement]** - a candidate is always
 * accepted exactly as proposed, and whatever it overlaps is relocated (upward first) rather than
 * the drop being refused. `displaceForPlacement`'s own test section is last in this file.
 */
class GridEngineTest {

    private fun item(id: String, row: Int, col: Int, rowSpan: Int = 1, colSpan: Int = 1) =
        GridItem(id, row, col, rowSpan, colSpan)

    // ---------------------------------------------------------------- collides

    @Test
    fun `collides is true for overlapping rects`() {
        val a = item("a", row = 0, col = 0, rowSpan = 2, colSpan = 2)
        val b = item("b", row = 1, col = 1, rowSpan = 2, colSpan = 2)
        assertTrue(GridEngine.collides(a, b))
        assertTrue(GridEngine.collides(b, a))
    }

    @Test
    fun `collides is false for adjacent non-overlapping rects`() {
        val a = item("a", row = 0, col = 0, rowSpan = 1, colSpan = 2)
        val b = item("b", row = 0, col = 2, rowSpan = 1, colSpan = 2)
        assertFalse(GridEngine.collides(a, b))
    }

    @Test
    fun `collides is false for vertically stacked non-overlapping rects`() {
        val a = item("a", row = 0, col = 0, rowSpan = 1, colSpan = 4)
        val b = item("b", row = 1, col = 0, rowSpan = 1, colSpan = 4)
        assertFalse(GridEngine.collides(a, b))
    }

    @Test
    fun `collides is false for an item against itself by id`() {
        val a = item("a", row = 0, col = 0, rowSpan = 2, colSpan = 2)
        val aAgain = item("a", row = 0, col = 0, rowSpan = 2, colSpan = 2)
        assertFalse(GridEngine.collides(a, aAgain))
    }

    // ------------------------------------------------------------ clampToBounds

    @Test
    fun `clampToBounds pulls col left so col plus colSpan never exceeds columnCount`() {
        val clamped = GridEngine.clampToBounds(item("a", row = 0, col = 3, colSpan = 2), columnCount = 4)
        assertEquals(2, clamped.col)
        assertEquals(2, clamped.colSpan)
    }

    @Test
    fun `clampToBounds ceilings colSpan at columnCount`() {
        val clamped = GridEngine.clampToBounds(item("a", row = 0, col = 0, colSpan = 9), columnCount = 4)
        assertEquals(4, clamped.colSpan)
        assertEquals(0, clamped.col)
    }

    @Test
    fun `clampToBounds floors row and spans at their minimums`() {
        val clamped = GridEngine.clampToBounds(item("a", row = -5, col = 0, rowSpan = 0, colSpan = 0), columnCount = 4)
        assertEquals(0, clamped.row)
        assertEquals(1, clamped.rowSpan)
        assertEquals(1, clamped.colSpan)
    }

    @Test
    fun `clampToBounds is a no-op for an already-legal item`() {
        val legal = item("a", row = 2, col = 1, rowSpan = 2, colSpan = 2)
        assertEquals(legal, GridEngine.clampToBounds(legal, columnCount = 4))
    }

    // ---------------------------------------------------------- resolveCollisions

    @Test
    fun `resolveCollisions pushes a single overlapping item straight down`() {
        val fixed = item("moved", row = 0, col = 0, rowSpan = 2, colSpan = 2)
        val other = item("stayed-col", row = 0, col = 0, rowSpan = 1, colSpan = 2)
        val result = GridEngine.resolveCollisions(fixed, listOf(other))
        assertEquals(1, result.size)
        val pushed = result.single()
        assertEquals("stayed-col", pushed.id)
        assertEquals(2, pushed.row) // fixed.row(0) + fixed.rowSpan(2)
        assertEquals(0, pushed.col) // column never changes - vertical push only
    }

    @Test
    fun `resolveCollisions is a no-op when nothing overlaps`() {
        val fixed = item("moved", row = 0, col = 0, rowSpan = 1, colSpan = 2)
        val other = item("far", row = 0, col = 2, rowSpan = 1, colSpan = 2)
        val result = GridEngine.resolveCollisions(fixed, listOf(other))
        assertEquals(other, result.single())
    }

    @Test
    fun `resolveCollisions cascades a three-deep stack`() {
        // fixed occupies row 0-1; b sits at row 0 (collides with fixed) and is pushed to row 2;
        // c sits at row 2 (collides with b's NEW position) and must cascade to row 3.
        val fixed = item("fixed", row = 0, col = 0, rowSpan = 2, colSpan = 4)
        val b = item("b", row = 0, col = 0, rowSpan = 1, colSpan = 4)
        val c = item("c", row = 2, col = 0, rowSpan = 1, colSpan = 4)
        val result = GridEngine.resolveCollisions(fixed, listOf(b, c))
        val byId = result.associateBy { it.id }
        assertEquals(2, byId.getValue("b").row)
        assertEquals(3, byId.getValue("c").row)
    }

    @Test
    fun `resolveCollisions pushes only items that actually overlap, leaving disjoint columns alone`() {
        val fixed = item("moved", row = 0, col = 0, rowSpan = 2, colSpan = 2)
        val overlapping = item("hit", row = 0, col = 1, rowSpan = 1, colSpan = 2)
        val disjoint = item("clear", row = 0, col = 2, rowSpan = 1, colSpan = 2)
        val result = GridEngine.resolveCollisions(fixed, listOf(overlapping, disjoint))
        val byId = result.associateBy { it.id }
        assertEquals(2, byId.getValue("hit").row)
        assertEquals(0, byId.getValue("clear").row) // untouched - never overlapped fixed
    }

    // -------------------------------------------------------------------- compact

    @Test
    fun `compact pulls a gap-separated item straight up to row 0`() {
        val items = listOf(item("a", row = 5, col = 0, colSpan = 4))
        val result = GridEngine.compact(items)
        assertEquals(0, result.single().row)
    }

    @Test
    fun `compact stops an item at the floor of the item above it in the same column`() {
        val above = item("above", row = 0, col = 0, rowSpan = 1, colSpan = 4)
        val below = item("below", row = 5, col = 0, rowSpan = 1, colSpan = 4)
        val result = GridEngine.compact(listOf(above, below))
        val byId = result.associateBy { it.id }
        assertEquals(0, byId.getValue("above").row)
        assertEquals(1, byId.getValue("below").row) // directly under `above`, not row 0
    }

    @Test
    fun `compact does not let two disjoint columns block each other`() {
        val left = item("left", row = 3, col = 0, rowSpan = 1, colSpan = 2)
        val right = item("right", row = 7, col = 2, rowSpan = 1, colSpan = 2)
        val result = GridEngine.compact(listOf(left, right))
        val byId = result.associateBy { it.id }
        assertEquals(0, byId.getValue("left").row)
        assertEquals(0, byId.getValue("right").row)
    }

    @Test
    fun `compact never produces an overlapping result`() {
        val items = listOf(
            item("a", row = 4, col = 0, rowSpan = 2, colSpan = 4),
            item("b", row = 9, col = 0, rowSpan = 1, colSpan = 2),
            item("c", row = 9, col = 2, rowSpan = 3, colSpan = 2),
        )
        val result = GridEngine.compact(items)
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }

    // ------------------------------------------------------------------ normalize

    @Test
    fun `normalize clamps out-of-bounds items into the grid`() {
        val items = listOf(item("a", row = -1, col = 5, rowSpan = 0, colSpan = 9))
        val result = GridEngine.normalize(items, columnCount = 4)
        val a = result.single()
        assertEquals(0, a.row)
        assertTrue(a.col + a.colSpan <= 4)
        assertTrue(a.rowSpan >= 1)
        assertTrue(a.colSpan >= 1)
    }

    @Test
    fun `normalize resolves a raw list with two items on the same cell`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 2),
            item("b", row = 0, col = 0, colSpan = 2),
        )
        val result = GridEngine.normalize(items, columnCount = 4)
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }

    @Test
    fun `normalize is idempotent`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 2),
            item("b", row = 0, col = 2, colSpan = 2),
            item("c", row = 1, col = 0, colSpan = 4),
        )
        val once = GridEngine.normalize(items, columnCount = 4)
        val twice = GridEngine.normalize(once, columnCount = 4)
        assertEquals(once.sortedBy { it.id }, twice.sortedBy { it.id })
    }

    @Test
    fun `normalize compacts a raw list with a gap`() {
        val items = listOf(item("a", row = 8, col = 0, colSpan = 4))
        val result = GridEngine.normalize(items, columnCount = 4)
        assertEquals(0, result.single().row)
    }

    // ---- fifth feel-test pass, 2026-08-23: "theres an empty space, i move something there, it --
    // ---- just goes to the top and doesnt stick" ------------------------------------------------
    // The coordinator's diagnosis to trace and prove FIRST: is `normalize` itself safe to call on
    // every recomposition? This test proves it is NOT - a valid, collision-free, DELIBERATELY
    // GAPPED layout does not round-trip untouched, because `compact` (called unconditionally,
    // `pinnedId = null`) pulls the gapped item straight up regardless of whether the gap was
    // intentional. This is the exact mechanism that silently relocated a freshly-dropped card:
    // `DeckGrid.kt`'s `baseItems` used to call `GridEngine.normalize(items, columnCount)` on EVERY
    // recomposition (including the one immediately following each commit), so a card placed into
    // an empty mid-grid space got compacted away the instant it landed. The fix lives in
    // `DeckGrid.kt` (normalize now runs exactly once, on this composable's first-ever composition,
    // never again) - this test documents the underlying GridEngine behavior that fix depends on
    // NOT being invoked repeatedly, not a bug in `normalize` itself (its compaction is correct and
    // intentional for genuinely untrusted first input - see its own KDoc).
    @Test
    fun `normalize does NOT round-trip a valid gapped layout untouched`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 4),
            item("b", row = 5, col = 0, colSpan = 4), // a deliberate gap, rows 1-4 empty
        )
        val result = GridEngine.normalize(items, columnCount = 4)
        assertFalse("normalize is not an identity function on valid gapped input - that is the point", items == result)
        assertEquals(1, result.single { it.id == "b" }.row) // pulled straight up to close the gap
    }

    // ------------------------- auto-arrange primitives (kept, unused by the interactive path) ---
    // moveTo / resize: react-grid-layout-style push-and-compact. NOT called by DeckGrid.kt's
    // drag/resize gestures as of 2026-08-23's second rework - kept as a candidate future
    // "auto-arrange" action. See this file's own doc and GridModel.kt's KDoc on both functions.

    @Test
    fun `moveTo places the item at the requested cell when nothing is in the way`() {
        val items = listOf(item("a", row = 0, col = 0, colSpan = 2))
        val result = GridEngine.moveTo(items, "a", targetRow = 3, targetCol = 2, columnCount = 4)
        val a = result.single { it.id == "a" }
        assertEquals(3, a.row)
        assertEquals(2, a.col)
    }

    @Test
    fun `moveTo clamps a target column so the item never runs off the right edge`() {
        val items = listOf(item("a", row = 0, col = 0, colSpan = 2))
        val result = GridEngine.moveTo(items, "a", targetRow = 0, targetCol = 10, columnCount = 4)
        val a = result.single { it.id == "a" }
        assertEquals(2, a.col) // 4 - colSpan(2)
    }

    @Test
    fun `moveTo clamps a negative target row to 0`() {
        val items = listOf(item("a", row = 3, col = 0, colSpan = 2))
        val result = GridEngine.moveTo(items, "a", targetRow = -9, targetCol = 0, columnCount = 4)
        assertEquals(0, result.single { it.id == "a" }.row)
    }

    @Test
    fun `moveTo pushes down whatever the moved item now overlaps and leaves no gap`() {
        val items = listOf(
            item("moved", row = 0, col = 0, rowSpan = 1, colSpan = 2),
            item("target-slot", row = 2, col = 2, rowSpan = 1, colSpan = 2),
        )
        val result = GridEngine.moveTo(items, "moved", targetRow = 2, targetCol = 2, columnCount = 4)
        val byId = result.associateBy { it.id }
        assertEquals(2, byId.getValue("moved").row)
        assertEquals(2, byId.getValue("moved").col)
        assertEquals(3, byId.getValue("target-slot").row) // pushed below the moved item
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }

    @Test
    fun `moveTo is a no-op for an unknown id`() {
        val items = listOf(item("a", row = 0, col = 0))
        val result = GridEngine.moveTo(items, "missing", targetRow = 5, targetCol = 1, columnCount = 4)
        assertEquals(items, result)
    }

    @Test
    fun `moveTo compacts other items up after the move opens a gap above them`() {
        val items = listOf(
            item("mover", row = 0, col = 0, colSpan = 4),
            item("follower", row = 1, col = 0, colSpan = 4),
        )
        val result = GridEngine.moveTo(items, "mover", targetRow = 5, targetCol = 0, columnCount = 4)
        val byId = result.associateBy { it.id }
        assertEquals(0, byId.getValue("follower").row) // pulled up once `mover` vacated row 0
    }

    // -------------------------------------------------------------------- resize

    @Test
    fun `resize applies the requested spans when nothing is in the way`() {
        val items = listOf(item("a", row = 0, col = 0, rowSpan = 1, colSpan = 1))
        val result = GridEngine.resize(items, "a", newRowSpan = 2, newColSpan = 3, columnCount = 4)
        val a = result.single { it.id == "a" }
        assertEquals(2, a.rowSpan)
        assertEquals(3, a.colSpan)
    }

    @Test
    fun `resize clamps rowSpan and colSpan to the min-size 1x1 floor`() {
        val items = listOf(item("a", row = 0, col = 0, rowSpan = 3, colSpan = 3))
        val result = GridEngine.resize(items, "a", newRowSpan = 0, newColSpan = -2, columnCount = 4)
        val a = result.single { it.id == "a" }
        assertEquals(1, a.rowSpan)
        assertEquals(1, a.colSpan)
    }

    @Test
    fun `resize clamps colSpan so the item never grows past the right edge from its own col`() {
        val items = listOf(item("a", row = 0, col = 2, rowSpan = 1, colSpan = 1))
        val result = GridEngine.resize(items, "a", newRowSpan = 1, newColSpan = 9, columnCount = 4)
        val a = result.single { it.id == "a" }
        assertEquals(2, a.col) // resize never relocates the anchor
        assertEquals(2, a.colSpan) // 4 - col(2)
    }

    @Test
    fun `resize pushes down whatever the new footprint now overlaps`() {
        val items = listOf(
            item("grower", row = 0, col = 0, rowSpan = 1, colSpan = 2),
            item("below", row = 1, col = 0, rowSpan = 1, colSpan = 2),
        )
        val result = GridEngine.resize(items, "grower", newRowSpan = 2, newColSpan = 2, columnCount = 4)
        val byId = result.associateBy { it.id }
        assertEquals(2, byId.getValue("below").row)
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }

    @Test
    fun `resize is a no-op for an unknown id`() {
        val items = listOf(item("a", row = 0, col = 0))
        val result = GridEngine.resize(items, "missing", newRowSpan = 5, newColSpan = 5, columnCount = 4)
        assertEquals(items, result)
    }

    @Test
    fun `resize shrinking does not itself compact - only a following normalize does`() {
        val items = listOf(item("a", row = 3, col = 0, rowSpan = 3, colSpan = 4))
        val result = GridEngine.resize(items, "a", newRowSpan = 1, newColSpan = 4, columnCount = 4)
        assertEquals(3, result.single().row) // row is untouched by resize itself
    }

    // -- overlapsAny / commitIfValid: kept, unused by the interactive path (third feel-test pass) --
    // clampMoveTarget / clampResizeTarget are still LIVE (see the displaceForPlacement section
    // below) and stay tested here. overlapsAny/commitIfValid implemented the SECOND generation's
    // outright-reject rule ("occupied target = invalid = reject, no displacement") - superseded
    // 2026-08-23 by the third pass's displaceForPlacement, but kept as a stricter primitive a
    // future caller that must never disturb anything else (a paste, an undo) might still want.

    @Test
    fun `clampMoveTarget pulls a target column left so it never runs off the right edge`() {
        val a = item("a", row = 0, col = 0, colSpan = 2)
        val clamped = GridEngine.clampMoveTarget(a, targetRow = 0, targetCol = 10, columnCount = 4)
        assertEquals(2, clamped.col) // 4 - colSpan(2)
    }

    @Test
    fun `clampMoveTarget floors a negative target row at 0`() {
        val a = item("a", row = 3, col = 0, colSpan = 2)
        val clamped = GridEngine.clampMoveTarget(a, targetRow = -9, targetCol = 0, columnCount = 4)
        assertEquals(0, clamped.row)
    }

    @Test
    fun `clampMoveTarget does not touch colSpan or id`() {
        val a = item("a", row = 0, col = 0, rowSpan = 2, colSpan = 3)
        val clamped = GridEngine.clampMoveTarget(a, targetRow = 5, targetCol = 1, columnCount = 4)
        assertEquals("a", clamped.id)
        assertEquals(3, clamped.colSpan)
        assertEquals(2, clamped.rowSpan)
    }

    @Test
    fun `clampResizeTarget clamps rowSpan and colSpan to the min-size 1x1 floor`() {
        val a = item("a", row = 0, col = 0, rowSpan = 3, colSpan = 3)
        val clamped = GridEngine.clampResizeTarget(a, newRowSpan = 0, newColSpan = -2, columnCount = 4)
        assertEquals(1, clamped.rowSpan)
        assertEquals(1, clamped.colSpan)
    }

    @Test
    fun `clampResizeTarget caps colSpan so the item never grows past the right edge, anchor unmoved`() {
        val a = item("a", row = 0, col = 2, rowSpan = 1, colSpan = 1)
        val clamped = GridEngine.clampResizeTarget(a, newRowSpan = 1, newColSpan = 9, columnCount = 4)
        assertEquals(2, clamped.col) // resize never relocates the anchor
        assertEquals(2, clamped.colSpan) // 4 - col(2)
    }

    @Test
    fun `overlapsAny is false when the candidate collides with nothing`() {
        val candidate = item("a", row = 0, col = 0, colSpan = 2)
        val others = listOf(item("b", row = 0, col = 2, colSpan = 2))
        assertFalse(GridEngine.overlapsAny(candidate, others))
    }

    @Test
    fun `overlapsAny is true when the candidate collides with an occupied cell`() {
        val candidate = item("a", row = 0, col = 0, colSpan = 2)
        val others = listOf(item("b", row = 0, col = 1, colSpan = 2))
        assertTrue(GridEngine.overlapsAny(candidate, others))
    }

    @Test
    fun `commitIfValid accepts a legal unoccupied placement exactly as proposed`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 2),
            item("b", row = 4, col = 0, colSpan = 2),
        )
        val candidate = item("a", row = 4, col = 2, colSpan = 2) // legal - beside b, not on it
        val result = GridEngine.commitIfValid(items, candidate)
        assertEquals(candidate, result!!.single { it.id == "a" })
        assertEquals(4, result.single { it.id == "b" }.row) // b never moved - no reflow
    }

    @Test
    fun `commitIfValid rejects an occupied target and returns null - no partial write`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 2),
            item("b", row = 4, col = 0, colSpan = 2),
        )
        val candidate = item("a", row = 4, col = 0, colSpan = 2) // directly on top of b
        val result = GridEngine.commitIfValid(items, candidate)
        assertEquals(null, result)
    }

    @Test
    fun `commitIfValid never moves any OTHER item, valid or not`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 1),
            item("untouched", row = 0, col = 1, colSpan = 1),
        )
        val candidate = item("a", row = 3, col = 3, colSpan = 1)
        val result = GridEngine.commitIfValid(items, candidate)!!
        assertEquals(item("untouched", row = 0, col = 1, colSpan = 1), result.single { it.id == "untouched" })
    }

    @Test
    fun `commitIfValid is a no-op wrapped in a non-null result for an unknown id`() {
        val items = listOf(item("a", row = 0, col = 0))
        val candidate = item("missing", row = 5, col = 1)
        val result = GridEngine.commitIfValid(items, candidate)
        assertEquals(items, result)
    }

    @Test
    fun `a rejected resize leaves the original item exactly as it was`() {
        val items = listOf(
            item("grower", row = 0, col = 0, rowSpan = 1, colSpan = 1),
            item("blocker", row = 0, col = 1, rowSpan = 1, colSpan = 1),
        )
        val current = items.first { it.id == "grower" }
        val candidate = GridEngine.clampResizeTarget(current, newRowSpan = 1, newColSpan = 2, columnCount = 4)
        val result = GridEngine.commitIfValid(items, candidate)
        assertEquals(null, result) // grower would now overlap blocker - rejected
        // Caller-side contract (exercised in DeckGrid.kt, not testable without Compose): on a
        // null result the caller leaves `items` untouched entirely, so `current` is still exactly
        // what a re-render would show - nothing here mutates `items` itself.
        assertEquals(1, current.colSpan)
    }

    // -------------------------------------------------------------------- remove

    @Test
    fun `remove drops the item and compacts what follows`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 4),
            item("b", row = 1, col = 0, colSpan = 4),
        )
        val result = GridEngine.remove(items, "a")
        assertEquals(1, result.size)
        assertEquals(0, result.single { it.id == "b" }.row)
    }

    @Test
    fun `remove is a no-op for an unknown id`() {
        val items = listOf(item("a", row = 0, col = 0))
        assertEquals(items, GridEngine.remove(items, "missing"))
    }

    // ------------------------------------------------------------------ rowCount

    @Test
    fun `rowCount is 0 for an empty grid`() {
        assertEquals(0, GridEngine.rowCount(emptyList()))
    }

    @Test
    fun `rowCount is the tallest occupied row plus its own span`() {
        val items = listOf(
            item("a", row = 0, col = 0, rowSpan = 1),
            item("b", row = 2, col = 0, rowSpan = 3),
        )
        assertEquals(5, GridEngine.rowCount(items))
    }

    // ----------------------------------------------------- displaceForPlacement (the LIVE path) --
    // Third feel-test pass, 2026-08-23: "it doesnt replace or move the items in the grid up if
    // something is already there." Displacement, not rejection - a candidate is always accepted
    // exactly as proposed, and whatever it overlaps relocates to the nearest free space, upward
    // first. This is what DeckGrid.kt's drag/resize commit path actually calls.

    @Test
    fun `displaceForPlacement is a no-op arrangement when the candidate overlaps nothing`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 2),
            item("b", row = 4, col = 0, colSpan = 2),
        )
        val candidate = item("a", row = 4, col = 2, colSpan = 2) // beside b, not on it
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        assertEquals(candidate, result.single { it.id == "a" })
        assertEquals(item("b", row = 4, col = 0, colSpan = 2), result.single { it.id == "b" }) // untouched, bit-identical
    }

    @Test
    fun `displaceForPlacement moves a single occupant UP to the NEAREST free row above`() {
        // "a" drops onto row 2, exactly where "occupant" already sits. Rows 0-1 are both free -
        // occupant should land at row 1, the CLOSEST free row above, not row 0 or below.
        val items = listOf(
            item("a", row = 5, col = 0, colSpan = 4),
            item("occupant", row = 2, col = 0, colSpan = 4),
        )
        val candidate = item("a", row = 2, col = 0, colSpan = 4)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        val byId = result.associateBy { it.id }
        assertEquals(candidate, byId.getValue("a")) // accepted exactly as proposed
        assertEquals(1, byId.getValue("occupant").row) // pushed UP to the NEAREST free row, not row 0
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }

    @Test
    fun `displaceForPlacement moves down when upward is full`() {
        // Rows 0-1 are already occupied by "blocker", so "occupant" (displaced from row 2) has
        // nowhere to go upward and must land below everything instead.
        val items = listOf(
            item("a", row = 5, col = 0, colSpan = 4),
            item("occupant", row = 2, col = 0, colSpan = 4),
            item("blocker", row = 0, col = 0, rowSpan = 2, colSpan = 4),
        )
        val candidate = item("a", row = 2, col = 0, colSpan = 4)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        val byId = result.associateBy { it.id }
        assertEquals(0, byId.getValue("blocker").row) // untouched - candidate never overlapped it
        assertTrue(byId.getValue("occupant").row >= 3) // could not fit above row 2, pushed down
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }

    @Test
    fun `displaceForPlacement finds a free COLUMN at the same row before moving to a new row`() {
        // occupant sits at row 0 (no row above it to search - upward is naturally exhausted).
        // Columns 2-3 at row 0 are free, so occupant should slot in sideways at the SAME row
        // rather than being pushed down to a different row entirely.
        val items = listOf(
            item("a", row = 5, col = 0, colSpan = 2),
            item("occupant", row = 0, col = 0, colSpan = 2),
        )
        val candidate = item("a", row = 0, col = 0, colSpan = 2)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        val occupant = result.single { it.id == "occupant" }
        assertEquals(0, occupant.row) // same row - a free column was available
        assertEquals(2, occupant.col)
    }

    @Test
    fun `displaceForPlacement chains two simultaneous occupants through processing order`() {
        // "a" drops as a 2-tall candidate spanning rows 0-1, directly overlapping BOTH "b" (row 0)
        // and "c" (row 1) at once - both are queued straight from the initial collision, not one
        // via the other. The chain shows up in processing ORDER: b is relocated first (to the
        // nearest free row, 2), then c's own search must route around b's NEW position too - if it
        // did not, both would independently pick the same nearest slot and end up overlapping.
        val items = listOf(
            item("a", row = 5, col = 0, colSpan = 4),
            item("b", row = 0, col = 0, colSpan = 4),
            item("c", row = 1, col = 0, colSpan = 4),
        )
        val candidate = item("a", row = 0, col = 0, rowSpan = 2, colSpan = 4)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        val byId = result.associateBy { it.id }
        assertEquals(2, byId.getValue("b").row) // nearest free row below candidate's 2-row span
        assertEquals(3, byId.getValue("c").row) // row 2 was already claimed by b - chained down
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }

    @Test
    fun `displaceForPlacement's knock-on requeue never fires a false collision (safety net)`() {
        // Defensive coverage for the requeue branch itself: even in a case engineered to try to
        // trip it (three occupants stacked with no gaps, candidate landing on the first one),
        // the result is still fully non-overlapping and every occupant that never geometrically
        // touched the candidate's own footprint stays exactly where it was.
        val items = listOf(
            item("a", row = 9, col = 0, colSpan = 4),
            item("stack1", row = 0, col = 0, colSpan = 4),
            item("stack2", row = 1, col = 0, colSpan = 4),
            item("stack3", row = 2, col = 0, colSpan = 4),
        )
        val candidate = item("a", row = 0, col = 0, colSpan = 4)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
        assertEquals(4, result.size)
        val byId = result.associateBy { it.id }
        // stack2/stack3 never geometrically overlapped the candidate's OWN footprint (row 0
        // only) - they stay bit-identical, only stack1 (the direct collider) moves.
        assertEquals(item("stack2", row = 1, col = 0, colSpan = 4), byId.getValue("stack2"))
        assertEquals(item("stack3", row = 2, col = 0, colSpan = 4), byId.getValue("stack3"))
    }

    @Test
    fun `displaceForPlacement never moves an item the candidate and its chain never touched`() {
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 1),
            item("target", row = 0, col = 1, colSpan = 1),
            item("faraway", row = 20, col = 3, colSpan = 1),
        )
        val candidate = item("a", row = 0, col = 1, colSpan = 1) // overlaps only "target"
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        assertEquals(item("faraway", row = 20, col = 3, colSpan = 1), result.single { it.id == "faraway" })
    }

    @Test
    fun `displaceForPlacement returns null when a displaced occupant cannot fit any column`() {
        // occupant's own colSpan (5) exceeds columnCount (4) - genuinely impossible at ANY row,
        // upward or downward, since row search is unbounded but column width is not.
        val items = listOf(
            item("a", row = 3, col = 0, colSpan = 4),
            item("occupant", row = 0, col = 0, colSpan = 5),
        )
        val candidate = item("a", row = 0, col = 0, colSpan = 4)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)
        assertEquals(null, result)
    }

    @Test
    fun `displaceForPlacement rejects a candidate that is itself out of bounds`() {
        val items = listOf(item("a", row = 0, col = 0, colSpan = 2))
        val outOfBounds = item("a", row = 0, col = 3, colSpan = 2) // col + colSpan = 5 > columnCount
        assertEquals(null, GridEngine.displaceForPlacement(items, outOfBounds, columnCount = 4))
    }

    @Test
    fun `displaceForPlacement is a no-op wrapped in a non-null result for an unknown id`() {
        val items = listOf(item("a", row = 0, col = 0))
        val candidate = item("missing", row = 5, col = 1)
        assertEquals(items, GridEngine.displaceForPlacement(items, candidate, columnCount = 4))
    }

    @Test
    fun `displaceForPlacement leaves untouched items bit-identical, not just non-overlapping`() {
        val untouched = item("bystander", row = 10, col = 0, rowSpan = 2, colSpan = 4)
        val items = listOf(
            item("a", row = 0, col = 0, colSpan = 2),
            item("occupant", row = 3, col = 0, colSpan = 2),
            untouched,
        )
        val candidate = item("a", row = 3, col = 0, colSpan = 2)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)!!
        assertEquals(untouched, result.single { it.id == "bystander" })
    }

    // ---- fourth feel-test pass, 2026-08-23: "doesnt commit, snaps back to old position" -------
    // Coordinator hypothesis to check FIRST: a short drag's candidate almost always overlaps the
    // MOVER'S OWN old footprint, and if `others` in displaceForPlacement failed to exclude the
    // mover's own id, that self-overlap would either try to displace the mover against itself or
    // make the arrangement look impossible (null -> reject -> animate home), matching the symptom
    // for most real drags. This test is that exact shape - a short move whose candidate rect
    // overlaps BOTH the mover's own old cells AND a real occupant - and it PASSES against the
    // current code, which disproves the hypothesis at the GridEngine layer: `others` already
    // filters `it.id != candidate.id` before any collision check runs, so the mover's own old
    // rect was never a real obstacle. The actual bug is elsewhere (see DeckGrid.kt's own doc on
    // the `remember` key fix) - stated here plainly per L-shape precedent, not left implicit.
    @Test
    fun `a short move whose candidate overlaps the mover's OWN old cells still commits, mover excluded`() {
        val mover = item("mover", row = 0, col = 0, colSpan = 2) // occupies col 0-1
        val occupant = item("occupant", row = 0, col = 2, colSpan = 2) // occupies col 2-3
        val items = listOf(mover, occupant)
        // A one-cell drag right: candidate occupies col 1-2 - overlaps the mover's OWN old
        // footprint at col 1 (which must NOT count as a collision) AND overlaps occupant at col 2
        // (which MUST be displaced).
        val candidate = item("mover", row = 0, col = 1, colSpan = 2)
        val result = GridEngine.displaceForPlacement(items, candidate, columnCount = 4)
        assertTrue("expected a commit, not a reject", result != null)
        val byId = result!!.associateBy { it.id }
        assertEquals(candidate, byId.getValue("mover")) // landed exactly on the drop target
        assertEquals(1, byId.getValue("occupant").row) // displaced - row 0 had no free column left
        assertEquals(0, byId.getValue("occupant").col)
        for (x in result) for (y in result) assertFalse(GridEngine.collides(x, y))
    }
}
