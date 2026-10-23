package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [DumbModeStore]: what a second controller reads is what the first wrote. */
class FakeDumbModeStore(var snapshot: DumbModeSnapshot = DumbModeSnapshot()) : DumbModeStore {
    var writes = 0
    override fun read(): DumbModeSnapshot = snapshot
    override fun write(snapshot: DumbModeSnapshot) {
        this.snapshot = snapshot
        writes++
    }
}

class DumbModeControllerTest {
    private var now = 1_000_000L
    private val store = FakeDumbModeStore()
    private fun controller() = DumbModeController(store) { now }

    private val sentence = DumbLeaveRule.SENTENCE

    @Test
    fun `turning on records the wall-clock start and is idempotent`() {
        val c = controller()
        assertTrue(c.turnOn())
        assertEquals(DumbModeState(on = true, startedAtMs = 1_000_000L), c.state.value)
        now += 5_000
        assertFalse("a second turn-on keeps the original start", c.turnOn())
        assertEquals(1_000_000L, c.state.value.startedAtMs)
    }

    @Test
    fun `cannot leave before the 60 second wait is over`() {
        val c = controller()
        c.turnOn()
        c.requestLeave()
        now += 59_999
        assertEquals(LeaveResult.TooEarly(1L), c.leave(sentence))
        assertTrue("a refused leave changes nothing", c.state.value.on)
    }

    @Test
    fun `leaves at exactly 60 seconds with the sentence`() {
        val c = controller()
        c.turnOn()
        c.requestLeave()
        now += 60_000
        assertEquals(LeaveResult.Left, c.leave(sentence))
        assertEquals(DumbModeState(), c.state.value)
        assertEquals(listOf(DumbInterval(1_000_000L, 1_060_000L)), c.history())
    }

    @Test
    fun `the wait is wall-clock and survives a recreated controller`() {
        val first = controller()
        first.turnOn()
        first.requestLeave()
        now += 40_000
        // Process death: nothing in memory survives, only the store.
        val second = controller()
        assertEquals(20_000L, second.leaveRemainingMs())
        assertEquals(LeaveResult.TooEarly(20_000L), second.leave(sentence))
        now += 20_000
        assertEquals(0L, second.leaveRemainingMs())
        assertEquals(LeaveResult.Left, second.leave(sentence))
        assertFalse(controller().state.value.on)
    }

    @Test
    fun `asking to leave again does not restart the wait`() {
        val c = controller()
        c.turnOn()
        c.requestLeave()
        now += 30_000
        c.requestLeave()
        assertEquals(30_000L, c.leaveRemainingMs())
    }

    @Test
    fun `stay drops the pending leave and the next leave starts a fresh wait`() {
        val c = controller()
        c.turnOn()
        c.requestLeave()
        now += 45_000
        c.stay()
        assertNull(c.leaveRemainingMs())
        assertEquals(LeaveResult.NotRequested, c.leave(sentence))
        c.requestLeave()
        assertEquals(60_000L, c.leaveRemainingMs())
    }

    @Test
    fun `wrong sentence after the wait is refused and stays on`() {
        val c = controller()
        c.turnOn()
        c.requestLeave()
        now += 61_000
        assertEquals(LeaveResult.WrongSentence, c.leave("I am leaving"))
        assertTrue(c.state.value.on)
    }

    @Test
    fun `leave while off says it is not on`() {
        assertEquals(LeaveResult.NotOn, controller().leave(sentence))
    }

    @Test
    fun `a clock set backwards cannot stretch the wait past a minute`() {
        val c = controller()
        c.turnOn()
        c.requestLeave()
        now -= 3_600_000
        assertEquals(60_000L, c.leaveRemainingMs())
    }

    @Test
    fun `the sentence match ignores case and outer spaces only`() {
        assertTrue(DumbLeaveRule.sentenceMatches("  i AM choosing to LEAVE dumb mode "))
        assertFalse(DumbLeaveRule.sentenceMatches("I am choosing to leave dumb mode."))
        assertFalse(DumbLeaveRule.sentenceMatches("I am  choosing to leave dumb mode"))
        assertFalse(DumbLeaveRule.sentenceMatches(""))
    }

    @Test
    fun `history is pruned past the keep window`() {
        val old = DumbInterval(0L, 1L)
        val running = DumbModeState(on = true, startedAtMs = now - 10, leaveRequestedAtMs = now - 70_000)
        store.snapshot = DumbModeSnapshot(running, listOf(old))
        val c = controller()
        now += DumbModeController.HISTORY_KEEP_MS
        assertEquals(LeaveResult.Left, c.leave(sentence))
        assertEquals(1, c.history().size)
    }

    @Test
    fun `prefs history round trips and drops malformed pairs`() {
        val h = listOf(DumbInterval(1, 2), DumbInterval(10, 30))
        assertEquals(h, PrefsDumbModeStore.parseHistory(PrefsDumbModeStore.formatHistory(h)))
        assertEquals(listOf(DumbInterval(5, 6)), PrefsDumbModeStore.parseHistory("junk;5-6;9-3;1-2-3;"))
        assertEquals(emptyList<DumbInterval>(), PrefsDumbModeStore.parseHistory(""))
    }
}
