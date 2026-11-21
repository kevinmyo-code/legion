package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.sse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * One of his turns streamed to the engine: frames while he speaks, the end frame with the total,
 * the words back from `heard`, and each fallback the contract names. Synthetic audio only.
 */
@RunWith(RobolectricTestRunner::class)
class EngineUtteranceTest {
    private val wire = FakeEngineWire()
    private val switched = mutableListOf<Boolean>()
    private val meta = JSONObject().put("client", "phone").put("contract_version", 1).put("conversation_id", "c1")

    private fun utterance(
        scope: CoroutineScope,
        streamed: Boolean = true,
        tail: CoroutineScope = scope,
        gate: kotlinx.coroutines.Job? = null,
    ) = EngineUtterance(1, UtteranceSetup(wire, scope, tail, meta, streamed, gate), { switched.add(it) })

    /** One second of a 220 Hz tone over near-silence: voiced by [SttGuard.voicedMs]'s measure. */
    private fun voice(): ByteArray {
        val samples = 16_000
        val buf = ByteBuffer.allocate(samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val loud = i in 4_000 until 12_000
            val v = if (loud) (sin(2 * PI * 220 * i / 16_000) * 8_000).toInt() else (i % 3) - 1
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    private val heardStream
        get() = sse(
            listOf(
                event("waiting", "{}"),
                event("turn", """{"turn_id": "t9", "conversation_id": "c1", "seq": 2}"""),
                event("heard", """{"text": "start a timer for five minutes"}"""),
                event("sentence", """{"text": "Timer set.", "line_id": "2.1.1"}"""),
                event("done", """{"status": "done"}"""),
            ).joinToString("\n"),
        )

    @Test
    fun `audio streams as frames, the end frame names the total, and heard is the turn's words`() = runBlocking {
        wire.streamAnswers.add(heardStream)
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size / 2)
        u.audio(pcm, pcm.size / 2, pcm.size / 2)
        u.end()
        val r = u.result(1_000) as SttResult.Text
        assertEquals("start a timer for five minutes", r.text)
        val upload = wire.uploads.single()
        assertTrue(upload.finished)
        val body = upload.body.toByteArray()
        assertEquals('M'.code.toByte(), body[0])
        val endFrame = EngineFrames.end(pcm.size.toLong())
        val tail = body.copyOfRange(body.size - endFrame.size, body.size)
        assertTrue("the body ends with E {bytes: total}", tail.contentEquals(endFrame))
        // The rest of the stream, the events read so far first, for the turn that answers it.
        val rest = checkNotNull(u.takeTurn())
        val types = mutableListOf<String>()
        for (e in rest.events) types.add(e.javaClass.simpleName)
        assertEquals(listOf("Waiting", "Turn", "Heard", "Sentence", "Done"), types)
    }

    @Test
    fun `noise is decided from the audio before the end frame, nothing runs on the engine`() = runBlocking {
        wire.streamAnswers.add(heardStream)
        val u = utterance(this)
        val hum = ByteArray(32_000) { (it % 3).toByte() }
        u.audio(hum, 0, hum.size)
        u.end()
        val r = u.result(1_000) as SttResult.Text
        assertEquals("", r.text)
        assertTrue("no stream was even opened", wire.uploads.isEmpty())
        assertTrue(wire.clips.isEmpty())
    }

    @Test
    fun `the stream switch off sends the held clip to the batch route in the same turn`() = runBlocking {
        wire.streamAnswers.add(json(404, """{"detail": "Nothing was answered: streamed speech-to-text is off"}"""))
        wire.clipAnswers.add(heardStream)
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        val r = u.result(1_000) as SttResult.Text
        assertEquals("start a timer for five minutes", r.text)
        assertEquals(listOf(true), switched)
        val fields = wire.clips.single()
        assertEquals("c1", fields["conversation_id"])
        assertEquals("1", fields["contract_version"])
        assertEquals(EngineUtterance.ROUTE_BATCH, u.route)
    }

    @Test
    fun `an audio gap also goes to the batch route`() = runBlocking {
        wire.streamAnswers.add(json(400, """{"code": "audio_gap"}"""))
        wire.clipAnswers.add(heardStream)
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        assertEquals("start a timer for five minutes", (u.result(1_000) as SttResult.Text).text)
        assertTrue(switched.isEmpty())
    }

    @Test
    fun `an answer lost after the end frame is never posted again, and is no second brain`() = runBlocking {
        wire.streamAnswers.add(WireReply.Unreachable("SocketTimeoutException"))
        wire.clipAnswers.add(heardStream)
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        assertEquals(SttResult.Failed(EngineUtterance.UNREACHABLE), u.result(1_000))
        assertTrue("the engine may be running it: no double run", wire.clips.isEmpty())
        assertTrue(u.postedAtMs > 0)
    }

    @Test
    fun `a new stream opens only after the last cancel went, and sends the audio held meanwhile`() = runBlocking {
        wire.streamAnswers.add(heardStream)
        val cancel = kotlinx.coroutines.CompletableDeferred<Unit>()
        val u = utterance(this, gate = cancel)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size / 2)
        kotlinx.coroutines.delay(20)
        assertTrue("held while the cancel is on its way", wire.uploads.isEmpty())
        cancel.complete(Unit)
        kotlinx.coroutines.delay(20)
        u.audio(pcm, pcm.size / 2, pcm.size / 2)
        u.end()
        assertEquals("start a timer for five minutes", (u.result(1_000) as SttResult.Text).text)
        val body = wire.uploads.single().body.toByteArray()
        val endFrame = EngineFrames.end(pcm.size.toLong())
        assertTrue(body.copyOfRange(body.size - endFrame.size, body.size).contentEquals(endFrame))
    }

    @Test
    fun `a cancel goes on the tail scope, so a closed conversation still cancels the engine's turn`() = runBlocking {
        wire.streamAnswers.add(heardStream)
        val session = CoroutineScope(kotlinx.coroutines.SupervisorJob() + coroutineContext)
        val u = utterance(session, tail = this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        u.result(1_000)
        session.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        u.release()
        kotlinx.coroutines.delay(20)
        assertEquals(listOf("t9"), wire.cancels)
    }

    @Test
    fun `the agents routes switched off is the server's rollback, the phone takes the turn`() = runBlocking {
        wire.clipAnswers.add(json(404, """{"detail": "switched off"}"""))
        val u = utterance(this, streamed = false)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        assertEquals(SttResult.Failed(EngineUtterance.SWITCHED_OFF), u.result(1_000))
        assertEquals(listOf(false), switched)
    }

    @Test
    fun `the one stream busy goes to the batch route in the same turn`() = runBlocking {
        wire.streamAnswers.add(json(409, """{"code": "stream_busy"}"""))
        wire.clipAnswers.add(heardStream)
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        assertEquals("start a timer for five minutes", (u.result(1_000) as SttResult.Text).text)
        assertEquals(1, wire.clips.size)
    }

    @Test
    fun `a stream closed with an error mid-turn hands the turn what it had, then ends`() = runBlocking {
        val broken = Channel<EngineEvent>(Channel.UNLIMITED)
        broken.trySend(EngineEvent.Turn("t9", "c1", 2, 1))
        broken.trySend(EngineEvent.Heard("what time is it"))
        broken.close(java.io.IOException("connection reset"))
        wire.streamAnswers.add(WireReply.Events(broken) {})
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        assertEquals("what time is it", (u.result(1_000) as SttResult.Text).text)
        val rest = checkNotNull(u.takeTurn())
        val seen = mutableListOf<EngineEvent>()
        var next = rest.events.receiveCatching().getOrNull()
        while (next != null) {
            seen.add(next)
            next = rest.events.receiveCatching().getOrNull()
        }
        assertEquals(2, seen.size)
    }

    @Test
    fun `a turn the phone keeps is cancelled on the engine`() = runBlocking {
        wire.streamAnswers.add(heardStream)
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        u.result(1_000)
        u.release()
        kotlinx.coroutines.delay(20)
        assertEquals(listOf("t9"), wire.cancels)
        assertEquals("released: nothing left to answer", null, u.takeTurn())
    }
}
