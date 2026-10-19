package com.kevin.legion.games.tetris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game's own behaviour beyond the fixture vectors: drops, hold, kicks, top-out. */
class TetrisGameTest {

    @Test
    fun firstPieceMatchesTheSharedSequence() {
        val game = TetrisGame(0)
        // seed 0's first bag starts O, L (fixtures.json); the active piece is the first of them.
        assertEquals(Piece.O, game.snapshot().active?.piece)
        assertEquals(listOf(Piece.L, Piece.S, Piece.T, Piece.Z), game.snapshot().next.take(4))
    }

    @Test
    fun hardDropScoresTwoPerCellAndSpawnsTheNext() {
        val game = TetrisGame(0)
        val fallen = game.hardDrop()
        assertTrue(fallen > 0)
        assertEquals(2 * fallen, game.score)
        assertEquals(Piece.L, game.snapshot().active?.piece)
    }

    @Test
    fun softDropScoresOnePerCell() {
        val game = TetrisGame(0)
        assertTrue(game.softDrop())
        assertTrue(game.softDrop())
        assertEquals(2, game.score)
    }

    @Test
    fun holdSwapsOncePerPiece() {
        val game = TetrisGame(0)
        assertTrue(game.hold())
        assertEquals(Piece.O, game.snapshot().held)
        assertEquals(Piece.L, game.snapshot().active?.piece)
        assertFalse("second hold on the same piece", game.hold())
        game.hardDrop()
        assertTrue("hold is available again after a lock", game.hold())
    }

    @Test
    fun wallsStopSidewaysMovement() {
        val game = TetrisGame(0)
        repeat(Board.COLUMNS) { game.moveHorizontal(-1) }
        assertFalse(game.moveHorizontal(-1))
    }

    @Test
    fun gravityLowersThePieceByOneRowPerInterval() {
        val game = TetrisGame(0)
        val before = game.snapshot().active!!.y
        game.advance(Scoring.secondsPerRow(1) + 0.001)
        assertEquals(before + 1, game.snapshot().active!!.y)
    }

    @Test
    fun stackingToTheCeilingTopsOut() {
        val game = TetrisGame(0)
        var guard = 0
        while (!game.toppedOut && guard++ < 100) game.hardDrop()
        assertTrue(game.toppedOut)
        val frozen = game.score
        game.hardDrop()
        game.advance(5.0)
        assertEquals(frozen, game.score)
    }

    @Test
    fun rotationKeepsTheCellCountAndTurnsBackAfterFourQuarters() {
        for (piece in Piece.entries) {
            for (r in 0 until 4) assertEquals(4, piece.cells(r).size)
            assertEquals(piece.cells(0), piece.cells(4))
        }
        val game = TetrisGame(1)
        val start = game.snapshot().active!!
        repeat(4) { game.rotate(true) }
        assertEquals(start.rotation, game.snapshot().active!!.rotation)
    }

    @Test
    fun aFullRowClearsAndWhatWasAboveFallsOne() {
        val board = Board()
        // Bottom row (index 21): I flat across columns 0-3 and 4-7, then an O over columns 8-9 so its
        // lower half completes the row and its upper half sits on row 20.
        assertFalse(board.place(Piece.I, 0, 0, 20))
        assertFalse(board.place(Piece.I, 0, 4, 20))
        assertFalse(board.place(Piece.O, 0, 7, 20))
        assertEquals(1, board.clearFullRows())
        val cells = board.copyCells()
        val o = Piece.O.ordinal + 1
        assertEquals(o, cells[21 * Board.COLUMNS + 8])
        assertEquals(o, cells[21 * Board.COLUMNS + 9])
        assertEquals(0, cells[21 * Board.COLUMNS + 0])
        assertEquals(0, cells[20 * Board.COLUMNS + 8])
    }

    @Test
    fun levelMultipliesTheLineAwardOnly() {
        assertEquals(2 * 100 + 3 + 20, Scoring.points(2, 1, 3, 10))
    }
}
