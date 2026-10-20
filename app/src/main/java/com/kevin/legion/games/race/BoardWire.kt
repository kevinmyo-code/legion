package com.kevin.legion.games.race

import com.kevin.legion.games.tetris.Board
import com.kevin.legion.games.tetris.Cell
import com.kevin.legion.games.tetris.GameSnapshot
import com.kevin.legion.games.tetris.Piece

/**
 * A board on the wire (ADR 0060, amended 2026-10-19): [Board.ROWS] rows of [Board.COLUMNS], top
 * row first, hidden rows included, one character a cell. `.` is empty, a letter is that piece. The
 * falling piece is baked in; the ghost is never sent. The PWA reads and writes the same string
 * (`server/frontend/src/games/tetris/board-wire.ts`), and both are held to
 * `shared/tetris/fixtures.json` `boardWire`.
 */
object BoardWire {
    const val CELLS = Board.ROWS * Board.COLUMNS
    private const val EMPTY = '.'

    /**
     * Locked [cells] (row-major, 0 empty, otherwise `Piece.ordinal + 1`, as [GameSnapshot.cells])
     * plus the falling piece's [activeCells] in [activePiece], as the wire string.
     */
    fun encode(cells: IntArray, activePiece: Piece?, activeCells: List<Cell>): String {
        val out = CharArray(CELLS) { i ->
            val v = cells.getOrElse(i) { 0 }
            if (v == 0) EMPTY else Piece.entries[v - 1].name[0]
        }
        if (activePiece != null) {
            for (c in activeCells) {
                if (c.x in 0 until Board.COLUMNS && c.y in 0 until Board.ROWS) {
                    out[c.y * Board.COLUMNS + c.x] = activePiece.name[0]
                }
            }
        }
        return String(out)
    }

    fun encode(snapshot: GameSnapshot): String =
        encode(snapshot.cells, snapshot.active?.piece, snapshot.active?.cells().orEmpty())

    /** The wire string as [GameSnapshot.cells]-shaped cells, or null for anything not a whole board. */
    fun decode(text: String?): IntArray? {
        if (text == null || text.length != CELLS) return null
        val out = IntArray(CELLS) { i -> codeOf(text[i]) }
        return out.takeIf { cells -> cells.none { it < 0 } }
    }

    /** 0 for empty, `ordinal + 1` for a piece letter, -1 for anything else. */
    private fun codeOf(ch: Char): Int =
        if (ch == EMPTY) 0 else Piece.entries.firstOrNull { it.name[0] == ch }?.let { it.ordinal + 1 } ?: -1
}
