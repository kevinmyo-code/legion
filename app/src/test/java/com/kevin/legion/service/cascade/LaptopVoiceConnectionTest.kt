@file:Suppress("MaxLineLength", "ReturnCount", "EmptyFunctionBlock") // test fixtures: invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.Collections

/**
 * [LaptopVoiceConnection] against an in-memory fake of the laptop service (wire contract protocol
 * 1 + 4a), and [LaptopProbe] against a real local HTTP server. Invented audio and text only.
 */
@RunWith(RobolectricTestRunner::class)
class LaptopVoiceConnectionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = LaptopVoiceConfig("http://127.0.0.1:8765", "invented-token")

    @After
    fun tearDown() = scope.cancel()

    /**
     * The fake service. [onMessage] reacts to each JSON frame the phone sends; [toPhone] pushes
     * frames down. Records every text frame and every mic frame.
     */
    private inner class FakeLaptop(
        val readyStt: JSONObject = JSONObject().put("sample_rate", 16000),
        val readyTts: JSONObject = JSONObject().put("sample_rate", 24000),
        val sendReady: Boolean = true,
        val protocol: Int = 1,
        val onMessage: FakeLaptop.(JSONObject) -> Unit = {},
    ) : WireConnector {
        val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
        val mic = Collections.synchronizedList(mutableListOf<ByteArray>())
        var headers: List<Pair<String, String>> = emptyList()
        lateinit var phone: WireListener
        var closed = false

        override fun connect(url: String, headers: List<Pair<String, String>>, listener: WireListener): WireSocket {
            this.headers = headers
            phone = listener
            return object : WireSocket {
                override fun sendText(text: String): Boolean {
                    if (closed) return false
                    val m = JSONObject(text)
                    sent.add(m)
                    if (m.getString("type") == "hello" && sendReady) {
                        text(JSONObject().put("type", "ready").put("protocol", protocol).put("stt", readyStt).put("tts", readyTts))
                    }
                    this@FakeLaptop.onMessage(m)
                    return true
                }

                override fun sendBinary(bytes: ByteArray): Boolean {
                    mic.add(bytes)
                    return !closed
                }

                override fun close(code: Int, reason: String) {
                    closed = true
                }
            }
        }

        fun text(m: JSONObject) = phone.onText(m.toString())
        fun audio(id: Int, pcm: ByteArray) = phone.onBinary(LaptopVoiceConnection.frame(LaptopVoiceConnection.KIND_TTS, id, pcm))
        fun drop(code: Int = 1006) {
            closed = true
            phone.onClosed(code, "gone")
        }
        fun types() = sent.map { it.getString("type") }
    }

    private fun connection(fake: FakeLaptop, onDrop: (String) -> Unit = {}) =
        LaptopVoiceConnection(fake, config, "legion-phone/test", scope, voice = null, onDrop = onDrop)

    // --- open ---------------------------------------------------------------------------------

    @Test
    fun `hello, ready, and the bearer on the upgrade`() = runBlocking<Unit> {
        val fake = FakeLaptop()
        val c = connection(fake)
        assertEquals(LaptopVoiceConnection.OpenResult.Ready, c.open(1_000))
        val hello = fake.sent.first()
        assertEquals("hello", hello.getString("type"))
        assertEquals(1, hello.getInt("protocol"))
        assertEquals("Authorization" to "Bearer invented-token", fake.headers.first())
        assertEquals(24_000, c.sampleRate)
        assertFalse(c.streamsText)
        assertFalse(c.endsTurns)
    }

    @Test
    fun `a laptop that never says ready times out and the socket is closed`() = runBlocking<Unit> {
        val fake = FakeLaptop(sendReady = false)
        val r = connection(fake).open(150)
        assertTrue(r is LaptopVoiceConnection.OpenResult.Failed)
        assertTrue(fake.closed)
    }

    @Test
    fun `a different protocol is refused`() = runBlocking<Unit> {
        val r = connection(FakeLaptop(protocol = 2)).open(500)
        assertEquals(LaptopVoiceConnection.OpenResult.Failed("protocol_2"), r)
    }

    @Test
    fun `busy at connect (close 1013) fails the open`() = runBlocking<Unit> {
        val fake = FakeLaptop(sendReady = false, onMessage = { if (it.getString("type") == "hello") drop(1013) })
        val r = connection(fake).open(500)
        assertEquals(LaptopVoiceConnection.OpenResult.Failed("busy"), r)
    }

    @Test
    fun `ready advertises streamed text and the service's own end of turn`() = runBlocking<Unit> {
        val fake = FakeLaptop(
            readyStt = JSONObject().put("end_of_turn", true),
            readyTts = JSONObject().put("sample_rate", 24000).put("text_streaming", true),
        )
        val c = connection(fake)
        c.open(500)
        assertTrue(c.streamsText)
        assertTrue(c.endsTurns)
    }

    // --- STT ----------------------------------------------------------------------------------

    @Test
    fun `an utterance streams up with its id and the final comes back`() = runBlocking<Unit> {
        val fake = FakeLaptop(onMessage = {
            if (it.getString("type") == "stt.end") {
                text(JSONObject().put("type", "stt.final").put("id", it.getInt("id")).put("text", " Set a timer. ").put("lang", "en").put("decode_ms", 90))
            }
        })
        val c = connection(fake)
        c.open(500)
        val u = c.begin()
        assertEquals(1, u.id % 2)
        u.audio(ByteArray(3_201) { 1 }, 0, 3_201) // odd length: trimmed to whole samples
        u.end()
        val r = u.result(1_000) as SttResult.Text
        assertEquals("Set a timer.", r.text)
        assertEquals(90L, r.decodeMs)
        val frame = fake.mic.single()
        assertEquals(LaptopVoiceConnection.KIND_MIC, frame[0])
        assertEquals(u.id, LaptopVoiceConnection.readId(frame))
        assertEquals(3_200, frame.size - LaptopVoiceConnection.HEADER_BYTES)
        assertEquals(listOf("hello", "stt.start", "stt.end"), fake.types())
    }

    @Test
    fun `the service ends the turn itself with an unasked final, and the phone sends nothing more for it`() = runBlocking<Unit> {
        val fake = FakeLaptop(readyStt = JSONObject().put("end_of_turn", true))
        val c = connection(fake)
        c.open(500)
        assertTrue(c.endsTurns)
        var ends = 0
        val u = c.begin { ends++ }
        fake.text(JSONObject().put("type", "stt.pause").put("id", u.id).put("prob", 0.4).put("silence_ms", 120))
        assertEquals(0, ends)
        fake.text(
            JSONObject().put("type", "stt.final").put("id", u.id).put("text", "Add oat milk.").put("reason", "end_of_turn"),
        )
        assertEquals(1, ends)
        assertEquals("Add oat milk.", (u.result(500) as SttResult.Text).text)
        u.end() // the id is closed: no stt.end goes out
        u.audio(ByteArray(320), 0, 320) // nor audio
        assertEquals(listOf("hello", "stt.start"), fake.types())
        assertTrue(fake.mic.isEmpty())
    }

    @Test
    fun `a final the phone asked for is not a service end`() = runBlocking<Unit> {
        val fake = FakeLaptop(readyStt = JSONObject().put("end_of_turn", true), onMessage = {
            if (it.getString("type") == "stt.end") {
                text(JSONObject().put("type", "stt.final").put("id", it.getInt("id")).put("text", "Hi.").put("reason", "client_end"))
            }
        })
        val c = connection(fake)
        c.open(500)
        var ends = 0
        val u = c.begin { ends++ }
        u.end()
        assertEquals("Hi.", (u.result(500) as SttResult.Text).text)
        assertEquals(0, ends)
    }

    @Test
    fun `the capabilities object is read as well`() = runBlocking<Unit> {
        val fake = FakeLaptop()
        val c = LaptopVoiceConnection(
            object : WireConnector {
                override fun connect(url: String, headers: List<Pair<String, String>>, listener: WireListener): WireSocket {
                    return object : WireSocket {
                        override fun sendText(text: String): Boolean {
                            if (JSONObject(text).getString("type") == "hello") {
                                listener.onText(
                                    JSONObject().put("type", "ready").put("protocol", 1)
                                        .put("capabilities", JSONObject().put("end_of_turn", true).put("text_streaming", true))
                                        .toString(),
                                )
                            }
                            return true
                        }
                        override fun sendBinary(bytes: ByteArray) = true
                        override fun close(code: Int, reason: String) {}
                    }
                }
            },
            config, "legion-phone/test", scope,
        )
        c.open(500)
        assertTrue(c.endsTurns)
        assertTrue(c.streamsText)
        assertFalse(fake.closed)
    }

    @Test
    fun `too long is not a failure - the final still comes`() = runBlocking<Unit> {
        val fake = FakeLaptop(onMessage = {
            if (it.getString("type") == "stt.end") {
                val id = it.getInt("id")
                text(JSONObject().put("type", "error").put("id", id).put("code", "too_long"))
                text(JSONObject().put("type", "stt.final").put("id", id).put("text", "A very long invented ramble."))
            }
        })
        val c = connection(fake)
        c.open(500)
        val u = c.begin()
        u.end()
        assertEquals("A very long invented ramble.", (u.result(1_000) as SttResult.Text).text)
    }

    @Test
    fun `a slow final times out and a busy one fails, both in words`() = runBlocking<Unit> {
        val fake = FakeLaptop(onMessage = {
            if (it.getString("type") == "stt.end" && it.getInt("id") > 1) {
                text(JSONObject().put("type", "error").put("id", it.getInt("id")).put("code", "busy").put("retry", false))
            }
        })
        val c = connection(fake)
        c.open(500)
        val slow = c.begin()
        slow.end()
        assertEquals(SttResult.Failed("timeout"), slow.result(100))
        val busy = c.begin()
        busy.end()
        assertEquals(SttResult.Failed("busy"), busy.result(1_000))
    }

    // --- TTS ----------------------------------------------------------------------------------

    @Test
    fun `a sentence's audio arrives in order and the clip completes`() = runBlocking<Unit> {
        val fake = FakeLaptop(onMessage = {
            if (it.getString("type") == "tts.speak") {
                val id = it.getInt("id")
                audio(id, byteArrayOf(1, 2))
                audio(id, byteArrayOf(3, 4))
                text(JSONObject().put("type", "tts.done").put("id", id).put("audio_ms", 40).put("first_audio_ms", 12))
            }
        })
        val c = connection(fake)
        c.open(500)
        val clip = c.speak("Twelve minutes, running.")
        assertEquals(0, clip.id % 2)
        val frames = withTimeout(1_000) { clip.frames.toList() }
        assertArrayEquals(byteArrayOf(1, 2), frames[0])
        assertArrayEquals(byteArrayOf(3, 4), frames[1])
        assertEquals(TtsOutcome.Done(40, 12), clip.outcome())
    }

    @Test
    fun `cancel stops a clip at once and late frames are dropped`() = runBlocking<Unit> {
        val fake = FakeLaptop()
        val c = connection(fake)
        c.open(500)
        val clip = c.speak("A long invented answer.")
        fake.audio(clip.id, byteArrayOf(9, 9))
        clip.cancel()
        fake.audio(clip.id, byteArrayOf(7, 7)) // late, after the cancel
        fake.text(JSONObject().put("type", "tts.done").put("id", clip.id).put("cancelled", true))
        assertEquals(TtsOutcome.Cancelled, clip.outcome())
        val frames = withTimeout(1_000) { clip.frames.toList() }
        assertTrue(frames.none { it.contentEquals(byteArrayOf(7, 7)) })
        assertTrue("tts.cancel" in fake.types())
    }

    @Test
    fun `a laptop that drops mid-reply fails every pending wait at once and says so`() = runBlocking<Unit> {
        val drops = mutableListOf<String>()
        val fake = FakeLaptop()
        val c = connection(fake) { drops.add(it) }
        c.open(500)
        val u = c.begin()
        val clip = c.speak("Half of an invented reply.")
        fake.audio(clip.id, byteArrayOf(1, 1))
        fake.drop(1006)
        assertEquals(TtsOutcome.Failed("dropped_1006"), clip.outcome())
        assertEquals(SttResult.Failed("dropped_1006"), u.result(1_000))
        assertEquals(listOf("dropped_1006"), drops)
        assertTrue(c.isDropped)
        // Anything asked after the drop fails at once.
        assertTrue(c.speak("More.").outcome() is TtsOutcome.Failed)
    }

    @Test
    fun `streamed text goes in as written and ends with stream end`() = runBlocking<Unit> {
        val fake = FakeLaptop(readyTts = JSONObject().put("text_streaming", true))
        val c = connection(fake)
        c.open(500)
        val s = c.openStream()
        s.append("Two ")
        s.append("minutes.")
        s.finish()
        assertEquals(listOf("hello", "tts.stream.start", "tts.stream.text", "tts.stream.text", "tts.stream.end"), fake.types())
        assertEquals("Two minutes.", s.text)
    }

    @Test
    fun `a voice that refuses streamed text turns the feature off`() = runBlocking<Unit> {
        val fake = FakeLaptop(readyTts = JSONObject().put("text_streaming", true), onMessage = {
            if (it.getString("type") == "tts.stream.start") {
                text(JSONObject().put("type", "error").put("id", it.getInt("id")).put("code", "bad_request"))
            }
        })
        val c = connection(fake)
        c.open(500)
        val s = c.openStream()
        assertEquals(TtsOutcome.Failed("bad_request"), s.outcome())
        assertFalse(c.streamsText)
    }

    @Test
    fun `close says bye and nothing reports a drop`() = runBlocking<Unit> {
        val drops = mutableListOf<String>()
        val fake = FakeLaptop()
        val c = connection(fake) { drops.add(it) }
        c.open(500)
        c.close()
        assertEquals("bye", fake.sent.last().getString("type"))
        assertTrue(drops.isEmpty())
    }

    @Test
    fun `the binary header round-trips`() {
        val f = LaptopVoiceConnection.frame(LaptopVoiceConnection.KIND_TTS, 0x01020304, byteArrayOf(5, 6))
        assertEquals(10, f.size)
        assertEquals(0x01020304, LaptopVoiceConnection.readId(f))
        assertEquals(0x04.toByte(), f[4])
    }

    // --- /health --------------------------------------------------------------------------------

    @Test
    fun `health replies decide the engine`() {
        assertTrue(LaptopProbe.interpret(200, """{"status":"ready","protocol":1}""", 30) is LaptopProbe.Result.Ready)
        assertEquals(LaptopProbe.Result.Busy("loading", 30), LaptopProbe.interpret(200, """{"status":"loading","protocol":1}""", 30))
        assertEquals(LaptopProbe.Result.Unauthorized, LaptopProbe.interpret(401, "", 30))
        assertEquals(LaptopProbe.Result.WrongProtocol(2), LaptopProbe.interpret(200, """{"status":"ready","protocol":2}""", 30))
        assertEquals(LaptopProbe.Result.Unreachable("bad_body", 30), LaptopProbe.interpret(200, "nope", 30))
        assertEquals(LaptopProbe.Result.Unreachable("http_502", 30), LaptopProbe.interpret(502, "", 30))
    }

    @Test
    fun `the probe against a real server - ready, unauthorized, and too slow`() = runBlocking<Unit> {
        // A bare HTTP/1.1 server on a socket (the JDK's HttpServer is not on the Android test
        // classpath): /health answers by the bearer, and sleeps when X-Slow is sent.
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                        val auth = headers.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(":")?.trim()
                        if (headers.any { it.startsWith("X-Slow:", true) }) Thread.sleep(800)
                        val (code, body) = if (auth == "Bearer invented-token") {
                            "200 OK" to """{"status":"ready","protocol":1}"""
                        } else {
                            "401 Unauthorized" to ""
                        }
                        val out = "HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n$body"
                        runCatching { s.getOutputStream().apply { write(out.toByteArray()); flush() } }
                    }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            assertTrue(LaptopProbe.probe(LaptopVoiceConfig(base, "invented-token"), 2_000) is LaptopProbe.Result.Ready)
            assertEquals(LaptopProbe.Result.Unauthorized, LaptopProbe.probe(LaptopVoiceConfig(base, "wrong"), 2_000))
            val slow = LaptopProbe.probe(LaptopVoiceConfig(base, "invented-token", listOf("X-Slow" to "1")), 200)
            assertTrue("too slow must be unreachable, was $slow", slow is LaptopProbe.Result.Unreachable)
            val off = LaptopProbe.probe(LaptopVoiceConfig("http://127.0.0.1:1", "t"), 500)
            assertTrue(off is LaptopProbe.Result.Unreachable)
            assertEquals(LaptopProbe.Result.NotConfigured, LaptopProbe.probe(LaptopVoiceConfig("", ""), 500))
        } finally {
            server.close()
        }
    }
}
