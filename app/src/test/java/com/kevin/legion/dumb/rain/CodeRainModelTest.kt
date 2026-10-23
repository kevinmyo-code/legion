package com.kevin.legion.dumb.rain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeRainModelTest {
    private fun snapshot(m: CodeRainModel): List<Any> = buildList {
        for (c in 0 until m.columns) {
            add(m.headRow(c))
            add(m.speedOf(c))
            add(m.trailLength(c))
            for (r in 0 until m.rows) add(m.glyphAt(c, r))
        }
    }

    @Test
    fun `the same seed gives the same rain at every step`() {
        val a = CodeRainModel(12, 30, seed = 42L)
        val b = CodeRainModel(12, 30, seed = 42L)
        assertEquals(snapshot(a), snapshot(b))
        repeat(200) {
            a.step(0.033f)
            b.step(0.033f)
        }
        assertEquals(snapshot(a), snapshot(b))
    }

    @Test
    fun `a different seed gives different rain`() {
        assertNotEquals(snapshot(CodeRainModel(12, 30, 1L)), snapshot(CodeRainModel(12, 30, 2L)))
    }

    @Test
    fun `a drop falls at its own speed`() {
        val m = CodeRainModel(30, 40, seed = 7L)
        // A column that will not run off the bottom during this half second, so it is not replaced.
        val c = (0 until m.columns).first { m.headRow(it) + m.speedOf(it) * 0.5f - m.trailLength(it) <= m.rows }
        val before = m.headRow(c)
        val speed = m.speedOf(c)
        m.step(0.5f)
        assertEquals(before + speed * 0.5f, m.headRow(c), 0.0001f)
    }

    @Test
    fun `speeds stay near classic and inside the jitter`() {
        val m = CodeRainModel(60, 40, seed = 3L)
        val lo = RainTuning.FALL_CELLS_PER_SEC * (1f - RainTuning.SPEED_JITTER)
        val hi = RainTuning.FALL_CELLS_PER_SEC * (1f + RainTuning.SPEED_JITTER)
        for (c in 0 until m.columns) assertTrue(m.speedOf(c) in lo..hi)
    }

    @Test
    fun `trail lengths stay inside the tuned range`() {
        val m = CodeRainModel(60, 40, seed = 5L)
        repeat(600) { m.step(0.033f) }
        for (c in 0 until m.columns) assertTrue(m.trailLength(c) in RainTuning.TRAIL_MIN..RainTuning.TRAIL_MAX)
    }

    @Test
    fun `brightness is 1 at the head and fades linearly to 0 over the trail`() {
        assertEquals(1f, CodeRainModel.brightnessFor(head = 10.4f, row = 10, trail = 10), 0.0001f)
        assertEquals(0.9f, CodeRainModel.brightnessFor(10.4f, 9, 10), 0.0001f)
        assertEquals(0.5f, CodeRainModel.brightnessFor(10.4f, 5, 10), 0.0001f)
        assertEquals(0.1f, CodeRainModel.brightnessFor(10.4f, 1, 10), 0.0001f)
        assertEquals(0f, CodeRainModel.brightnessFor(10.4f, 0, 10), 0.0001f)
    }

    @Test
    fun `nothing is lit ahead of the head or before the drop has entered`() {
        assertEquals(0f, CodeRainModel.brightnessFor(10.4f, 11, 10), 0f)
        assertEquals(0f, CodeRainModel.brightnessFor(-3.5f, 0, 10), 0f)
        assertEquals(0f, CodeRainModel.brightnessFor(5f, 5, 0), 0f)
    }

    @Test
    fun `the trail fades monotonically behind the head`() {
        var last = 2f
        for (row in 20 downTo 0) {
            val b = CodeRainModel.brightnessFor(20.2f, row, 15)
            assertTrue(b <= last)
            last = b
        }
    }

    @Test
    fun `a drop that has fallen past the bottom is replaced by a later one`() {
        val m = CodeRainModel(1, 20, seed = 9L)
        var respawned = false
        var previous = m.headRow(0)
        repeat(2000) {
            m.step(0.033f)
            if (m.headRow(0) < previous - 1f) respawned = true
            previous = m.headRow(0)
        }
        assertTrue("the column should have started a new drop", respawned)
    }

    @Test
    fun `some of the grid is lit and some is dark once the rain is going`() {
        val m = CodeRainModel(40, 50, seed = 11L)
        repeat(300) { m.step(0.033f) }
        var lit = 0
        var total = 0
        for (c in 0 until m.columns) for (r in 0 until m.rows) {
            total++
            if (m.brightness(c, r) >= RainTuning.MIN_VISIBLE) lit++
        }
        assertTrue("some cells are lit", lit > 0)
        assertTrue("not every cell is lit", lit < total)
    }

    @Test
    fun `a zero or negative step changes nothing`() {
        val m = CodeRainModel(6, 20, seed = 13L)
        val before = snapshot(m)
        m.step(0f)
        m.step(-1f)
        assertEquals(before, snapshot(m))
    }

    @Test
    fun `the glyph set is half-width katakana, digits and a few Latin letters`() {
        val all = RainGlyphs.ALL
        assertTrue("ｦ" in all && "ﾝ" in all)
        assertTrue("0" in all && "9" in all)
        assertTrue("Z" in all)
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it.length == 1 })
    }

    @Test
    fun `glyphs are always inside the set`() {
        val m = CodeRainModel(8, 16, seed = 17L)
        repeat(100) { m.step(0.05f) }
        for (c in 0 until m.columns) for (r in 0 until m.rows) assertTrue(m.glyphAt(c, r) in RainGlyphs.ALL)
    }

    @Test
    fun `the whole feel lives in the tuning object`() {
        assertTrue(RainTuning.FALL_CELLS_PER_SEC > 0f)
        assertTrue(RainTuning.DENSITY in 0.1f..1f)
        assertTrue(RainTuning.TRAIL_MIN < RainTuning.TRAIL_MAX)
        assertFalse(RainTuning.FRAME_MS <= 0L)
    }
}

class RainContrastTest {
    private fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun luminance(r: Double, g: Double, b: Double) = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)

    private fun luminanceOf(rgb: Int) =
        luminance(((rgb shr 16) and 0xFF) / 255.0, ((rgb shr 8) and 0xFF) / 255.0, (rgb and 0xFF) / 255.0)

    private fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    /** The brightest thing the rain can put behind a letter: the head colour, seen through the scrim over black. */
    private val worstBackground: Double = run {
        val keep = 1.0 - RainPalette.SCRIM_ALPHA
        val h = RainPalette.HEAD_RGB
        val r = ((h shr 16) and 0xFF) / 255.0 * keep
        val g = ((h shr 8) and 0xFF) / 255.0 * keep
        val b = (h and 0xFF) / 255.0 * keep
        luminance(r, g, b)
    }

    @Test
    fun `text clears 4_5 to 1 against the brightest rain under the scrim`() {
        assertTrue(contrast(luminanceOf(RainPalette.TEXT_RGB), worstBackground) >= 4.5)
    }

    @Test
    fun `body text clears 4_5 to 1 against the brightest rain under the scrim`() {
        assertTrue(contrast(luminanceOf(RainPalette.BODY_RGB), worstBackground) >= 4.5)
    }

    @Test
    fun `dim text clears 4_5 to 1 against the brightest rain under the scrim`() {
        val ratio = contrast(luminanceOf(RainPalette.DIM_RGB), worstBackground)
        assertTrue("dim text contrast was $ratio", ratio >= 4.5)
    }

    @Test
    fun `every text colour clears 4_5 to 1 on plain black for the screens without rain`() {
        for (rgb in listOf(RainPalette.TEXT_RGB, RainPalette.BODY_RGB, RainPalette.DIM_RGB)) {
            assertTrue(contrast(luminanceOf(rgb), 0.0) >= 4.5)
        }
    }
}
