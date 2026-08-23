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
 * **Two feel-test passes on the A25 (2026-08-23) retired the push-and-compact reflow from the
 * INTERACTIVE drag/resize path** (`DeckGrid.kt` no longer calls [GridEngine.moveTo]/
 * [GridEngine.resize]). Their tests below, in the section marked "auto-arrange primitives (kept,
 * unused by the interactive path)", stay as direct coverage of those two functions - they are
 * real, present code (kept on purpose per that day's rework, in case a future explicit
 * "auto-arrange" action wants them), not dead code, so testing them directly remains honest
 * coverage. [GridEngine.compact]/[GridEngine.resolveCollisions] are exercised both directly AND
 * through [GridEngine.normalize] (their only remaining LIVE caller in the interactive path's own
 * call graph). The launcher-semantics replacement - clamp, check, accept-or-reject-outright, never
 * push - has its own section below: [GridEngine.clampMoveTarget] / [GridEngine.clampResizeTarget] /
 * [GridEngine.overlapsAny] / [GridEngine.commitIfValid].
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

    // ------------------- interactive placement (no reflow) - the LIVE drag/resize path's own API --
    // clampMoveTarget / clampResizeTarget / overlapsAny / commitIfValid: Android-launcher
    // semantics. A candidate is either a legal, unoccupied spot (accepted exactly as proposed) or
    // it collides with something (rejected outright, `commitIfValid` returns null). No card ever
    // moves because another card was dragged or resized.

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
}
