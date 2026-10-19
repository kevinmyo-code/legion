@file:Suppress("MagicNumber") // Cell offsets and SRS kick offsets are the published tables, not tunables.

package com.kevin.legion.games.tetris

/** One cell offset. [y] grows DOWNWARD on this board; the SRS tables below are written y-up and flipped on use. */
data class Cell(val x: Int, val y: Int)

/** The seven pieces, in the order a fresh bag starts in (ticket 04: `[I, J, L, O, S, T, Z]`). */
enum class Piece(val boxSize: Int, private val spawnCells: List<Cell>) {
    I(4, listOf(Cell(0, 1), Cell(1, 1), Cell(2, 1), Cell(3, 1))),
    J(3, listOf(Cell(0, 0), Cell(0, 1), Cell(1, 1), Cell(2, 1))),
    L(3, listOf(Cell(2, 0), Cell(0, 1), Cell(1, 1), Cell(2, 1))),
    O(4, listOf(Cell(1, 0), Cell(2, 0), Cell(1, 1), Cell(2, 1))),
    S(3, listOf(Cell(1, 0), Cell(2, 0), Cell(0, 1), Cell(1, 1))),
    T(3, listOf(Cell(1, 0), Cell(0, 1), Cell(1, 1), Cell(2, 1))),
    Z(3, listOf(Cell(0, 0), Cell(1, 0), Cell(1, 1), Cell(2, 1)));

    /** The four cells in the piece's box for rotation state [rotation] (0 spawn, 1 right, 2 flipped, 3 left). */
    fun cells(rotation: Int): List<Cell> {
        // O is square: SRS gives it no rotation and no kicks, so it never leaves state 0.
        val turns = if (this == O) 0 else Math.floorMod(rotation, 4)
        var cells = spawnCells
        repeat(turns) { cells = cells.map { Cell(boxSize - 1 - it.y, it.x) } } // clockwise in a y-down box
        return cells
    }
}

/** SRS wall-kick offsets keyed by (from, to) rotation state, written in the SRS y-up convention. */
internal object Kicks {
    private val jlstz = mapOf(
        (0 to 1) to listOf(0 to 0, -1 to 0, -1 to 1, 0 to -2, -1 to -2),
        (1 to 0) to listOf(0 to 0, 1 to 0, 1 to -1, 0 to 2, 1 to 2),
        (1 to 2) to listOf(0 to 0, 1 to 0, 1 to -1, 0 to 2, 1 to 2),
        (2 to 1) to listOf(0 to 0, -1 to 0, -1 to 1, 0 to -2, -1 to -2),
        (2 to 3) to listOf(0 to 0, 1 to 0, 1 to 1, 0 to -2, 1 to -2),
        (3 to 2) to listOf(0 to 0, -1 to 0, -1 to -1, 0 to 2, -1 to 2),
        (3 to 0) to listOf(0 to 0, -1 to 0, -1 to -1, 0 to 2, -1 to 2),
        (0 to 3) to listOf(0 to 0, 1 to 0, 1 to 1, 0 to -2, 1 to -2),
    )
    private val i = mapOf(
        (0 to 1) to listOf(0 to 0, -2 to 0, 1 to 0, -2 to -1, 1 to 2),
        (1 to 0) to listOf(0 to 0, 2 to 0, -1 to 0, 2 to 1, -1 to -2),
        (1 to 2) to listOf(0 to 0, -1 to 0, 2 to 0, -1 to 2, 2 to -1),
        (2 to 1) to listOf(0 to 0, 1 to 0, -2 to 0, 1 to -2, -2 to 1),
        (2 to 3) to listOf(0 to 0, 2 to 0, -1 to 0, 2 to 1, -1 to -2),
        (3 to 2) to listOf(0 to 0, -2 to 0, 1 to 0, -2 to -1, 1 to 2),
        (3 to 0) to listOf(0 to 0, 1 to 0, -2 to 0, 1 to -2, -2 to 1),
        (0 to 3) to listOf(0 to 0, -1 to 0, 2 to 0, -1 to 2, 2 to -1),
    )

    /** The offsets to try, in order, for [piece] rotating [fromRot] to [toRot]; already flipped to y-down. */
    fun offsets(piece: Piece, fromRot: Int, toRot: Int): List<Cell> {
        if (piece == Piece.O) return listOf(Cell(0, 0))
        val table = if (piece == Piece.I) i else jlstz
        return table.getValue(fromRot to toRot).map { (x, y) -> Cell(x, -y) }
    }
}
