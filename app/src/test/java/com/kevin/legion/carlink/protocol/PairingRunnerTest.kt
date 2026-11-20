package com.kevin.legion.carlink.protocol

import com.kevin.legion.carlink.protocol.CarLinkVectors.hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Connect retry with jitter (3.2) and the runner's handling of the conversation. */
class PairingRunnerTest {
    private val p = CarLinkVectors.pairing
    private val ack = """{"t":"ack","v":1,"kc":"${LinkCrypto.b64(p.hex("ack_kc"))}"}"""

    /** Scripted unit: answers each frame the phone sends with the next canned reply. */
    private class ScriptedConn(private val replies: ArrayDeque<Received>) : PairingConnection {
        val sent = mutableListOf<String>()
        var closed = false
        val reader = Frames.FrameReader()
        override suspend fun send(frame: ByteArray) { sent += reader.feed(frame) }
        override suspend fun receive(timeoutMs: Long): Received = replies.removeFirstOrNull() ?: Received.Closed
        override fun close() { closed = true }
    }

    private fun client() = PairingClient(
        QrPayload.parse(CarLinkVectors.qrText())!!, CarLinkVectors.phoneKey(), MemoryKeys().ring(),
    ) { CarLinkVectors.vectorHandover() }

    @Test fun `jitter stays within 300 to 800 ms`() {
        val r = Random(7)
        val all = List(10_000) { PairingRunner.jitter(r) }
        assertTrue(all.all { it in 300L..800L })
        assertTrue(all.min() == 300L && all.max() == 800L)
    }

    @Test fun `refused three times then reached - three waits, then pairing goes on`() = runBlocking {
        val waits = mutableListOf<Long>()
        var tries = 0
        val conn = ScriptedConn(ArrayDeque(listOf(Received.Message(ack))))
        val runner = PairingRunner(
            transport = { _, _ -> if (++tries <= 3) null else conn },
            jitterMs = { 500L },
            sleep = { waits += it },
        )
        val c = client()
        val hello = c.hello()
        val job = async { runner.run(c, "10.0.0.1", 17300) }
        // After the ack it waits for code_ok; the script then reports Closed -> connection lost.
        val end = job.await()
        assertEquals(listOf(500L, 500L, 500L), waits)
        assertEquals(hello, conn.sent.first())
        assertEquals(PairingFailure.CONNECTION_LOST, (end as PairingStage.Failed).why)
    }

    @Test fun `refused four times gives up in words after three retries`() = runBlocking {
        val waits = mutableListOf<Long>()
        val runner = PairingRunner({ _, _ -> null }, { 300L }, { waits += it })
        val end = runner.run(client(), "10.0.0.1", 17300)
        assertEquals(3, waits.size)
        assertEquals(PairingFailure.UNREACHABLE, (end as PairingStage.Failed).why)
    }

    @Test fun `closed before ack is retried with the same hello`() = runBlocking {
        val conns = listOf(ScriptedConn(ArrayDeque()), ScriptedConn(ArrayDeque(listOf(Received.Message(ack)))))
        var i = 0
        val runner = PairingRunner({ _, _ -> conns[i++] }, { 400L }, {})
        runner.run(client(), "10.0.0.1", 17300)
        assertEquals(2, i)
        assertEquals(conns[0].sent.single(), conns[1].sent.first())
        assertTrue(conns.all { it.closed })
    }

    @Test fun `cancelling while the digits are shown sends abort cancelled`() = runBlocking {
        val shown = CompletableDeferred<Unit>()
        val conn = object : PairingConnection {
            val sent = mutableListOf<String>()
            val reader = Frames.FrameReader()
            var n = 0
            override suspend fun send(frame: ByteArray) { sent += reader.feed(frame) }
            override suspend fun receive(timeoutMs: Long): Received =
                if (n++ == 0) Received.Message(ack) else { shown.complete(Unit); awaitCancellation() }
            override fun close() = Unit
        }
        val runner = PairingRunner({ _, _ -> conn }, { 300L }, {})
        val job = async { runner.run(client(), "10.0.0.1", 17300) }
        shown.await()
        yield()
        job.cancel()
        runCatching { job.await() }
        assertEquals("""{"t":"abort","why":"cancelled"}""", conn.sent.last())
    }
}
