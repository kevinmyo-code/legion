package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine's turn stream as the phone reads it: the shapes of the relay report section 2 and the
 * pipeline's event list. Invented values. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class EngineEventsTest {

    private fun read(vararg events: String): List<EngineEvent> = runBlocking {
        val out = mutableListOf<EngineEvent>()
        for (e in FakeEngineWire.sse(events.joinToString("\n")).events) out.add(e)
        out
    }

    @Test
    fun `a relayed first stream reads as turn, request and done awaiting the limb`() {
        val events = read(
            event("waiting", "{}"),
            event("turn", """{"turn_id": "t1", "conversation_id": "c1", "seq": 3, "stream_no": 1}"""),
            event("heard", """{"text": "is the oven timer on"}"""),
            event(
                "tool_request",
                """{"turn_id": "t1", "call_id": "k1", "name": "clock", "args": {"action": "timer_left"},
                "writes": false, "read_through": false, "actuates": true, "tainted": false,
                "deadline_ms": 20000, "needs": [], "confirm": "limb", "limb_id": "l1"}""".replace("\n", " "),
            ),
            event("done", """{"status": "awaiting_limb", "final_text": "", "writes_ran": false, "calls": ["k1"]}"""),
        )
        assertEquals(EngineEvent.Waiting, events[0])
        assertEquals(EngineEvent.Turn("t1", "c1", 3, 1), events[1])
        assertEquals(EngineEvent.Heard("is the oven timer on"), events[2])
        val call = (events[3] as EngineEvent.Request).call
        assertEquals("k1", call.callId)
        assertEquals("timer_left", call.args.getString("action"))
        assertTrue(call.actuates)
        assertEquals("limb", call.confirm)
        assertEquals(20_000L, call.deadlineMs)
        val done = events[4] as EngineEvent.Done
        assertEquals(EngineEvent.AWAITING_LIMB, done.status)
        assertEquals(listOf("k1"), done.calls)
    }

    @Test
    fun `sentences keep their line ids, and audio is never decoded`() {
        val events = read(
            event("sentence", """{"text": "Nine minutes left.", "line_id": "3.2.1", "role": "answer"}"""),
            event("audio", """{"pcm": "AAAA", "rate": 24000, "line_id": "3.2.1"}"""),
            event("filler", """{"tool": "web_search", "text": "Looking.", "line_id": "3.1.1"}"""),
        )
        assertEquals(EngineEvent.Sentence("Nine minutes left.", "3.2.1", "answer"), events[0])
        assertEquals(EngineEvent.Other("audio"), events[1])
        assertEquals(EngineEvent.Other("filler"), events[2])
    }

    @Test
    fun `done carries the taint, the end, and K1 device actions to run after the reply`() {
        val done = read(
            event(
                "done",
                """{"status": "done", "final_text": "[reply after an outside read: not kept]", "writes_ran": false,
                "tainted": true, "end": true, "device_actions": [{"turn_id": "t1", "call_id": "d1",
                "name": "control_music", "args": {"action": "pause"}, "after_reply": true}]}""".replace("\n", " "),
            ),
        ).single() as EngineEvent.Done
        assertTrue(done.tainted)
        assertTrue(done.end)
        val action = done.deviceActions.single()
        assertEquals("d1", action.callId)
        assertTrue(action.afterReply)
    }

    @Test
    fun `a tool event with no guard, an error and a broken data line`() {
        val events = read(
            event("tool", """{"name": "read_calendar", "ok": true, "outcome": "ok", "guard": null}"""),
            event("error", """{"words": "That could not run."}"""),
            event("sentence", "{not json"),
        )
        val tool = events[0] as EngineEvent.Tool
        assertNull(tool.guard)
        assertTrue(tool.ok)
        assertEquals(EngineEvent.Failed("That could not run."), events[1])
        assertEquals(EngineEvent.Other("sentence"), events[2])
    }

    @Test
    fun `a comment line and a data line with no event are skipped`() {
        val reader = EngineSseReader()
        assertNull(reader.line(": keep-alive"))
        assertNull(reader.line("data: {}"))
        assertNull(reader.line(""))
        assertFalse(reader.line("event: waiting") != null)
        assertEquals(EngineEvent.Waiting, reader.line(""))
    }
}
