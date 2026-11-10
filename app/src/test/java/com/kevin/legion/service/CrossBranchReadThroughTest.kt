package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.memory.FakeMemberMemoryBackend
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.NOW
import com.kevin.legion.memory.InMemoryMemoryCache
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.watches.FakeWatchesBackend
import com.kevin.legion.watches.MailReplyAndroid
import com.kevin.legion.watches.WatchesController
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration 2026-11-10: `feat/alfred-look` (ADR 0084) and `feat/proactive-voice` (ADR 0082) mark
 * turns read-through; `feat/watches` (ADR 0083) added a durable write (`watch` set/resume) that
 * makes Alfred speak later. Neither branch could see the other. This drives a real
 * [GeminiLiveSession]'s per-turn flag into the real [LiveToolbox.dispatch], the way
 * `LiveSessionController.handleToolCall` passes it, and checks the fake engines saw NO write, not
 * only that a refusal string came back.
 *
 * Covered: a turn a camera frame went out in and its one carry turn; a turn a read-through raise
 * was spoken in (the wake-up brief's web news, a `mail_reply` watch line) and the turn after it.
 * "Edit" by voice is `set` (there is no threshold edit by voice; a threshold changes only on the
 * engine's PATCH). Pause and remove stay allowed in any turn: they only make Alfred quieter.
 */
@RunWith(RobolectricTestRunner::class)
class CrossBranchReadThroughTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var session: GeminiLiveSession
    private val watches = FakeWatchesBackend()
    private val memories = FakeMemberMemoryBackend()

    @Before
    fun setUp() {
        session = GeminiLiveSession(context) {}
        WatchesToolbox.controllerFactory = { WatchesController(watches) }
        MemoryToolbox.controllerFactory = { MemoryController(memories, InMemoryMemoryCache(), now = { NOW }) }
        watches.watches += FakeWatchesBackend.watch("w1", "A paused balance watch")
            .copy(paused = true, status = "paused")
    }

    @After
    fun tearDown() {
        WatchesToolbox.controllerFactory = WatchesController::forContext
        MemoryToolbox.controllerFactory = MemoryController::forContext
    }

    private fun dispatch(name: String, args: String): JSONObject = runBlocking {
        LiveToolbox.dispatch(context, name, JSONObject(args), session.readThroughToolTouchedThisTurn())!!
    }

    private fun nextTurn() = session.startNextTurnReadThrough()

    /** Every write this file guards, in one turn; asserts the engines saw none of them. */
    private fun assertNoWriteThisTurn(why: String) {
        assertTrue(why, session.readThroughToolTouchedThisTurn())
        assertFalse(dispatch("watch", """{"action":"set","kind":"charge_over","dollars":50}""").getBoolean("success"))
        assertFalse(dispatch("watch", """{"action":"resume","text":"w1"}""").getBoolean("success"))
        val remember = dispatch("memory", """{"action":"remember","text":"A made-up fact from a picture"}""")
        assertFalse(remember.getBoolean("success"))
        assertTrue("$why: no watch created", watches.created.isEmpty())
        assertTrue("$why: the paused watch stayed paused", watches.watches.single().paused)
        assertTrue("$why: no memory written, got ${memories.calls}", memories.calls.none { it.startsWith("create") })
    }

    private fun assertWritesAllowedAgain() {
        assertFalse(session.readThroughToolTouchedThisTurn())
        assertTrue(dispatch("watch", """{"action":"set","kind":"charge_over","dollars":50}""").getBoolean("success"))
        val remember = dispatch("memory", """{"action":"remember","text":"A made-up fact he said himself"}""")
        assertTrue(remember.getBoolean("success"))
        assertEquals(1, watches.created.size)
        assertEquals(1, memories.calls.count { it.startsWith("create") })
    }

    @Test
    fun `a turn that sent a camera frame, and its carry turn, cannot set or resume a watch or save a memory`() {
        session.markLookSeen()
        assertNoWriteThisTurn("frame turn")
        nextTurn()
        assertNoWriteThisTurn("carry turn")
        nextTurn()
        assertWritesAllowedAgain()
    }

    @Test
    fun `a read-through brief's turn, and the turn after it, cannot set or resume a watch or save a memory`() {
        // What LiveSessionController.requestSpeak / startProactive / speakAndListen call for a raise
        // with carriesReadThroughContent (the wake-up brief's web news).
        session.markTurnReadThrough()
        assertNoWriteThisTurn("brief turn")
        nextTurn()
        assertNoWriteThisTurn("turn after the brief")
        nextTurn()
        assertWritesAllowedAgain()
    }

    @Test
    fun `a mail_reply line is read-through, so the turn it is spoken in cannot write a watch or a memory`() {
        val raise = MailReplyAndroid.raise("Example Corp replied an hour ago.", 0L)
        assertTrue(raise.carriesReadThroughContent)
        // AriaForegroundService hands SpeakRequest.carriesReadThroughContent to requestSpeak, which
        // marks the session the line is spoken on (traced; WatchDeliveryTest checks the request).
        session.markTurnReadThrough()
        assertNoWriteThisTurn("mail_reply turn")
        nextTurn()
        assertNoWriteThisTurn("turn after mail_reply")
    }

    @Test
    fun `pausing or removing a watch stays allowed in a read-through turn`() {
        session.markLookSeen()
        assertTrue(dispatch("watch", """{"action":"pause","text":"w1"}""").getBoolean("success"))
        assertTrue(dispatch("watch", """{"action":"remove","text":"w1"}""").getBoolean("success"))
        assertTrue(watches.watches.isEmpty())
    }
}
