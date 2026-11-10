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
}
