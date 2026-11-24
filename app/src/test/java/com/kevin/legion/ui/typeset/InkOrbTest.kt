package com.kevin.legion.ui.typeset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ink orb's simulation: phases ease in over frames, and a still frame snaps to the phase. */
class InkOrbTest {

    @Test
    fun `a still frame snaps straight to the phase`() {
        val sim = OrbSim()
        sim.step(0f, OrbPhase.THINKING)
        assertEquals(0.92f, sim.sizeFactor, 1e-6f)
        assertEquals(0.85f, sim.spin, 1e-6f)
    }

    @Test
    fun `a running orb eases toward the phase instead of jumping`() {
        val sim = OrbSim()
        sim.step(0.016f, OrbPhase.SPEAKING)
        assertTrue(sim.sizeFactor > 1f && sim.sizeFactor < 1.04f)
        repeat(300) { sim.step(0.016f, OrbPhase.SPEAKING) }
        assertEquals(1.04f, sim.sizeFactor, 1e-3f)
    }

    @Test
    fun `nine hundred points, as the prototype`() = assertEquals(900, OrbSim.N)

    @Test
    fun `at rest a second of frame callbacks draws about thirty times, and turns just as far`() {
        val dt = 1f / 120f
        val paced = OrbSim()
        val frames = OrbFrames()
        var drawn = 0
        repeat(120) {
            frames.frame(dt, OrbPhase.IDLE)?.let { step ->
                drawn++
                paced.step(step, OrbPhase.IDLE)
            }
        }
        assertEquals("120 callbacks at rest", 30, drawn)

        // The look is unchanged: the skipped time is carried, so a second turns just as far.
        val every = OrbSim()
        repeat(120) { every.step(dt, OrbPhase.IDLE) }
        assertEquals(every.turn, paced.turn, every.turn * 0.02f)
    }

    @Test
    fun `a phase other than rest draws on every callback`() {
        val frames = OrbFrames()
        val dt = 1f / 120f
        assertEquals(120, (1..120).count { frames.frame(dt, OrbPhase.SPEAKING) != null })
        // Leaving rest is never held back by the frames already skipped.
        val leaving = OrbFrames()
        leaving.frame(dt, OrbPhase.IDLE)
        assertTrue(leaving.frame(dt, OrbPhase.LISTENING) != null)
    }
}
