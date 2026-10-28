package com.kevin.legion.service.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kevin's 2026-10-27 rulings as a decision table. */
class TurnConfigTest {
    @Test
    fun `phone speaker with both switches on is local end of turn plus barge-in`() {
        val c = TurnConfig.decide(true, true, AudioRoute.PHONE_SPEAKER, sileroReady = true, smartTurnReady = true)
        assertTrue(c.manualActivity && c.bargeIn && c.smartTurn)
    }

    @Test
    fun `bluetooth keeps today's half-duplex server VAD`() {
        val c = TurnConfig.decide(true, true, AudioRoute.BLUETOOTH, sileroReady = true, smartTurnReady = true)
        assertEquals(TurnConfig.SERVER_VAD.copy(note = c.note), c)
    }

    @Test
    fun `a wired headset keeps barge-in`() {
        assertTrue(TurnConfig.decide(true, true, AudioRoute.WIRED_HEADSET, true, true).bargeIn)
    }

    @Test
    fun `no silero means server VAD and no barge-in`() {
        val c = TurnConfig.decide(true, true, AudioRoute.PHONE_SPEAKER, sileroReady = false, smartTurnReady = false)
        assertFalse(c.manualActivity || c.bargeIn)
    }

    @Test
    fun `no smart turn keeps manual activity on silence alone`() {
        val c = TurnConfig.decide(true, false, AudioRoute.PHONE_SPEAKER, sileroReady = true, smartTurnReady = false)
        assertTrue(c.manualActivity)
        assertFalse(c.smartTurn)
        assertFalse(c.bargeIn)
    }

    @Test
    fun `barge-in alone rides server VAD`() {
        val c = TurnConfig.decide(false, true, AudioRoute.PHONE_SPEAKER, sileroReady = true, smartTurnReady = true)
        assertFalse(c.manualActivity)
        assertTrue(c.bargeIn)
    }
}
