package com.kevin.legion.ui.dumb

import com.kevin.legion.service.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matrix HOME's push-to-talk: every phase reads differently, in words and in motion. */
class MatrixTalkStateTest {
    private fun live(phase: Phase) =
        MatrixTalkResolver.resolve(phase, notice = null, micGranted = true, silenced = false)

    @Test
    fun `every phase has its own label, its own motion and its own spoken sentence`() {
        val all = Phase.entries.map(::live)
        assertEquals(Phase.entries.size, all.map { it.label }.toSet().size)
        assertEquals(Phase.entries.size, all.map { it.motion }.toSet().size)
        assertEquals(Phase.entries.size, all.map { it.spoken }.toSet().size)
    }

    @Test
    fun `idle invites a tap and does not move`() {
        val d = live(Phase.IDLE)
        assertEquals("TAP TO TALK", d.label)
        assertEquals(MatrixTalkMotion.NONE, d.motion)
        assertFalse(d.live)
        assertFalse(d.showLevel)
    }

    @Test
    fun `connecting spins and is not yet live`() {
        val d = live(Phase.CONNECTING)
        assertEquals("> CONNECTING", d.label)
        assertEquals(MatrixTalkMotion.SPINNER, d.motion)
        assertFalse(d.live)
    }

    @Test
    fun `listening blinks a cursor, shows the mic level, and says a tap stops it`() {
        val d = live(Phase.LISTENING)
        assertEquals("> LISTENING", d.label)
        assertEquals(MatrixTalkMotion.CURSOR, d.motion)
        assertTrue(d.showLevel)
        assertTrue(d.live)
        assertEquals(MatrixTalkResolver.TAP_TO_STOP, d.detail)
    }

    @Test
    fun `thinking cycles dots and speaking runs a wave, neither shows the mic level`() {
        val thinking = live(Phase.THINKING)
        val speaking = live(Phase.SPEAKING)
        assertEquals("> THINKING", thinking.label)
        assertEquals(MatrixTalkMotion.DOTS, thinking.motion)
        assertEquals("> SPEAKING", speaking.label)
        assertEquals(MatrixTalkMotion.WAVE, speaking.motion)
        listOf(thinking, speaking).forEach {
            assertTrue(it.live)
            assertFalse(it.showLevel)
            assertEquals(MatrixTone.HEADING, it.tone)
        }
    }

    @Test
    fun `a missing mic grant beats every phase, silenced and notice`() {
        Phase.entries.forEach { phase ->
            val d = MatrixTalkResolver.resolve(phase, "CONNECTION LOST", micGranted = false, silenced = true)
            assertEquals("! MIC NOT ALLOWED", d.label)
            assertEquals(MatrixTone.WARNING, d.tone)
            assertFalse(d.showLevel)
        }
    }

    @Test
    fun `silenced beats a notice and listening, so it never claims to hear`() {
        val d = MatrixTalkResolver.resolve(Phase.LISTENING, "NO SIGNAL", micGranted = true, silenced = true)
        assertEquals("! CAN'T HEAR YOU", d.label)
        assertFalse(d.showLevel)
    }

    @Test
    fun `a notice is what tells a failed tap apart from idle`() {
        val d = MatrixTalkResolver.resolve(Phase.IDLE, "Conversation closed", micGranted = true, silenced = false)
        assertEquals("! CONVERSATION CLOSED", d.label)
        assertEquals(MatrixTone.WARNING, d.tone)
        assertNotEquals(live(Phase.IDLE).label, d.label)
    }

    @Test
    fun `frames of one motion are one width, and the still frame differs per motion`() {
        MatrixTalkMotion.entries.forEach { motion ->
            val widths = (0 until 12).map { MatrixTalkResolver.frame(motion, it).length }.toSet()
            assertEquals("$motion jitters", 1, widths.size)
        }
        val stills = MatrixTalkMotion.entries.map { MatrixTalkResolver.frame(it, null) }
        assertEquals(stills.size, stills.toSet().size)
    }

    @Test
    fun `the cursor blinks at half the tick rate`() {
        val frames = (0 until 4).map { MatrixTalkResolver.frame(MatrixTalkMotion.CURSOR, it) }
        assertEquals(listOf(" ", " ", "_", "_"), frames)
    }

    @Test
    fun `level is zero for silence or a stale reading and fills for loud speech`() {
        assertEquals(0, MatrixTalkResolver.levelCells(0, 0))
        assertEquals(0, MatrixTalkResolver.levelCells(20_000, MatrixTalkResolver.LEVEL_STALE_MS + 1))
        assertEquals(0, MatrixTalkResolver.levelCells(20_000, -5))
        assertEquals(0, MatrixTalkResolver.levelCells(50, 0))
        assertEquals(MatrixTalkResolver.LEVEL_CELLS, MatrixTalkResolver.levelCells(32_767, 0))
        val quiet = MatrixTalkResolver.levelCells(500, 0)
        val loud = MatrixTalkResolver.levelCells(8_000, 0)
        assertTrue("quiet=$quiet loud=$loud", quiet in 1 until loud)
    }

    @Test
    fun `level bar is a fixed-width text gauge`() {
        assertEquals("[........]", MatrixTalkResolver.levelBar(0))
        assertEquals("[|||.....]", MatrixTalkResolver.levelBar(3))
        assertEquals("[||||||||]", MatrixTalkResolver.levelBar(99))
    }
}
