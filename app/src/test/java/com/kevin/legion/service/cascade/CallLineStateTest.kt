package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.VoiceOutlet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call the screen draws ([CallLine]): up at the tap, answered at the pickup's first audio, and
 * owned by one call at a time, so a conversation tearing down late can never take a newer call's
 * screen with it.
 */
class CallLineStateTest {

    @After
    fun tearDown() {
        CallLine.line.value?.let { CallLine.ended(it.callId) }
    }

    @Test
    fun `a call is up before it is answered, and the timer starts at the first audio`() {
        val id = CallLine.nextId()
        CallLine.began(id, VoiceOutlet.EARPIECE)
        assertNull("nothing to time yet", CallLine.line.value?.answeredAtMs)
        assertEquals(InCallRule.CONNECTING, InCallRule.status(CallLine.line.value?.answeredAtMs, nowMs = 5))
        CallLine.answered(id, atMs = 1_000)
        assertEquals(1_000L, CallLine.line.value?.answeredAtMs)
    }

    @Test
    fun `a later clip never restarts the timer`() {
        val id = CallLine.nextId()
        CallLine.began(id, VoiceOutlet.EARPIECE)
        CallLine.answered(id, atMs = 1_000)
        CallLine.answered(id, atMs = 90_000)
        assertEquals("answered once", 1_000L, CallLine.line.value?.answeredAtMs)
    }

    @Test
    fun `mute and the route follow the call that is up`() {
        val id = CallLine.nextId()
        CallLine.began(id, VoiceOutlet.EARPIECE)
        CallLine.muted(id, on = true)
        assertTrue(CallLine.line.value?.muted == true)
        CallLine.outlet(id, VoiceOutlet.BLUETOOTH)
        assertEquals(VoiceOutlet.BLUETOOTH, CallLine.line.value?.outlet)
        assertTrue("mute is not lost when the route moves", CallLine.line.value?.muted == true)
    }

    @Test
    fun `a closed call cannot touch the call that replaced it`() {
        val first = CallLine.nextId()
        CallLine.began(first, VoiceOutlet.EARPIECE)
        val second = CallLine.nextId()
        CallLine.began(second, VoiceOutlet.LOUDSPEAKER)
        // The first conversation's teardown lands now, as a release always does, after the next
        // conversation opened.
        CallLine.ended(first)
        CallLine.muted(first, on = true)
        CallLine.answered(first, atMs = 1)
        CallLine.outlet(first, VoiceOutlet.WIRED)
        val live = CallLine.line.value
        assertNotNull("the live call is still up", live)
        assertEquals(second, live?.callId)
        assertEquals(false, live?.muted)
        assertNull(live?.answeredAtMs)
        assertEquals(VoiceOutlet.LOUDSPEAKER, live?.outlet)
    }

    @Test
    fun `the call's own end takes the screen down`() {
        val id = CallLine.nextId()
        CallLine.began(id, VoiceOutlet.EARPIECE)
        CallLine.ended(id)
        assertNull(CallLine.line.value)
    }

    @Test
    fun `nothing is published for a conversation that never became a call`() {
        CallLine.nextId()
        assertNull("an id alone draws nothing", CallLine.line.value)
    }
}
