package com.kevin.legion.ui.games

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin's complaint (2026-10-19) was a black well he could not tell the walls from. These are the
 * sanity checks that stop a fourth theme repeating it: the wall must read against the page, and
 * every block (by the colour that carries its shape) must read against the well.
 */
class TetrisThemeTest {
    private val wallVsPage = 3.0
    private val blockVsWell = 3.0

    @Test
    fun `contrast ratio is 21 for black on white and 1 for the same colour`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.Red, Color.Red), 0.0001)
    }

    @Test
    fun `every theme wall reads against the page`() {
        TetrisTheme.entries.forEach { t ->
            val ratio = contrastRatio(t.palette.wall, TetrisPage)
            assertTrue("${t.label} wall vs page is $ratio", ratio >= wallVsPage)
        }
    }

    @Test
    fun `every theme block reads against the well`() {
        TetrisTheme.entries.forEach { t ->
            val p = t.palette
            p.pieces.forEachIndexed { i, c ->
                val ratio = contrastRatio(p.edgeOf(c), p.well)
                assertTrue("${t.label} piece $i vs well is $ratio", ratio >= blockVsWell)
            }
        }
    }

    @Test
    fun `the well is not the page and the grid shows but is quieter than the blocks`() {
        TetrisTheme.entries.forEach { t ->
            val p = t.palette
            assertTrue("${t.label} well matches the page", p.well != TetrisPage)
            val grid = contrastRatio(p.grid, p.well)
            assertTrue("${t.label} grid is invisible ($grid)", grid > 1.0)
            p.pieces.forEach { c ->
                assertTrue("${t.label} grid is louder than a block", grid < contrastRatio(p.edgeOf(c), p.well))
            }
        }
    }

    @Test
    fun `classic uses only the four DMG greens`() {
        val dmg = setOf(Color(0xFF0F380F), Color(0xFF306230), Color(0xFF8BAC0F), Color(0xFF9BBC0F))
        val p = TetrisTheme.CLASSIC.palette
        (p.pieces + p.well + p.grid + p.wall + p.wallEdge).forEach { assertTrue(it in dmg) }
    }

    @Test
    fun `neon pieces are the guideline colours`() {
        val c = TetrisTheme.NEON.palette.pieces // I J L O S T Z
        assertEquals(Color(0xFF00E5FF), c[0])
        assertEquals(Color(0xFFFFE000), c[3])
        assertEquals(Color(0xFFFF3B3B), c[6])
    }

    @Test
    fun `next cycles through all three and back, and an unknown name falls back to classic`() {
        assertEquals(TetrisTheme.NEON, TetrisTheme.CLASSIC.next())
        assertEquals(TetrisTheme.SOFT, TetrisTheme.NEON.next())
        assertEquals(TetrisTheme.CLASSIC, TetrisTheme.SOFT.next())
        assertEquals(TetrisTheme.CLASSIC, TetrisTheme.fromName("nope"))
        assertEquals(TetrisTheme.SOFT, TetrisTheme.fromName("SOFT"))
    }
}
