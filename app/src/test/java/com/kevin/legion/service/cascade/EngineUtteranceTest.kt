package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.sse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun utterance(scope: CoroutineScope, streamed: Boolean = true) =
        EngineUtterance(1, wire, scope, meta, streamed, { switched.add(it) })

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
        assertTrue(wire.uploads.single().aborted)
        assertFalse(wire.uploads.single().finished)
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
    fun `both routes unreachable is the engine unreachable, never a second brain`() = runBlocking {
        wire.streamAnswers.add(WireReply.Unreachable("ConnectException"))
        wire.clipAnswers.add(WireReply.Unreachable("ConnectException"))
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        assertEquals(SttResult.Failed(EngineUtterance.UNREACHABLE), u.result(1_000))
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
    fun `a turn the phone keeps is cancelled on the engine`() = runBlocking {
        wire.streamAnswers.add(heardStream)
        val u = utterance(this)
        val pcm = voice()
        u.audio(pcm, 0, pcm.size)
        u.end()
        u.result(1_000)
        u.release()
        kotlinx.coroutines.yield()
        assertEquals(listOf("t9"), wire.cancels)
        assertEquals("released: nothing left to answer", null, u.takeTurn())
    }
}
