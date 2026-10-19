package com.kevin.legion.games.tetris

/**
 * The well: 10 columns, 20 visible rows and 2 hidden spawn rows above them (ticket 04), so row 0 is
 * the top of the hidden band and rows [HIDDEN_ROWS] until [ROWS] are what the player sees.
 * A cell holds 0 when empty, otherwise `Piece.ordinal + 1` so the screen can colour it.
 */
class Board {
    private val grid = Array(ROWS) { IntArray(COLUMNS) }

    /** True when [piece] in [rotation] with its box at ([x], [y]) is inside the walls and on empty cells. */
    fun fits(piece: Piece, rotation: Int, x: Int, y: Int): Boolean = piece.cells(rotation).all {
        val cx = x + it.x
        val cy = y + it.y
        cx in 0 until COLUMNS && cy in 0 until ROWS && grid[cy][cx] == 0
    }

    /** Writes the piece into the well. Returns true if every cell landed in the hidden band (a lock-out). */
    fun place(piece: Piece, rotation: Int, x: Int, y: Int): Boolean {
        var allHidden = true
        for (c in piece.cells(rotation)) {
            grid[y + c.y][x + c.x] = piece.ordinal + 1
            if (y + c.y >= HIDDEN_ROWS) allHidden = false
        }
        return allHidden
    }

    /** Removes every full row, drops what was above, and returns how many went. */
    fun clearFullRows(): Int {
        val kept = grid.filter { row -> row.any { it == 0 } }
        val cleared = ROWS - kept.size
        for (i in 0 until ROWS) {
            grid[i] = if (i < cleared) IntArray(COLUMNS) else kept[i - cleared]
        }
        return cleared
    }

    /** A flat row-major copy, [ROWS] x [COLUMNS], safe to hand to the UI. */
    fun copyCells(): IntArray = IntArray(ROWS * COLUMNS) { grid[it / COLUMNS][it % COLUMNS] }

    companion object {
        const val COLUMNS = 10
        const val VISIBLE_ROWS = 20
        const val HIDDEN_ROWS = 2
        const val ROWS = VISIBLE_ROWS + HIDDEN_ROWS
    }
}
