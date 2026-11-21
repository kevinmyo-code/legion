package com.kevin.legion.service.cascade

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [EngineWireHttp] over a real socket (review item 10): the JDK's own HTTP server and a raw server
 * socket, no new dependency. The chunked streamed upload is read frame by frame as the engine's
 * reader reads it; the event stream is parsed; a stream that drops mid-answer ends without a crash;
 * the refusals keep their status, code and Retry-After; the streamed body is never sent twice.
 * Invented data. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWireHttpTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: HttpServer? = null
    private val frames = CopyOnWriteArrayList<Pair<Char, ByteArray>>()
    private val headers = CopyOnWriteArrayList<String>()

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun serve(path: String, handler: (HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(path) { ex ->
            headers.add("auth=" + ex.requestHeaders.getFirst("Authorization"))
            headers.add("te=" + ex.requestHeaders.getFirst("Transfer-encoding"))
            handler(ex)
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun wire(base: String) = EngineWireHttp({ base }, { "test-token" }, scope)

    /** The engine's `FrameReader` loop on the request body: M, A..., E. */
    private fun readFrames(ex: HttpExchange) {
        val input = DataInputStream(ex.requestBody)
        while (true) {
            val kind = input.read()
            if (kind < 0) break
            val payload = ByteArray(input.readInt())
            input.readFully(payload)
            frames.add(kind.toChar() to payload)
        }
    }

    private fun answerEvents(ex: HttpExchange, events: String) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { it.write(events.toByteArray()) }
    }

    @Test
    fun `a streamed utterance goes up as chunked frames and its answer comes back as events`() = runBlocking {
        val base = serve("/api/agents/turn/stream") { ex ->
            readFrames(ex)
            answerEvents(
                ex,
                "event: waiting\ndata: {}\n\n" +
                    "event: turn\ndata: {\"turn_id\": \"t1\", \"seq\": 1}\n\n" +
                    "event: heard\ndata: {\"text\": \"what time is it\"}\n\n" +
                    "event: done\ndata: {\"status\": \"done\"}\n\n",
            )
        }
        val up = wire(base).openStream(JSONObject().put("client", "phone"))
        val pcm = ByteArray(6_400) { 3 }
        EngineFrames.audio(pcm, 0, pcm.size).forEach { up.send(it) }
        up.send(EngineFrames.end(pcm.size.toLong()))
        up.finish()
        val reply = withTimeout(10_000) { up.reply() } as WireReply.Events
        val got = mutableListOf<EngineEvent>()
        for (e in reply.events) got.add(e)
        assertEquals(listOf('M', 'A', 'E'), frames.map { it.first })
        assertEquals("""{"bytes":6400}""", String(frames.last().second))
        assertTrue(headers.contains("auth=Token test-token"))
        assertTrue(headers.contains("te=chunked"))
        assertEquals(EngineEvent.Heard("what time is it"), got[2])
        assertEquals(4, got.size)
    }

    @Test
    fun `refusals keep their status, code and Retry-After`() = runBlocking {
        val status = AtomicInteger(401)
        val base = serve("/api/agents/turn/t1/tool_result") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.responseHeaders.add("Retry-After", "3")
            val body = """{"detail": "No.", "code": "c${status.get()}"}""".toByteArray()
            ex.sendResponseHeaders(status.get(), body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        for (code in listOf(401, 429, 503)) {
            status.set(code)
            val r = wire(base).toolResult("t1", JSONObject().put("call_id", "k1")) as WireReply.Json
            assertEquals(code, r.status)
            assertEquals("c$code", r.code)
            assertEquals(3L, r.retryAfterS)
        }
    }

    @Test
    fun `an answer that drops mid-stream ends the events, never a crash`() = runBlocking {
        val socket = ServerSocket(0)
        thread {
            socket.accept().use { c ->
                c.getInputStream().read(ByteArray(4096))
                val event = "event: turn\ndata: {\"turn_id\": \"t1\"}\n\n"
                val out = c.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        Integer.toHexString(event.length) + "\r\n" + event + "\r\n").toByteArray(),
                )
                out.flush()
            }
        }
        val reply = wire("http://127.0.0.1:${socket.localPort}").status("t1") as WireReply.Events
        val got = mutableListOf<EngineEvent>()
        withTimeout(10_000) {
            var next = reply.events.receiveCatching().getOrNull()
            while (next != null) {
                got.add(next)
                next = reply.events.receiveCatching().getOrNull()
            }
        }
        socket.close()
        assertEquals(listOf<EngineEvent>(EngineEvent.Turn("t1", null, 0, 1)), got)
    }

    @Test
    fun `the streamed body is sent once: a dropped connection is never retried with a drained body`() = runBlocking {
        val socket = ServerSocket(0)
        val accepted = AtomicInteger()
        thread {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                c.getInputStream().read(ByteArray(64))
                c.close()
            }
        }
        val up = wire("http://127.0.0.1:${socket.localPort}").openStream(JSONObject())
        repeat(20) { up.send(EngineFrames.audio(ByteArray(3_200) { 1 }, 0, 3_200).single()) }
        up.send(EngineFrames.end(64_000))
        up.finish()
        val reply = withTimeout(10_000) { up.reply() }
        Thread.sleep(SETTLE_MS)
        socket.close()
        assertTrue(reply is WireReply.Unreachable)
        assertEquals("one connection: OkHttp did not replay the body", 1, accepted.get())
    }

    private companion object {
        const val SETTLE_MS = 300L
    }
}
