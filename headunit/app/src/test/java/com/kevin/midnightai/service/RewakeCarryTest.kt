package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin, 2026-11-24, phone parity: start talking to Moose again within about a minute of a
 * conversation ending and he still has that conversation. What a caller can see: what is carried,
 * for how long, and which ends leave nothing.
 */
class RewakeCarryTest {

    private fun conversation(carry: RewakeCarry) {
        carry.turn("what should I put on", "The live record or the studio one. Your call.")
    }

    @Test
    fun `a conversation he stopped is in front of the next one inside the window`() {
        val carry = RewakeCarry()
        conversation(carry)
        carry.ended(1_000)
        val block = carry.take(1_000 + 30_000)
        assertNotNull(block)
        assertTrue(block!!.contains("Driver: what should I put on"))
        assertTrue(block.contains("You: The live record or the studio one."))
        assertTrue("it says where it came from", block.contains("ended a moment ago"))
    }

    @Test
    fun `after the window there is nothing`() {
        val carry = RewakeCarry()
        conversation(carry)
        carry.ended(1_000)
        assertNull(carry.take(1_000 + RewakeCarry.WINDOW_MS + 1))
    }

    @Test
    fun `the last moment of the window still carries`() {
        val carry = RewakeCarry()
        conversation(carry)
        carry.ended(1_000)
        assertNotNull(carry.take(1_000 + RewakeCarry.WINDOW_MS))
    }

    @Test
    fun `a cold start that fails does not spend the context`() {
        val carry = RewakeCarry()
        conversation(carry)
        carry.ended(1_000)
        assertNotNull("the connect takes it", carry.take(1_100))
        assertNotNull("the connect failed; his next tap still has it", carry.take(1_200))
    }

    @Test
    fun `a conversation that opened too late never gets it back`() {
        val carry = RewakeCarry()
        conversation(carry)
        carry.ended(1_000)
        carry.take(1_000 + RewakeCarry.WINDOW_MS + 1)
        assertNull(carry.take(1_500))
    }

    @Test
    fun `a conversation that never ended leaves nothing`() {
        val carry = RewakeCarry()
        conversation(carry)
        // The socket dropped, errored, or the controller replaced it mid-conversation.
        carry.abandoned()
        assertNull(carry.take(1_100))
    }

    @Test
    fun `a socket closed after the conversation already ended keeps what it left`() {
        val carry = RewakeCarry()
        conversation(carry)
        // It parked warm, then the server or the hold timer took the socket away.
        carry.ended(1_000)
        carry.abandoned()
        assertNotNull("the driver must not lose context to a dead socket", carry.take(1_100))
    }

    @Test
    fun `turns accumulate across a park, so a proactive line does not replace his conversation`() {
        val carry = RewakeCarry()
        carry.turn("the first one", "sure")
        carry.ended(1_000)
        // A proactive line spoken on the warm socket, then another exchange.
        carry.turn("", "Rain in ten minutes.")
        carry.turn("the second one", "fine")
        carry.ended(2_000)
        val block = carry.take(2_100)!!
        assertTrue(block.contains("the first one"))
        assertTrue(block.contains("Rain in ten minutes."))
        assertTrue(block.contains("the second one"))
    }

    @Test
    fun `an end with nothing said leaves nothing`() {
        val carry = RewakeCarry()
        carry.ended(1_000)
        assertNull(carry.take(1_100))
    }

    @Test
    fun `a blank turn is not a turn`() {
        val carry = RewakeCarry()
        carry.turn("  ", "")
        carry.ended(1_000)
        assertNull(carry.take(1_100))
    }

    @Test
    fun `only the newest turns are held`() {
        val carry = RewakeCarry()
        for (i in 1..RewakeCarry.MAX_TURNS + 4) carry.turn("line $i", "reply $i")
        carry.ended(1_000)
        val block = carry.take(1_100)!!
        assertEquals(RewakeCarry.MAX_TURNS, Regex("Driver: ").findAll(block).count())
        assertTrue(block.contains("line ${RewakeCarry.MAX_TURNS + 4}"))
        assertFalse(block.contains("line 1\n"))
    }

    @Test
    fun `a clock that went backwards carries nothing`() {
        assertFalse(RewakeCarry.carries(endedAtMs = 10_000, nowMs = 9_000))
    }

    @Test
    fun `only his own tap ends a conversation at the close`() {
        assertTrue(RewakeCarry.endsHisConversation("stopped"))
        for (reason in listOf("idle", "warm expired", "go away", "stale socket", "destroyed", "internal error")) {
            assertFalse(reason, RewakeCarry.endsHisConversation(reason))
        }
    }
}
