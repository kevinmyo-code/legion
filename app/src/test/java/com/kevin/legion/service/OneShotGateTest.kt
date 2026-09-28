package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotGateTest {

    @Test
    fun `a greeting's turn end never hangs up - only the reply to the user does`() {
        // The case that makes this a gate at all: open, greet, THEN listen.
        val g = OneShotGate().apply { start(oneShot = true) }
        assertFalse("the greeting ended before the user said anything", g.onTurnComplete(inConversation = true))
        g.onMicOpened()
        assertTrue("the reply to what he said", g.onTurnComplete(inConversation = true))
    }

    @Test
    fun `mic straight away, then the reply, hangs up`() {
        val g = OneShotGate().apply { start(oneShot = true) }
        g.onMicOpened()
        assertTrue(g.onTurnComplete(inConversation = true))
    }

    @Test
    fun `it fires once`() {
        val g = OneShotGate().apply { start(oneShot = true) }
        g.onMicOpened()
        assertTrue(g.onTurnComplete(true))
        g.onMicOpened()
        assertFalse(g.onTurnComplete(true))
    }

    @Test
    fun `an ordinary tap never hangs up`() {
        val g = OneShotGate().apply { start(oneShot = false) }
        g.onMicOpened()
        assertFalse(g.onTurnComplete(true))
    }

    @Test
    fun `a plain tap after a one-shot disarms it`() {
        val g = OneShotGate().apply { start(oneShot = true) }
        g.start(oneShot = false)
        g.onMicOpened()
        assertFalse(g.onTurnComplete(true))
    }

    @Test
    fun `a speak-only proactive turn never counts`() {
        val g = OneShotGate().apply { start(oneShot = true) }
        g.onMicOpened()
        assertFalse(g.onTurnComplete(inConversation = false))
    }

    @Test
    fun `arming mid-conversation ends after the next reply`() {
        val g = OneShotGate()
        g.armMidConversation()
        assertTrue(g.onTurnComplete(true))
    }
}
