// Guard clauses: the document says "refuse otherwise" at each check, and one early return per
// refusal reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/** One TCP connection to the unit's pairing listener. */
interface PairingConnection : Closeable {
    /** Sends one whole frame. Throws [IOException] when the connection is gone. */
    suspend fun send(frame: ByteArray)

    /** The next whole message, or null when the peer closed. Throws [IOException] on a failure. */
    suspend fun receive(timeoutMs: Long): Received
}

sealed interface Received {
    class Message(val text: String) : Received { override fun toString() = "Message" }
    object Closed : Received
    object TimedOut : Received
}

/** Opens TCP connections; null = refused or unreachable. */
fun interface PairingTransport {
    suspend fun connect(host: String, port: Int): PairingConnection?
}

/**
 * Drives a [PairingClient] over real connections (section 3.2): sends the hello as soon as a
 * connection opens; if it is refused, closed before `ack`, or fails, retries up to 3 times with a
 * random 300 to 800 ms wait, then gives up in words. Cancelling the coroutine is the person's cancel.
 */
class PairingRunner(
    private val transport: PairingTransport,
    private val jitterMs: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    /**
     * Called after every stage change, and AWAITED. The suspend is load-bearing: the phone mints
     * the unit's engine sign-in when the stage reaches `DigitsShown`, which is a network call, and
     * the handover frame is built by [PairingClient.onMessage] the moment `code_ok` arrives. This
     * loop cannot take that message until this call returns, so awaiting here is what keeps the
     * mint from landing after the frame it belongs in (it is why `CarLinkController` no longer
     * launches it into the background).
     */
    private val onStage: suspend (PairingStage) -> Unit = {},
) {
    suspend fun run(client: PairingClient, host: String, port: Int): PairingStage {
        var retries = 0
        while (true) {
            val conn = transport.connect(host, port)
            if (conn != null) {
                val reachedAck = conn.use { converse(client, it) }
                if (reachedAck) return client.stage
            }
            if (retries >= MAX_RETRIES) {
                client.giveUp()
                onStage(client.stage)
                return client.stage
            }
            retries++
            sleep(jitterMs())
        }
    }

    /** True once the conversation got past `ack` (or failed for a reason a retry cannot fix). */
    private suspend fun converse(client: PairingClient, conn: PairingConnection): Boolean {
        try {
            conn.send(Frames.encode(client.hello()))
            while (true) {
                val before = client.stage
                val effects = when (val r = conn.receive(timeoutFor(before))) {
                    is Received.Message -> client.onMessage(r.text)
                    Received.Closed -> if (before == PairingStage.AwaitingAck) return false else client.onDisconnected()
                    Received.TimedOut -> if (before == PairingStage.AwaitingAck) return false else client.onTimeout()
                }
                if (client.stage != before) onStage(client.stage)
                if (perform(conn, effects)) return true
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { perform(conn, client.cancel()) } }
            onStage(client.stage)
            throw e
        } catch (_: IOException) {
            if (client.stage == PairingStage.AwaitingAck) return false
            perform(conn, client.onDisconnected())
            onStage(client.stage)
            return true
        } catch (_: Frames.BadFrame) {
            if (client.stage == PairingStage.AwaitingAck) return false
            perform(conn, client.onMessage("{}"))
            onStage(client.stage)
            return true
        }
    }

    /** Runs [effects]; true when one of them ends the conversation. */
    private suspend fun perform(conn: PairingConnection, effects: List<PairingEffect>): Boolean {
        var closed = false
        for (e in effects) {
            when (e) {
                is PairingEffect.Send -> runCatching { conn.send(Frames.encode(e.json)) }
                PairingEffect.Close -> closed = true
            }
        }
        return closed
    }

    companion object {
        const val MAX_RETRIES = 3
        const val JITTER_MIN_MS = 300L
        const val JITTER_MAX_MS = 800L

        /** Phone-side waits. The document sets the unit's limits (3.6), none for the phone. */
        const val ACK_WAIT_MS = 5_000L
        const val DIGITS_WAIT_MS = 135_000L
        const val DONE_WAIT_MS = 30_000L
        const val AFTER_STORED_WAIT_MS = 5_000L

        /** 3.2's wait between connect tries: random, 300 to 800 ms inclusive. */
        fun jitter(random: kotlin.random.Random): Long = random.nextLong(JITTER_MIN_MS, JITTER_MAX_MS + 1)

        fun timeoutFor(stage: PairingStage): Long = when (stage) {
            PairingStage.AwaitingAck -> ACK_WAIT_MS
            is PairingStage.DigitsShown -> DIGITS_WAIT_MS
            PairingStage.AwaitingDone -> DONE_WAIT_MS
            else -> AFTER_STORED_WAIT_MS
        }
    }
}
