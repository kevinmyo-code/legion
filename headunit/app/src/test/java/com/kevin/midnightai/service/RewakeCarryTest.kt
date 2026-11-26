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

    /**
     * The bug a park used to hide. `turn()` appends across a park by design, and while the end
     * stamp survived a resume, the resumed conversation's own completed turns sat under the old
     * park's stamp and `abandoned()` was a no-op. A drop, an error or a teardown then left a
     * torn-down conversation behind for the next cold connect to replay. [RewakeCarry.running],
     * called when a conversation starts or resumes on the socket, is what fixes it.
     */
    @Test
    fun `a conversation resumed after a park and then torn down leaves nothing`() {
        val carry = RewakeCarry()
        carry.turn("what should I put on", "The live record.")
        carry.ended(1_000)
        // The driver taps and the warm socket resumes, so a conversation is running again.
        carry.running()
        carry.turn("and the other side", "Side B it is.")
        // The socket drops, errors, or the controller replaces it mid-conversation.
        carry.abandoned()
        assertNull("nothing of a torn-down conversation, its own turns included", carry.take(1_100))
    }

    @Test
    fun `a crisis wipes it whether the conversation ended or not`() {
        val ended = RewakeCarry()
        conversation(ended)
        ended.ended(1_000)
        ended.wipe()
        assertNull("not replayed into the next greeting prompt", ended.take(1_100))
        val running = RewakeCarry()
        conversation(running)
        running.running()
        running.wipe()
        assertNull(running.take(1_100))
    }

    @Test
    fun `a resumed conversation that ends properly still carries`() {
        val carry = RewakeCarry()
        carry.turn("the first one", "sure")
        carry.ended(1_000)
        carry.running()
        carry.turn("the second one", "fine")
        carry.ended(2_000)
        val block = carry.take(2_100)!!
        assertTrue("both exchanges, as the park rule intends", block.contains("the first one"))
        assertTrue(block.contains("the second one"))
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

    /**
     * The crisis sequence as the controller now runs it, played on the holder: a pair admitted while
     * the socket was being torn down, this handler's own park stamping it, `silentDestroy`'s
     * `abandoned()` keeping it BECAUSE it is stamped, and the controller's wipe last. Nothing is
     * left for the next cold conversation.
     *
     * Reverse the last two calls and this is the leak the order exists to stop: `abandoned()` keeps
     * a stamped deque, so a wipe before the teardown leaves the stamped pair behind. The order
     * itself is pinned in `RewakeCarryWiringTest`, which is what goes red if the controller is put
     * back the old way; this pins what the order buys.
     */
    @Test
    fun `a stamped turn admitted during a teardown does not survive a crisis`() {
        val carry = RewakeCarry()
        carry.turn("i can't do this any more", "I'm not equipped for that. Call 988.")
        carry.ended(1_000)
        carry.abandoned()
        assertNotNull("a stamped deque survives the teardown, which is why the wipe must come after", carry.take(1_100))
        carry.wipe()
        assertNull(carry.take(1_100))
    }

    /**
     * Every method takes the holder's own monitor, and the crisis guarantee rests on it.
     * `GeminiLiveSession` `carryTurn` checks `closed` and admits a turn inside `synchronized(holder)`
     * so that `silentDestroy`'s `abandoned()` cannot run between the check and the insert. That only
     * excludes anything while the holder's own methods take the SAME monitor, which is what
     * `@Synchronized` means on an instance method and nothing else states.
     *
     * Behavioural, not a source pin: this thread holds the monitor, a second thread calls the
     * method, and the call must park. Drop the annotation and it runs straight through instead, so
     * the thread finishes and this goes red.
     */
    @Test
    fun `every method waits for the holder's own monitor, which is what the admission check holds`() {
        val carry = RewakeCarry()
        val calls = mapOf<String, () -> Unit>(
            "running" to { carry.running() },
            "turn" to { carry.turn("what's the voltage", "Twelve six. Fine.") },
            "ended" to { carry.ended(1_000) },
            "abandoned" to { carry.abandoned() },
            "wipe" to { carry.wipe() },
            "take" to { carry.take(1_000) },
        )
        for ((name, call) in calls) {
            val caller = Thread(call, "carry-$name")
            synchronized(carry) {
                caller.start()
                assertTrue(
                    "$name ran while another thread held the holder's monitor, so it is not @Synchronized",
                    parks(caller),
                )
            }
            caller.join(5_000)
            assertFalse("$name never finished once the monitor was free", caller.isAlive)
        }
    }

    /** True once the thread is waiting on a monitor; false if it finished instead, or never started. */
    private fun parks(thread: Thread): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            when (thread.state) {
                Thread.State.BLOCKED -> return true
                Thread.State.TERMINATED -> return false
                else -> Thread.sleep(1)
            }
        }
        return false
    }

    @Test
    fun `only his own tap ends a conversation at the close`() {
        assertTrue(RewakeCarry.endsHisConversation("stopped"))
        for (reason in listOf("idle", "warm expired", "go away", "stale socket", "destroyed", "internal error")) {
            assertFalse(reason, RewakeCarry.endsHisConversation(reason))
        }
    }
}
