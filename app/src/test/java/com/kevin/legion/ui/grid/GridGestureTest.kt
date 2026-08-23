package com.kevin.legion.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain-JVM coverage of [GridGesture.candidateCell] - the pixel-to-cell mapping extracted out of
 * `DeckGrid.kt` after the sixth feel-test pass, where an inline, unnamed, duplicated copy of this
 * exact arithmetic transposed row and column at the commit call site (see [GridGesture]'s own
 * KDoc for the full trace). This file is the "leaving only event plumbing untested" half of that
 * fix - `detectDragGestures` wiring and Compose state threading still need a real device or
 * instrumentation test, but the ARITHMETIC itself - the part that was actually wrong three times
 * in a row across the third, fourth, and sixth feel-test passes - now has direct JVM coverage.
 *
 * Cell pitch is a round 100px throughout for arithmetic that reads cleanly by eye (a real pitch
 * comes from `colPitchPx`/`rowPitchPx` in `DeckGrid.kt`, density-derived and non-round).
 */
class GridGestureTest {

    private val pitch = 100f

    @Test
    fun `no movement returns the origin cell unchanged`() {
        val (row, col) = GridGesture.candidateCell(
            originRow = 2, originCol = 1, accumPxX = 0f, accumPxY = 0f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(2, row)
        assertEquals(1, col)
    }

    @Test
    fun `dragging DOWN increases row, column unchanged - the exact shape that broke`() {
        // This is the reproduction of the sixth-pass symptom: a vertical-only drag (accumPxX = 0)
        // on a card starting at column 0. Before the fix, DeckGrid.kt's commit call site swapped
        // this large row-progress value into the COLUMN slot and the near-zero column value into
        // the ROW slot - for a full-width card (colSpan == columnCount, targetCol range [0, 0]),
        // that always clamped to (row 0, col 0) regardless of this result. This test asserts the
        // CORRECT, unswapped answer the pure function must produce.
        val (row, col) = GridGesture.candidateCell(
            originRow = 0, originCol = 0, accumPxX = 0f, accumPxY = 250f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(2, row) // 250px / 100px-per-row = 2.5, floored to row 2
        assertEquals(0, col) // no horizontal movement - column must stay 0, not become 2
    }

    @Test
    fun `dragging UP decreases row, column unchanged`() {
        val (row, col) = GridGesture.candidateCell(
            originRow = 5, originCol = 0, accumPxX = 0f, accumPxY = -200f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(3, row) // origin row 5, pitch 100 -> 500px, minus 200px = 300px -> row 3
        assertEquals(0, col)
    }

    @Test
    fun `dragging RIGHT increases column, row unchanged`() {
        val (row, col) = GridGesture.candidateCell(
            originRow = 1, originCol = 0, accumPxX = 320f, accumPxY = 0f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(1, row) // no vertical movement - row must stay 1, not become 3
        assertEquals(3, col) // 320px / 100px-per-col = 3.2, floored to col 3
    }

    @Test
    fun `dragging LEFT decreases column, row unchanged`() {
        val (row, col) = GridGesture.candidateCell(
            originRow = 2, originCol = 3, accumPxX = -150f, accumPxY = 0f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(2, row)
        assertEquals(1, col) // origin col 3 (300px), minus 150px = 150px -> col 1
    }

    @Test
    fun `diagonal drag moves row and column independently, never conflated`() {
        val (row, col) = GridGesture.candidateCell(
            originRow = 0, originCol = 0, accumPxX = 250f, accumPxY = 150f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(1, row) // 150px vertical -> row 1
        assertEquals(2, col) // 250px horizontal -> col 2 - NOT the row's own value
    }

    @Test
    fun `sub-cell jitter that stays inside the same pitch band does not change the candidate cell`() {
        // Origin (row 1, col 1) sits at pixel baseline (100px, 100px); each band spans [n*100,
        // (n+1)*100). A small positive nudge on each axis stays inside the SAME band as the
        // origin, so the candidate cell must not move at all - this is the "does not thrash on a
        // shaky finger" case.
        val (row, col) = GridGesture.candidateCell(
            originRow = 1, originCol = 1, accumPxX = 12f, accumPxY = 8f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(1, row) // 100px + 8px = 108px, still inside row 1's [100,200) band
        assertEquals(1, col) // 100px + 12px = 112px, still inside col 1's [100,200) band
    }

    @Test
    fun `sub-cell jitter that crosses a pitch boundary DOES change the candidate cell`() {
        // Same origin (100px, 100px) baseline; a 5px nudge TOWARD zero on each axis crosses out of
        // row/col 1's [100,200) band into row/col 0's [0,100) band - the candidate cell must move.
        val (row, col) = GridGesture.candidateCell(
            originRow = 1, originCol = 1, accumPxX = -5f, accumPxY = -5f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(0, row) // 100px - 5px = 95px, now inside row 0's [0,100) band
        assertEquals(0, col) // 100px - 5px = 95px, now inside col 0's [0,100) band
    }

    @Test
    fun `row is floored at 0 and never goes negative`() {
        val (row, _) = GridGesture.candidateCell(
            originRow = 0, originCol = 0, accumPxX = 0f, accumPxY = -999f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(0, row)
    }

    @Test
    fun `column is NOT floored here - a negative column is a real answer, clamping is the caller's job`() {
        val (_, col) = GridGesture.candidateCell(
            originRow = 0, originCol = 0, accumPxX = -999f, accumPxY = 0f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(-10, col) // -999px / 100px-per-col = -9.99, floored to -10 - NOT clamped to 0
    }

    @Test
    fun `a non-zero origin cell is honoured as the drag's own starting point`() {
        val (row, col) = GridGesture.candidateCell(
            originRow = 4, originCol = 2, accumPxX = 0f, accumPxY = 0f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(4, row)
        assertEquals(2, col)
    }

    @Test
    fun `result never depends on which axis the caller happens to compute first - the swap this test guards against`() {
        // The sixth-pass bug's actual SHAPE: two independently-computed values (one from X, one
        // from Y) landing in the wrong parameter slots at the CALL SITE. This function returns
        // BOTH as one named Pair specifically so a caller can no longer separate them into two
        // ad-hoc local expressions and risk reordering them - there is exactly one call, and its
        // result destructures directly into (targetRow, targetCol) in that order.
        val result = GridGesture.candidateCell(
            originRow = 0, originCol = 0, accumPxX = 300f, accumPxY = 100f, colPitchPx = pitch, rowPitchPx = pitch,
        )
        assertEquals(1 to 3, result) // (row, col) - row from Y (100px), col from X (300px)
    }
}
