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

    // --- review of 264baf63 ---------------------------------------------------------------------------

    @Test
    fun `a VoIP call ringing through telecom, ignored, closes nothing and blocks no open (C2)`() {
        val ring = signals().copy(telecomCall = true, ringtone = true)
        assertNull(CallPause.reason(ring))
        assertFalse(CallPause.blocksOpen(ring))
        // Answered: the ring is over, the call is up.
        assertEquals(CallPause.Reason.VOIP, CallPause.reason(ring.copy(ringtone = false)))
    }

    @Test
    fun `the capture signals wait out the wake word's hand-over after the open (C3)`() {
        val grace = CallPause.CAPTURE_GRACE_MS
        val silenced = signals().copy(micSilenced = true)
        assertNull(CallPause.reason(silenced.copy(sinceStartMs = 0)))
        assertNull(CallPause.reason(silenced.copy(sinceStartMs = grace - 1)))
        assertEquals(CallPause.Reason.MIC_SILENCED, CallPause.reason(silenced.copy(sinceStartMs = grace)))
        val focus = signals().copy(focusLost = true, otherCallCapture = true)
        assertNull(CallPause.reason(focus.copy(sinceStartMs = 200)))
        assertEquals(CallPause.Reason.FOCUS, CallPause.reason(focus.copy(sinceStartMs = grace)))
        // A real call is not held back by the grace.
        assertEquals(CallPause.Reason.CALL, CallPause.reason(signals().copy(cellularCall = true, sinceStartMs = 0)))
    }

    @Test
    fun `a silenced capture closes with its own token and leaves the next open free (C4)`() {
        assertEquals("paused_mic_silenced", CallPause.closeReason(CallPause.Reason.MIC_SILENCED))
        assertEquals("conversation_paused reason=mic_silenced", CallPause.line(CallPause.Reason.MIC_SILENCED))
        // The door reads no capture signal: the wake word after it opens at once.
        assertFalse(CallPause.blocksOpen(signals().copy(micSilenced = true, sinceStartMs = 5_000)))
    }

    @Test
    fun `a communication mode left behind with no call in it stops blocking after 10 s (C5)`() {
        val stale = CallPause.StaleComm()
        val left = signals(mode = comm).copy(otherCallAudio = false)
        assertFalse(stale.feed(0, left))
        assertFalse(stale.feed(CallPause.STALE_COMM_MS - 1, left))
        assertTrue(stale.feed(CallPause.STALE_COMM_MS, left))
        assertTrue(CallPause.blocksOpen(left))
        assertFalse(CallPause.blocksOpen(left.copy(commStale = true)))
        assertNull(CallPause.reason(left.copy(commStale = true, sinceStartMs = 5_000)))
        // Any sign of a call starts the count again: call audio, a telecom call.
        assertFalse(stale.feed(CallPause.STALE_COMM_MS + 1, left.copy(otherCallAudio = true)))
        assertFalse(stale.feed(CallPause.STALE_COMM_MS + 2, left))
        assertFalse(stale.feed(2 * CallPause.STALE_COMM_MS, left.copy(telecomCall = true)))
        // Our own mode is never "left behind".
        val ours = CallPause.StaleComm()
        assertFalse(ours.feed(0, signals(mode = comm, ownComm = true).copy(otherCallAudio = false)))
        assertFalse(ours.feed(60_000, signals(mode = comm, ownComm = true).copy(otherCallAudio = false)))
    }

    private companion object {
        val LINE = Regex("conversation_paused reason=(call|voip|mic_silenced|focus)")
    }
}
