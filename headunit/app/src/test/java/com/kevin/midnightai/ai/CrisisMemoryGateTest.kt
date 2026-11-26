package com.kevin.midnightai.ai

import com.kevin.midnightai.data.local.EpisodicTurn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrisisMemoryGate]: a crisis conversation leaves no durable memory behind (ticket #458).
 *
 * Both halves matter. [CrisisMemoryGate.recordsTurn] keeps the pair out of `episodic_turns` as the
 * turn completes; [CrisisMemoryGate.forgetsSession] drops the conversation on the way past, which
 * is what reaches the turns said before the crisis phrase and the rows a unit already holds.
 */
class CrisisMemoryGateTest {

    // --- the write side -----------------------------------------------------------------------

    @Test
    fun `an ordinary exchange is recorded`() {
        assertTrue(CrisisMemoryGate.recordsTurn("what is the coolant running at", sessionClosed = false))
    }

    @Test
    fun `a crisis line is never recorded`() {
        assertFalse(CrisisMemoryGate.recordsTurn("i want to kill myself", sessionClosed = false))
        assertFalse(CrisisMemoryGate.recordsTurn("honestly i just want to die", sessionClosed = false))
    }

    /**
     * The graceful close keeps delivering frames, so a turn can complete after the teardown began.
     * The same reason `carryTurn` refuses one.
     */
    @Test
    fun `nothing is recorded once the session is closed`() {
        assertFalse(CrisisMemoryGate.recordsTurn("what is the coolant running at", sessionClosed = true))
    }

    @Test
    fun `a blank driver line is not a turn to remember`() {
        assertFalse(CrisisMemoryGate.recordsTurn("", sessionClosed = false))
        assertFalse(CrisisMemoryGate.recordsTurn("   ", sessionClosed = false))
    }

    /** Car idioms are the detector's own hard case, and the gate must not swallow them. */
    @Test
    fun `ordinary car talk is still recorded`() {
        assertTrue(CrisisMemoryGate.recordsTurn("this traffic is killing me", sessionClosed = false))
        assertTrue(CrisisMemoryGate.recordsTurn("the battery is dead again", sessionClosed = false))
    }

    // --- the read side ------------------------------------------------------------------------

    @Test
    fun `a session with no distress in it is consolidated as usual`() {
        assertFalse(
            CrisisMemoryGate.forgetsSession(
                listOf(
                    driver("the wipers are streaking"),
                    companion("new blades then"),
                ),
            ),
        )
    }

    /**
     * The turns BEFORE the phrase are the same exchange, and the write gate never saw them: it is
     * handed one completed turn at a time and can only refuse the one it is handed.
     */
    @Test
    fun `one crisis turn forgets the whole session, earlier turns included`() {
        assertTrue(
            CrisisMemoryGate.forgetsSession(
                listOf(
                    driver("long day"),
                    companion("sounds like it"),
                    driver("i dont want to live"),
                ),
            ),
        )
    }

    /** A reply carrying the line is as much a memory of the distress as the line itself. */
    @Test
    fun `a companion turn carrying it forgets the session too`() {
        assertTrue(CrisisMemoryGate.forgetsSession(listOf(companion("the suicide prevention line is 988"))))
    }

    @Test
    fun `an empty session forgets nothing`() {
        assertFalse(CrisisMemoryGate.forgetsSession(emptyList()))
    }

    private fun driver(text: String) = turn(EpisodicTurn.Role.DRIVER, text)

    private fun companion(text: String) = turn(EpisodicTurn.Role.COMPANION, text)

    private fun turn(role: String, text: String) = EpisodicTurn(
        sessionId = "s1", vehicleId = "car:test", role = role, text = text, timestamp = 1L,
    )
}
