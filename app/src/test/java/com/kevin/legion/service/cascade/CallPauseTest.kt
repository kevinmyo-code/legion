package com.kevin.legion.service.cascade

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call or a silenced capture closes the conversation quietly ([CallPause], 2026-11-21); LEGION's own
 * call audio does not, and the open's door refuses during a call.
 */
class CallPauseTest {
    private val normal = AudioManager.MODE_NORMAL
    private val comm = AudioManager.MODE_IN_COMMUNICATION

    private fun signals(mode: Int = normal, ownComm: Boolean = false) = CallPause.Signals(
        cellularCall = false,
        ringing = false,
        telecomCall = false,
        audioMode = mode,
        ownComm = ownComm,
    )

    @Test
    fun `each trigger closes, with its own reason`() {
        assertEquals(CallPause.Reason.CALL, CallPause.reason(signals().copy(cellularCall = true)))
        assertEquals(CallPause.Reason.CALL, CallPause.reason(signals(mode = AudioManager.MODE_IN_CALL)))
        assertEquals("another app holds the call mode", CallPause.Reason.VOIP, CallPause.reason(signals(mode = comm)))
        assertEquals(CallPause.Reason.VOIP, CallPause.reason(signals().copy(telecomCall = true)))
        assertEquals(CallPause.Reason.MIC_SILENCED, CallPause.reason(signals().copy(micSilenced = true)))
        assertEquals(
            CallPause.Reason.FOCUS,
            CallPause.reason(signals().copy(focusLost = true, otherCallCapture = true)),
        )
    }

    @Test
    fun `our own call audio for the earpiece, speaker or Bluetooth route is not a call`() {
        assertNull(CallPause.reason(signals(mode = comm, ownComm = true)))
        assertFalse(CallPause.blocksOpen(signals(mode = comm, ownComm = true)))
    }

    @Test
    fun `a VoIP call that starts while we hold the call mode is still seen, by the signals the mode cannot give`() {
        // Tonight's shape: LEGION entered call audio (previous mode normal), then a call began.
        val ours = signals(mode = comm, ownComm = true)
        assertEquals(CallPause.Reason.MIC_SILENCED, CallPause.reason(ours.copy(micSilenced = true)))
        assertEquals(CallPause.Reason.VOIP, CallPause.reason(ours.copy(telecomCall = true)))
        assertEquals(CallPause.Reason.FOCUS, CallPause.reason(ours.copy(focusLost = true, otherCallCapture = true)))
    }

    @Test
    fun `music taking focus, an alarm, or a ringing phone keeps the conversation open`() {
        assertNull("a bare focus loss", CallPause.reason(signals().copy(focusLost = true)))
        assertNull(CallPause.reason(signals(mode = comm, ownComm = true).copy(focusLost = true)))
        // "Answer it" is said while it rings: neither the call state nor telecom's ringing call counts.
        assertNull(CallPause.reason(signals().copy(ringing = true, telecomCall = true)))
        assertNull(CallPause.reason(signals(mode = AudioManager.MODE_RINGTONE).copy(telecomCall = true)))
        assertFalse(CallPause.blocksOpen(signals().copy(ringing = true, telecomCall = true)))
    }

    @Test
    fun `the wake word and a tap start nothing during a call`() {
        assertTrue(CallPause.blocksOpen(signals().copy(cellularCall = true)))
        assertTrue(CallPause.blocksOpen(signals(mode = comm)))
        assertTrue(CallPause.blocksOpen(signals().copy(telecomCall = true)))
        assertFalse(CallPause.blocksOpen(signals()))
        // Capture and focus belong to an open conversation; at the door they decide nothing.
        assertFalse(CallPause.blocksOpen(signals().copy(micSilenced = true, focusLost = true, otherCallCapture = true)))
    }

    @Test
    fun `one reading is not enough, two in a row fire once`() {
        val d = CallPause.Debounce()
        // Our own call audio sets the mode just before it marks it ours: one stray reading.
        assertNull(d.feed(CallPause.Reason.VOIP))
        assertNull(d.feed(null))
        assertNull(d.feed(CallPause.Reason.CALL))
        assertEquals(CallPause.Reason.CALL, d.feed(CallPause.Reason.CALL))
        assertNull("once", d.feed(CallPause.Reason.CALL))
    }

    @Test
    fun `the close is quiet - no chime, and the log line holds a token only`() {
        for (reason in CallPause.Reason.entries) {
            val skip = CloseCue.decide(
                CallPause.closeReason(reason),
                quiet = false,
                onCall = false,
                audioOpened = true,
                alreadyPlayed = false,
                rateMatches = true,
            )
            assertEquals(CloseCue.Skip.NOT_A_CLOSE, skip)
            assertTrue(CallPause.line(reason).matches(LINE))
        }
    }

    private companion object {
        val LINE = Regex("conversation_paused reason=(call|voip|mic_silenced|focus)")
    }
}
