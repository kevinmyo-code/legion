package com.kevin.legion.games.race

import com.kevin.legion.games.tetris.Board
import com.kevin.legion.games.tetris.Cell
import com.kevin.legion.games.tetris.Piece
import com.kevin.legion.games.tetris.TetrisGame
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board on the wire (ADR 0060, amended 2026-10-19), held to `shared/tetris/fixtures.json`
 * `boardWire`: the same cases the PWA's `board-wire.test.ts` encodes, so the two cannot drift.
 */
class BoardWireTest {
    private fun fixtureFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/tetris/fixtures.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/tetris/fixtures.json not found above ${System.getProperty("user.dir")}")
    }

    private val cases = Json.parseToJsonElement(fixtureFile().readText()).jsonObject.getValue("boardWire").jsonArray

    @Test
    fun `encodes and decodes every shared fixture`() {
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val o = case.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val cells = IntArray(BoardWire.CELLS)
            for (locked in o.getValue("locked").jsonArray) {
                val (x, y, kind) = locked.jsonArray
                cells[y.jsonPrimitive.int * Board.COLUMNS + x.jsonPrimitive.int] =
                    Piece.valueOf(kind.jsonPrimitive.content).ordinal + 1
            }
            val active = o.getValue("active").takeUnless { it is JsonNull }?.jsonObject
            val piece = active?.let { Piece.valueOf(it.getValue("kind").jsonPrimitive.content) }
            val activeCells = active?.getValue("cells")?.jsonArray?.map {
                val (x, y) = it.jsonArray
                Cell(x.jsonPrimitive.int, y.jsonPrimitive.int)
            }.orEmpty()
            val wire = o.getValue("wire").jsonPrimitive.content
            assertEquals(name, wire, BoardWire.encode(cells, piece, activeCells))
            val back = assertNotNullAnd(BoardWire.decode(wire), name)
            assertEquals(name, wire, BoardWire.encode(back, null, emptyList()))
        }
    }

    @Test
    fun `a live game bakes in its falling piece and never the ghost`() {
        val snapshot = TetrisGame(0).snapshot()
        val wire = BoardWire.encode(snapshot)
        assertEquals(220, wire.length)
        val active = requireNotNull(snapshot.active)
        assertEquals(4, wire.count { it != '.' })
        for (c in active.cells()) assertEquals(active.piece.name[0], wire[c.y * Board.COLUMNS + c.x])
    }

    @Test
    fun `anything that is not a whole board decodes to null`() {
        assertNull(BoardWire.decode(null))
        assertNull(BoardWire.decode(""))
        assertNull(BoardWire.decode(".".repeat(219)))
        assertNull(BoardWire.decode(".".repeat(219) + "G"))
        assertNull(BoardWire.decode(".".repeat(219) + "i"))
        assertArrayEquals(IntArray(220), BoardWire.decode(".".repeat(220)))
    }

    private fun assertNotNullAnd(value: IntArray?, name: String): IntArray {
        assertNotNull(name, value)
        return value!!
    }
}
