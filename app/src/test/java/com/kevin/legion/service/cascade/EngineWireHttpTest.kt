package com.kevin.legion.service.cascade

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
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [EngineWireHttp] over a real socket (review item 10), against a small HTTP/1.1 server written here
 * on `java.net.ServerSocket` (the JDK's own HTTP server is not on the Android unit-test classpath, and
 * no dependency is added). The chunked streamed upload is read frame by frame as the engine's reader
 * reads it; the event stream is parsed; a stream that drops mid-answer ends without a crash; refusals
 * keep their status, code and Retry-After; the streamed body is never sent twice. Invented data.
 * Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWireHttpTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    private val frames = CopyOnWriteArrayList<Pair<Char, ByteArray>>()
    private val headers = CopyOnWriteArrayList<String>()

    @After
    fun stop() {
        sockets.forEach { runCatching { it.close() } }
    }

    /** Serves each connection with [answer] on its own thread; the request's head is read first. */
    private fun serve(answer: (head: List<String>, input: InputStream, socket: Socket) -> Unit): String {
        val server = ServerSocket(0).also { sockets.add(it) }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { socket ->
                        val input = socket.getInputStream()
                        val head = readHead(input)
                        headers.addAll(head)
                        answer(head, input, socket)
                    }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}"
    }

    /** One line up to its newline, without the carriage return; null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        var b = input.read()
        while (b >= 0 && b != '\n'.code) {
            line.append(b.toChar())
            b = input.read()
        }
        return if (b < 0 && line.isEmpty()) null else line.toString().trimEnd('\r')
    }

    private fun readHead(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        var line = readLine(input)
        while (!line.isNullOrEmpty()) {
            lines.add(line)
            line = readLine(input)
        }
        return lines
    }

    /** A chunked request body, de-chunked. */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.trim()?.toIntOrNull(16) ?: 0
            if (size == 0) break
            val chunk = ByteArray(size)
            DataInputStream(input).readFully(chunk)
            out.write(chunk)
            input.read()
            input.read()
        }
        return out.toByteArray()
    }

    /** The engine's `FrameReader` rule on a whole body: M, A..., E. */
    private fun readFrames(body: ByteArray) {
        val input = DataInputStream(body.inputStream())
        while (input.available() > 0) {
            val kind = input.read()
            val payload = ByteArray(input.readInt())
            input.readFully(payload)
            frames.add(kind.toChar() to payload)
        }
    }

    private fun respond(socket: Socket, status: String, extra: String, body: String) {
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            val head = "HTTP/1.1 $status\r\n${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun wire(base: String) = EngineWireHttp({ base }, { "test-token" }, scope)

    @Test
    fun `a streamed utterance goes up as chunked frames and its answer comes back as events`() = runBlocking {
        val base = serve { _, input, socket ->
            readFrames(readChunked(input))
            val events = "event: waiting\ndata: {}\n\n" +
                "event: turn\ndata: {\"turn_id\": \"t1\", \"seq\": 1}\n\n" +
                "event: heard\ndata: {\"text\": \"what time is it\"}\n\n" +
                "event: done\ndata: {\"status\": \"done\"}\n\n"
            respond(socket, "200 OK", "Content-Type: text/event-stream\r\n", events)
        }
        val up = wire(base).openStream(JSONObject().put("client", "phone"))
        val pcm = ByteArray(6_400) { 3 }
        EngineFrames.audio(pcm, 0, pcm.size).forEach { up.send(it) }
        up.send(EngineFrames.end(pcm.size.toLong()))
        up.finish()
        val reply = withTimeout(10_000) { up.reply() } as WireReply.Events
        val got = mutableListOf<EngineEvent>()
        withTimeout(10_000) { for (e in reply.events) got.add(e) }
        assertEquals(listOf('M', 'A', 'E'), frames.map { it.first })
        assertEquals("""{"bytes":6400}""", String(frames.last().second))
        assertTrue(headers.any { it.equals("Authorization: Token test-token", ignoreCase = true) })
        assertTrue(headers.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) })
        assertEquals(EngineEvent.Heard("what time is it"), got[2])
        assertEquals(4, got.size)
    }

    @Test
    fun `refusals keep their status, code and Retry-After`() = runBlocking {
        val status = AtomicInteger(401)
        val base = serve { _, _, socket ->
            val code = status.get()
            respond(
                socket,
                "$code Refused",
                "Content-Type: application/json\r\nRetry-After: 3\r\n",
                """{"detail": "No.", "code": "c$code"}""",
            )
        }
        for (code in listOf(401, 429, 503)) {
            status.set(code)
            val r = wire(base).status("t1") as WireReply.Json
            assertEquals(code, r.status)
            assertEquals("c$code", r.code)
            assertEquals(3L, r.retryAfterS)
        }
    }

    @Test
    fun `an answer that drops mid-stream ends the events, never a crash`() = runBlocking {
        val base = serve { _, _, socket ->
            val event = "event: turn\ndata: {\"turn_id\": \"t1\"}\n\n"
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        Integer.toHexString(event.length) + "\r\n" + event + "\r\n").toByteArray(),
                )
                flush()
            }
        }
        val reply = wire(base).status("t1") as WireReply.Events
        val got = mutableListOf<EngineEvent>()
        withTimeout(10_000) {
            var next = reply.events.receiveCatching().getOrNull()
            while (next != null) {
                got.add(next)
                next = reply.events.receiveCatching().getOrNull()
            }
        }
        assertEquals(listOf<EngineEvent>(EngineEvent.Turn("t1", null, 0, 1)), got)
    }

    @Test
    fun `the streamed body is sent once, a dropped connection is never retried with a drained body`() =
        runBlocking {
            val accepted = AtomicInteger()
            val base = serve { _, _, _ -> accepted.incrementAndGet() }
            val up = wire(base).openStream(JSONObject())
            repeat(REPEATS) { up.send(EngineFrames.audio(ByteArray(3_200) { 1 }, 0, 3_200).single()) }
            up.send(EngineFrames.end(REPEATS * 3_200L))
            up.finish()
            val reply = withTimeout(10_000) { up.reply() }
            Thread.sleep(SETTLE_MS)
            assertTrue(reply is WireReply.Unreachable)
            assertEquals("one connection: OkHttp did not replay the body", 1, accepted.get())
        }

    private companion object {
        const val SETTLE_MS = 300L
        const val REPEATS = 40
    }
}
