package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 18: the stage machine as a pure unit, no audio and no native library. */
class WakeStageMachineTest {
    private val log = mutableListOf<String>()
    private val machine = WakeStageMachine { log.add(it) }

    @Test
    fun `silence never reaches the keyword spotter`() {
        var spotCalls = 0
        repeat(50) { machine.onChunk(speech = false) { spotCalls++; true } }
        assertEquals(0, spotCalls)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `speech runs the spotter and logs the VAD edges once`() {
        var spotCalls = 0
        val first = machine.onChunk(true) { spotCalls++; false }
        machine.onChunk(true) { spotCalls++; false }
        machine.onChunk(false) { spotCalls++; false }
        assertEquals(WakeStageMachine.Step.Speech(entered = true), first)
        assertEquals(2, spotCalls)
        assertEquals(listOf("VAD on", "VAD off"), log)
    }

    @Test
    fun `hit then confirm accept opens`() {
        val step = machine.onChunk(true) { true }
        assertEquals(WakeStageMachine.Step.Candidate, step)
        assertEquals(WakeStageMachine.Verdict.OPEN, machine.onConfirm(accepted = true))
        assertTrue(log.contains("KWS hit"))
        assertTrue(log.any { it.startsWith("confirm accept") })
    }

    @Test
    fun `hit then confirm reject does not open and is logged`() {
        machine.onChunk(true) { true }
        assertEquals(WakeStageMachine.Verdict.REJECT, machine.onConfirm(accepted = false))
        assertTrue(log.any { it.startsWith("confirm reject") })
        assertFalse(log.any { it.startsWith("confirm accept") })
    }

    @Test
    fun `a rejected hit leaves the machine listening for the next one`() {
        machine.onChunk(true) { true }
        machine.onConfirm(false)
        assertEquals(WakeStageMachine.Step.Candidate, machine.onChunk(true) { true })
    }
}
