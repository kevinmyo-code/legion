package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.sse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A relayed engine turn against a fake engine (relay contract, engine report section 11): request,
 * run, result, resumed sentences; cancel between hops; dedupe; a dropped stream recovered; the
 * answers a result post can get. Invented data. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class EngineRelayTest {
    private val wire = FakeEngineWire()
    private val ran = CopyOnWriteArrayList<String>()
    private val said = CopyOnWriteArrayList<String>()

    private val sink = object : EngineRelaySink {
        override fun event(event: EngineEvent) {
            if (event is EngineEvent.Sentence) said.add(event.text)
        }

        override fun toolsStarting(calls: List<EngineCall>) = Unit
        override fun toolEnded(call: EngineCall, outcome: CallOutcome) = Unit
    }

    private fun relay(scope: CoroutineScope, runner: suspend (EngineCall) -> CallOutcome = ::okRun): EngineRelay {
        val calls = EngineCalls(scope, runner, { call, out ->
            wire.toolResult(call.turnId, JSONObject().put("late", out.outcome))
        })
        return EngineRelay(wire, calls, sink, EngineResultPoster(wire) { })
    }

    private suspend fun okRun(call: EngineCall): CallOutcome {
        ran.add(call.callId)
        return CallOutcome(CallOutcome.OK, JSONObject().put("left_min", 9), "Nine minutes left.", 3)
    }

    private fun request(id: String, name: String = "clock", writes: Boolean = false, deadline: Long = 20_000) =
        event(
            "tool_request",
            """{"turn_id": "t1", "call_id": "$id", "name": "$name", "args": {}, "writes": $writes,
            "deadline_ms": $deadline, "confirm": "limb"}""".replace("\n", " "),
        )

    private fun awaiting(vararg ids: String) =
        event("done", """{"status": "awaiting_limb", "calls": [${ids.joinToString { "\"$it\"" }}]}""")

    private val turn = event("turn", """{"turn_id": "t1", "conversation_id": "c1", "seq": 1}""")
    private val doneOk = event("done", """{"status": "done", "writes_ran": false}""")

    private fun stream(vararg parts: String) = sse(parts.joinToString("\n"))

    private fun sentence(text: String) = event("sentence", """{"text": "$text", "line_id": "1.2.1"}""")

    @Test
    fun `a relayed turn runs the call, posts its result and speaks the continuation`() = runBlocking {
        val second = event("turn", """{"turn_id": "t1", "stream_no": 2}""")
        wire.resultAnswers.add(stream(second, sentence("Nine minutes left."), doneOk))
        val end = relay(this).follow(stream(turn, request("k1"), awaiting("k1")))
        assertEquals("done", end.status)
        assertEquals(listOf("k1"), ran)
        val (turnId, body) = wire.posts.single()
        assertEquals("t1", turnId)
        assertEquals("k1", body.getString("call_id"))
        assertEquals("ok", body.getString("outcome"))
        assertEquals(9, body.getJSONObject("result").getInt("left_min"))
        assertEquals(listOf("Nine minutes left."), said)
    }

    @Test
    fun `two calls in a round run one at a time in the engine's order`() = runBlocking {
        wire.resultAnswers.add(json(202, """{"waiting_for": ["k1"]}"""))
        wire.resultAnswers.add(stream(sentence("Both done."), doneOk))
        relay(this).follow(stream(turn, request("k2"), request("k1"), awaiting("k1", "k2")))
        assertEquals(listOf("k1", "k2"), ran)
        assertEquals(listOf("k1", "k2"), wire.posts.map { it.second.getString("call_id") })
    }

    @Test
    fun `a call id asked again is never run twice, its stored result is posted again`() = runBlocking {
        wire.resultAnswers.add(stream(request("k1"), awaiting("k1")))
        wire.resultAnswers.add(stream(sentence("Done."), doneOk))
        relay(this).follow(stream(turn, request("k1"), awaiting("k1")))
        assertEquals("run once", listOf("k1"), ran)
        assertEquals(2, wire.posts.size)
        assertEquals(wire.posts[0].second.toString(), wire.posts[1].second.toString())
    }

    @Test
    fun `cancel between hops posts the cancel, then not_run for each unstarted call`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val r = relay(this) { call ->
            started.complete(Unit)
            gate.await()
            okRun(call)
        }
        val job = async { r.follow(stream(turn, request("k1"), request("k2"), awaiting("k1", "k2"))) }
        started.await()
        job.cancel()
        // The running call finishes after the cancel: its real result is still posted.
        val cancelling = async { r.cancel() }
        gate.complete(Unit)
        cancelling.await()
        assertEquals(listOf("t1"), wire.cancels)
        val outcomes = wire.posts.associate { it.second.getString("call_id") to it.second.getString("outcome") }
        assertEquals("ok", outcomes["k1"])
        assertEquals("not_run", outcomes["k2"])
    }

    @Test
    fun `a stream that drops after the request is recovered once through the status route`() = runBlocking {
        val asked = """{"turn_id": "t1", "call_id": "k1", "name": "clock", "args": {}, "deadline_ms": 20000}"""
        val status = """{"turn_id": "t1", "status": "awaiting_limb", "calls": [$asked], "waiting_for": ["k1"]}"""
        wire.statusAnswers.add(json(200, status))
        wire.resultAnswers.add(stream(sentence("Recovered."), doneOk))
        val end = relay(this).follow(stream(turn, request("k1")))
        assertEquals(listOf("t1"), wire.statusAsked)
        assertEquals(listOf("k1"), ran)
        assertEquals("done", end.status)
        assertEquals(listOf("Recovered."), said)
    }

    @Test
    fun `a drop the status route cannot pick up ends the turn in words`() = runBlocking {
        val end = relay(this).follow(sse(turn))
        assertEquals(EngineTurnEnd.FAILED, end.status)
        assertEquals(EngineBrainRules.STOPPED_LINE, end.words)
    }

    @Test
    fun `a throttled post is retried after Retry-After, a revoked token ends the turn in words`() = runBlocking {
        wire.resultAnswers.add(json(429, """{"code": "throttled"}""", retryAfterS = 1))
        wire.resultAnswers.add(json(401, """{"detail": "Invalid token."}"""))
        val end = relay(this).follow(stream(turn, request("k1"), awaiting("k1")))
        assertEquals("posted twice", 2, wire.posts.size)
        assertEquals(EngineBrainRules.REVOKED_LINE, end.words)
    }

    @Test
    fun `a member cap after a result says the engine's own words`() = runBlocking {
        val cap = """{"recorded": true, "code": "member_cap", "detail": "Today's allowance is used up."}"""
        wire.resultAnswers.add(json(429, cap))
        val end = relay(this).follow(stream(turn, request("k1"), awaiting("k1")))
        assertEquals("Today's allowance is used up.", end.words)
        assertEquals(1, wire.posts.size)
    }

    @Test
    fun `a write past its deadline is timed_out, and ok for the same id once it lands`() = runBlocking {
        val r = relay(this) { call ->
            delay(LATE_MS)
            okRun(call)
        }
        wire.resultAnswers.add(sse(doneOk))
        r.follow(stream(turn, request("w1", writes = true, deadline = 20), awaiting("w1")))
        assertEquals("timed_out", wire.posts.single().second.getString("outcome"))
        delay(LATE_MS * 2)
        assertEquals("ok", wire.posts.last().second.getString("late"))
    }

    @Test
    fun `no first event within the deadline is unreachable`() = runBlocking {
        val silent = WireReply.Events(Channel()) {}
        val end = relay(this).follow(silent, firstEventMs = 20)
        assertEquals(EngineBrainRules.UNREACHABLE_LINE, end.words)
        assertNull(end.turnId)
        assertTrue(wire.posts.isEmpty())
    }

    @Test
    fun `device actions after the reply run once and post, and an unplayed reply posts not_run`() = runBlocking {
        val r = relay(this)
        r.follow(stream(turn, doneOk))
        val action = EngineEventParse.call(
            JSONObject().put("turn_id", "t1").put("call_id", "d1").put("name", "control_music"),
            afterReply = true,
        )
        r.runAfterReply(listOf(action))
        r.runAfterReply(listOf(action))
        assertEquals("run once, posted twice", listOf("d1"), ran)
        assertEquals(listOf("ok", "ok"), wire.posts.map { it.second.getString("outcome") })
        val skipped = action.copy(callId = "d2")
        r.runAfterReply(listOf(skipped), run = false)
        assertEquals("not_run", wire.posts.last().second.getString("outcome"))
        assertEquals(listOf("d1"), ran)
    }

    @Test
    fun `a stream that breaks with an error ends quietly, never a crash`() = runBlocking {
        val broken = Channel<EngineEvent>(Channel.UNLIMITED)
        broken.trySend(EngineEvent.Turn("t1", "c1", 1, 1))
        broken.close(java.io.IOException("connection reset"))
        val end = relay(this).follow(WireReply.Events(broken) {})
        assertEquals(EngineBrainRules.STOPPED_LINE, end.words)
    }

    private companion object {
        const val LATE_MS = 150L
    }
}
